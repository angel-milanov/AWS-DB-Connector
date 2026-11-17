package com.angelmilanov.awsdbconnector.tunnel

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.aws.AwsContext
import com.angelmilanov.awsdbconnector.aws.AwsErrors
import com.angelmilanov.awsdbconnector.model.AppSettings
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.util.Platform
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import software.amazon.awssdk.services.ssm.model.SessionFilter
import software.amazon.awssdk.services.ssm.model.SessionFilterKey
import software.amazon.awssdk.services.ssm.model.SessionState
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TunnelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An established port forwarding session that accepts TCP connections on [localPort] (loopback). */
interface BackendSession {
    val id: String
    val localPort: Int
    fun isAlive(): Boolean

    /** Registers a callback that runs once when the session ends for whatever reason. */
    fun onExit(callback: () -> Unit)

    /** Asks the remote side whether the session still exists: true / false, or null if unknown. */
    fun checkRemote(): Boolean?
    fun close()
}

fun interface TunnelBackend {
    /** @throws AuthRequiredException if the credentials expired, [TunnelException] for other failures. */
    fun open(profile: ConnectionProfile, log: (String) -> Unit): BackendSession
}

/**
 * Opens an SSM port forwarding session to the database through the bastion host:
 * `StartSession` is called with the AWS SDK, the data channel is then handled by the
 * AWS session-manager-plugin, which listens on a private, randomly chosen loopback port.
 */
class SsmBackend(private val settings: () -> AppSettings) : TunnelBackend {

    override fun open(profile: ConnectionProfile, log: (String) -> Unit): BackendSession {
        val plugin = Platform.findSessionManagerPlugin(settings().pluginPath)
            ?: throw TunnelException("AWS session-manager-plugin not found – install it or set its path in Settings")
        val internalPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        val region: String
        val endpoint: String
        val sessionId: String
        val responseJson: String
        try {
            AwsContext(profile.awsProfile, profile.region).use { ctx ->
                region = ctx.region.id()
                val suffix = runCatching { ctx.region.metadata().partition().dnsSuffix() }.getOrNull() ?: "amazonaws.com"
                endpoint = "https://ssm.$region.$suffix"
                val response = ctx.ssm.startSession { r ->
                    r.target(profile.bastionInstanceId)
                        .documentName(DOCUMENT)
                        .reason("AWS DB Connector: ${profile.name}".take(256))
                        .parameters(
                            mapOf(
                                "host" to listOf(profile.dbHost),
                                "portNumber" to listOf(profile.dbPort.toString()),
                                "localPortNumber" to listOf(internalPort.toString()),
                            ),
                        )
                }
                sessionId = response.sessionId()
                responseJson = buildJsonObject {
                    put("SessionId", response.sessionId())
                    put("TokenValue", response.tokenValue())
                    put("StreamUrl", response.streamUrl())
                }.toString()
            }
        } catch (e: Exception) {
            if (AwsErrors.isAuthError(e)) throw AuthRequiredException(AwsErrors.describe(e), e)
            throw TunnelException(AwsErrors.describe(e), e)
        }

        log("Session $sessionId started via ${profile.bastionInstanceId} ($region)")
        // The session token is handed over in an environment variable, never on the command line.
        val target = buildJsonObject { put("Target", profile.bastionInstanceId) }.toString()
        val builder = ProcessBuilder(
            plugin, RESPONSE_ENV, region, "StartSession", profile.awsProfile, Platform.jsonArg(target), endpoint,
        ).redirectErrorStream(true)
        builder.environment()[RESPONSE_ENV] = responseJson
        val process = try {
            builder.start()
        } catch (e: Exception) {
            terminateRemote(profile, sessionId)
            throw TunnelException("Could not start session-manager-plugin: ${e.message}", e)
        }

        val ready = CountDownLatch(1)
        Thread.ofVirtual().name("plugin-output").start {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) log(line.trim())
                    if (line.contains("Waiting for connections", true)) ready.countDown()
                }
            }
        }
        process.onExit().thenRun { ready.countDown() }
        val session = PluginSession(profile, sessionId, internalPort, process)
        val signalled = ready.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!process.isAlive) {
            throw TunnelException("session-manager-plugin exited with code ${process.exitValue()} – see the activity log")
        }
        if (!signalled) {
            session.close()
            throw TunnelException("Timed out waiting for the Session Manager tunnel to become ready")
        }
        return session
    }

    private fun terminateRemote(profile: ConnectionProfile, sessionId: String) {
        Thread.ofVirtual().start {
            runCatching {
                AwsContext(profile.awsProfile, profile.region).use { it.ssm.terminateSession { r -> r.sessionId(sessionId) } }
            }
        }
    }

    private inner class PluginSession(
        private val profile: ConnectionProfile,
        override val id: String,
        override val localPort: Int,
        private val process: Process,
    ) : BackendSession {
        override fun isAlive() = process.isAlive

        override fun onExit(callback: () -> Unit) {
            process.onExit().thenRun(callback)
        }

        override fun checkRemote(): Boolean? = try {
            AwsContext(profile.awsProfile, profile.region).use { ctx ->
                ctx.ssm.describeSessions { r ->
                    r.state(SessionState.ACTIVE)
                        .filters(SessionFilter.builder().key(SessionFilterKey.SESSION_ID).value(id).build())
                }.sessions().any { it.sessionId() == id }
            }
        } catch (_: Exception) {
            null // no permission, expired credentials, offline … – cannot tell
        }

        override fun close() {
            val wasAlive = process.isAlive
            runCatching { process.descendants().forEach { it.destroy() } }
            process.destroy()
            Thread.ofVirtual().start {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            // Killing the plugin leaves the session open on the AWS side until it times out.
            if (wasAlive) terminateRemote(profile, id)
        }
    }

    companion object {
        const val DOCUMENT = "AWS-StartPortForwardingSessionToRemoteHost"
        private const val RESPONSE_ENV = "AWS_SSM_START_SESSION_RESPONSE"
        private const val STARTUP_TIMEOUT_SECONDS = 30L
    }
}
