package com.angelmilanov.awsdbconnector.aws

import com.angelmilanov.awsdbconnector.model.AppSettings
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.NoticeLevel
import com.angelmilanov.awsdbconnector.util.Notifications
import com.angelmilanov.awsdbconnector.util.Platform
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Something that can bring expired credentials back (asks the user to sign in). */
fun interface LoginGate {
    /** Completes with true when credentials for [awsProfile] should work again. */
    fun login(awsProfile: String, userInitiated: Boolean): CompletableFuture<Boolean>
}

/**
 * Re-authenticates expired SSO sessions by running `aws sso login`, which opens the browser.
 * Logins are de-duplicated per SSO session, and automatic prompts are rate limited so an
 * unattended machine does not keep opening browser tabs.
 */
class SsoLoginManager(
    private val settings: () -> AppSettings,
    private val log: ActivityLog,
    private val notifications: Notifications,
) : LoginGate {
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val lastAutoPrompt = ConcurrentHashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<(ssoKey: String) -> Unit>()

    /** Called (with the SSO session key) after a successful sign-in. */
    fun addSuccessListener(l: (String) -> Unit) {
        listeners += l
    }

    override fun login(awsProfile: String, userInitiated: Boolean): CompletableFuture<Boolean> {
        val info = AwsProfiles.find(awsProfile)
        val key = info?.ssoKey
        val loginProfile = info?.loginProfile
        if (key == null || loginProfile == null) {
            log.log("sso", "Profile '$awsProfile' does not use AWS SSO – cannot re-authenticate automatically")
            return CompletableFuture.completedFuture(false)
        }
        val label = info.ssoLabel ?: loginProfile
        synchronized(this) {
            inFlight[key]?.let { return it }
            if (!userInitiated) {
                val now = System.currentTimeMillis()
                val recentlyPrompted = now - (lastAutoPrompt[key] ?: 0L) < AUTO_PROMPT_COOLDOWN_MS
                if (!settings().autoSsoLogin || recentlyPrompted) {
                    if (!recentlyPrompted) {
                        lastAutoPrompt[key] = now
                        notifications.show(
                            "AWS sign-in required",
                            "The SSO session '$label' has expired. Use \"Sign in\" in the tray menu.",
                            NoticeLevel.WARNING,
                        )
                    }
                    return CompletableFuture.completedFuture(false)
                }
                lastAutoPrompt[key] = now
            }
            val future = CompletableFuture<Boolean>()
            inFlight[key] = future
            Thread.ofVirtual().name("sso-login").start {
                val ok = runCatching { runLogin(loginProfile, label) }.getOrElse {
                    log.log("sso", "Sign-in failed: ${it.message}")
                    false
                }
                inFlight.remove(key)
                if (ok) {
                    lastAutoPrompt.remove(key)
                    log.log("sso", "Signed in to '$label'")
                }
                future.complete(ok)
                if (ok) listeners.forEach { l -> runCatching { l(key) } }
            }
            return future
        }
    }

    private fun runLogin(loginProfile: String, label: String): Boolean {
        val aws = Platform.findAwsCli(settings().awsCliPath)
        if (aws == null) {
            notifications.show(
                "AWS sign-in required",
                "The AWS CLI was not found. Run 'aws sso login --profile $loginProfile' manually or set its path in Settings.",
                NoticeLevel.ERROR,
            )
            return false
        }
        notifications.show(
            "AWS sign-in required",
            "The SSO session '$label' has expired. Complete the sign-in in your browser.",
            NoticeLevel.WARNING,
        )
        log.log("sso", "Running: aws sso login --profile $loginProfile")
        val process = ProcessBuilder(aws, "sso", "login", "--profile", loginProfile)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        Thread.ofVirtual().start {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { if (it.isNotBlank()) log.log("sso", it) }
            }
        }
        if (!process.waitFor(LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            log.log("sso", "Sign-in timed out after $LOGIN_TIMEOUT_MINUTES minutes")
            return false
        }
        return process.exitValue() == 0
    }

    companion object {
        private const val AUTO_PROMPT_COOLDOWN_MS = 5 * 60 * 1000L
        const val LOGIN_TIMEOUT_MINUTES = 5L
    }
}
