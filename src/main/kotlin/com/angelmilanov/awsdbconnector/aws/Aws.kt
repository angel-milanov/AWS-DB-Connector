package com.angelmilanov.awsdbconnector.aws

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.profiles.Profile
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.ssm.SsmClient
import java.time.Duration

/** Thrown when AWS credentials are missing / expired and the user has to sign in again. */
class AuthRequiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What we need to know about an entry of ~/.aws/config. */
data class AwsProfileInfo(
    val name: String,
    val region: String?,
    /** Identifies the SSO session (shared between profiles); null if the profile does not use SSO. */
    val ssoKey: String?,
    /** Human readable SSO session name. */
    val ssoLabel: String?,
    /** Profile to pass to `aws sso login --profile`. */
    val loginProfile: String?,
)

object AwsProfiles {
    /** Reads the shared AWS config / credentials files (profile names and settings only). */
    fun load(): List<AwsProfileInfo> = try {
        val profiles = ProfileFile.defaultProfileFile().profiles()
        profiles.values.map { describe(it, profiles) }.sortedBy { it.name.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }

    fun find(name: String): AwsProfileInfo? = load().firstOrNull { it.name == name }

    private fun describe(profile: Profile, all: Map<String, Profile>): AwsProfileInfo {
        var current: Profile? = profile
        var region: String? = null
        // Follow source_profile chains (role assumption on top of an SSO profile).
        repeat(6) {
            val p = current ?: return@repeat
            if (region == null) region = p.property("region").orElse(null)
            val session = p.property("sso_session").orElse(null)
            val startUrl = p.property("sso_start_url").orElse(null)
            if (session != null) return AwsProfileInfo(profile.name(), region, "session:$session", session, p.name())
            if (startUrl != null) return AwsProfileInfo(profile.name(), region, "url:$startUrl", startUrl, p.name())
            current = p.property("source_profile").orElse(null)?.let { all[it] }?.takeIf { it !== p }
        }
        return AwsProfileInfo(profile.name(), region, null, null, null)
    }
}

/** Short lived bundle of SDK clients for one AWS profile. Created fresh so new SSO tokens are picked up. */
class AwsContext(val profileName: String, regionOverride: String) : AutoCloseable {
    private val profileFile: ProfileFile = ProfileFile.defaultProfileFile()
    private val credentials: ProfileCredentialsProvider

    val region: Region

    init {
        val profile = profileFile.profile(profileName).orElseThrow {
            IllegalStateException("AWS profile '$profileName' was not found in your AWS config")
        }
        val regionName = regionOverride.trim().ifBlank { AwsProfiles.find(profileName)?.region.orEmpty() }
            .ifBlank { profile.property("region").orElse("") }
        if (regionName.isBlank()) {
            throw IllegalStateException("No region configured for AWS profile '$profileName' – set one in the connection")
        }
        region = Region.of(regionName)
        credentials = ProfileCredentialsProvider.builder().profileFile(profileFile).profileName(profileName).build()
    }

    private val overrides: ClientOverrideConfiguration = ClientOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofSeconds(40))
        .apiCallAttemptTimeout(Duration.ofSeconds(20))
        .build()

    private val ssmLazy = lazy {
        SsmClient.builder().region(region).credentialsProvider(credentials).overrideConfiguration(overrides).build()
    }
    private val ec2Lazy = lazy {
        Ec2Client.builder().region(region).credentialsProvider(credentials).overrideConfiguration(overrides).build()
    }
    private val rdsLazy = lazy {
        RdsClient.builder().region(region).credentialsProvider(credentials).overrideConfiguration(overrides).build()
    }
    val ssm: SsmClient get() = ssmLazy.value
    val ec2: Ec2Client get() = ec2Lazy.value
    val rds: RdsClient get() = rdsLazy.value

    override fun close() {
        for (l in listOf(ssmLazy, ec2Lazy, rdsLazy)) if (l.isInitialized()) runCatching { l.value.close() }
        runCatching { credentials.close() }
    }
}

object AwsErrors {
    private val authCodes = setOf(
        "ExpiredToken", "ExpiredTokenException", "RequestExpired", "UnrecognizedClientException",
        "InvalidClientTokenId", "AuthFailure", "UnauthorizedException", "InvalidGrantException",
    )
    private val authClassNames = setOf("ExpiredTokenException", "UnauthorizedException", "InvalidGrantException")
    private val authPhrases = listOf(
        "token is expired", "token has expired", "sso session", "aws sso login", "unable to load sso token",
        "error loading sso token", "session token not found or invalid", "security token included in the request is expired",
        "sso token", "unable to load credentials",
    )

    private fun chain(t: Throwable): Sequence<Throwable> =
        generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(12)

    /** True if the failure means "credentials are gone / expired" rather than e.g. a network problem. */
    fun isAuthError(t: Throwable): Boolean = chain(t).any { e ->
        (e is AwsServiceException && e.awsErrorDetails()?.errorCode() in authCodes) ||
            e.javaClass.simpleName in authClassNames ||
            e.message?.lowercase()?.let { m -> authPhrases.any { it in m } } == true
    }

    /** Compact, user presentable message. */
    fun describe(t: Throwable): String {
        val service = chain(t).filterIsInstance<AwsServiceException>().firstOrNull()
        if (service != null) {
            val d = service.awsErrorDetails()
            return listOfNotNull(d?.errorCode(), d?.errorMessage() ?: service.message).joinToString(": ")
        }
        val root = chain(t).last()
        val msg = (t.message ?: root.message ?: t.javaClass.simpleName).lineSequence().first().trim()
        return when (root) {
            is java.net.UnknownHostException -> "No network connection (cannot resolve ${root.message})"
            is java.net.ConnectException, is java.net.SocketTimeoutException -> "Network problem: $msg"
            else -> msg
        }.take(300)
    }
}
