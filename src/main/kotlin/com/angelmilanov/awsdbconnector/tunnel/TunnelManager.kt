package com.angelmilanov.awsdbconnector.tunnel

import com.angelmilanov.awsdbconnector.aws.AwsProfiles
import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.AppSettings
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.NoticeLevel
import com.angelmilanov.awsdbconnector.util.Notifications
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns the runtime [Tunnel] of every connection profile and keeps them in sync with the configuration. */
class TunnelManager(
    private val settings: () -> AppSettings,
    private val backend: TunnelBackend,
    private val loginGate: LoginGate,
    private val notifications: Notifications,
    private val log: ActivityLog,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tunnel-scheduler").apply { isDaemon = true }
    }
    private val tunnels = LinkedHashMap<String, Tunnel>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        scheduler.scheduleWithFixedDelay({
            val interval = settings().healthCheckSeconds
            all().forEach { t -> Thread.ofVirtual().start { runCatching { t.healthCheck(interval) } } }
        }, 5, 5, TimeUnit.SECONDS)
    }

    /** Listeners are invoked on arbitrary threads whenever any tunnel changes. */
    fun addListener(l: () -> Unit) {
        listeners += l
    }

    private fun fire() = listeners.forEach { runCatching { it() } }

    @Synchronized
    fun all(): List<Tunnel> = tunnels.values.toList()

    @Synchronized
    fun get(id: String): Tunnel? = tunnels[id]

    private fun create(profile: ConnectionProfile) = Tunnel(
        profile, backend, loginGate, notifications, scheduler,
        logger = { log.log(profile.name, it) },
        onChange = { fire() },
    )

    /** Applies a new list of profiles: removed ones are stopped, changed ones are restarted. */
    fun sync(profiles: List<ConnectionProfile>, startNew: Boolean) {
        val toStop = mutableListOf<Tunnel>()
        val toStart = mutableListOf<Tunnel>()
        synchronized(this) {
            val next = LinkedHashMap<String, Tunnel>()
            for (p in profiles) {
                val existing = tunnels[p.id]
                when {
                    existing == null -> next[p.id] = create(p).also { if (startNew && p.autoConnect) toStart += it }
                    existing.profile == p -> next[p.id] = existing
                    else -> {
                        toStop += existing
                        next[p.id] = create(p).also { if (existing.isActive) toStart += it }
                    }
                }
            }
            toStop += tunnels.values.filter { it.profile.id !in next.keys }
            tunnels.clear()
            tunnels.putAll(next)
        }
        // Stop first so that a changed profile can re-bind its port.
        Thread.ofVirtual().start {
            toStop.forEach { it.stop() }
            toStart.forEach { it.start() }
            fire()
        }
        fire()
    }

    fun connect(id: String) = async { get(id)?.start() }
    fun disconnect(id: String) = async { get(id)?.stop() }
    fun reconnect(id: String) = async { get(id)?.restart() }
    fun connectAll() = async { all().forEach { it.start() } }
    fun disconnectAll() = async { all().forEach { it.stop() } }

    /** Runs a health check right now and reports the result as a notification. */
    fun checkStatus(id: String) = async {
        val t = get(id) ?: return@async
        val result = t.healthCheck(settings().healthCheckSeconds, force = true)
        val clients = t.clientCount.let { if (it > 0) ", $it open connection(s)" else "" }
        notifications.show(
            t.profile.name,
            "$result$clients\n127.0.0.1:${t.profile.localPort} → ${t.profile.dbHost}:${t.profile.dbPort}",
            if (t.state == TunnelState.ERROR) NoticeLevel.ERROR else NoticeLevel.INFO,
        )
    }

    /** Called after a successful SSO sign-in: resume everything that was waiting for it. */
    fun onSignedIn(ssoKey: String) {
        val profiles = AwsProfiles.load().associateBy { it.name }
        all().filter { it.state == TunnelState.AUTH_REQUIRED && profiles[it.profile.awsProfile]?.ssoKey == ssoKey }
            .forEach { it.retryNow(userInitiated = false) }
    }

    fun signIn(awsProfile: String) {
        loginGate.login(awsProfile, userInitiated = true)
    }

    private fun async(block: () -> Unit) {
        Thread.ofVirtual().start {
            runCatching(block).onFailure { log.log("app", "Unexpected error: $it") }
        }
    }

    /** Synchronously closes all sessions (used on exit). */
    fun shutdown() {
        scheduler.shutdownNow()
        all().forEach { runCatching { it.stop() } }
    }
}
