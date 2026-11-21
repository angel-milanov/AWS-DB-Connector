package com.angelmilanov.awsdbconnector.tunnel

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.util.NoticeLevel
import com.angelmilanov.awsdbconnector.util.Notifications
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class TunnelState(val label: String) {
    STOPPED("Disconnected"),
    CONNECTING("Connecting…"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting…"),
    IDLE("Listening (connects on demand)"),
    AUTH_REQUIRED("Sign-in required"),
    ERROR("Error"),
}

/**
 * One connection profile at runtime.
 *
 * While active, the tunnel owns the configured local port: it always listens there and proxies
 * every accepted TCP connection to the Session Manager session. If the session is gone when a
 * client connects (or drops while "keep alive" is on) it is re-created transparently.
 */
class Tunnel(
    val profile: ConnectionProfile,
    private val backend: TunnelBackend,
    private val loginGate: LoginGate,
    private val notifications: Notifications,
    private val scheduler: ScheduledExecutorService,
    private val logger: (String) -> Unit,
    private val onChange: (Tunnel) -> Unit,
    private val retryDelaysSeconds: List<Long> = listOf(2, 5, 10, 30, 60),
) {
    private val lock = ReentrantLock()
    private var active = false
    private var generation = 0
    private var server: ServerSocket? = null
    private var acceptor: Thread? = null
    private var session: BackendSession? = null
    private var pending: CompletableFuture<BackendSession>? = null
    private var retryTask: ScheduledFuture<*>? = null
    private var failures = 0
    private var dropped = false
    private var remoteSeen = false
    private var lastRemoteCheck = 0L
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    var state: TunnelState = TunnelState.STOPPED
        private set

    @Volatile
    var detail: String = ""
        private set

    val clientCount: Int get() = clients.size
    val isActive: Boolean get() = lock.withLock { active }
    val sessionId: String? get() = lock.withLock { session?.id }

    private fun log(message: String) = logger(message)

    /** Sets the state unless the tunnel was stopped / restarted since [gen] was observed. */
    private fun setState(gen: Int?, newState: TunnelState, newDetail: String = "") {
        lock.withLock {
            if (gen != null && gen != generation) return
            state = newState
            detail = newDetail
        }
        onChange(this)
    }

    // ------------------------------------------------------------------ user actions

    /** Binds the local port and establishes the session. One caller at a time, so that the port is only bound once. */
    @Synchronized
    fun start() {
        if (isActive) {
            ensureSession(userInitiated = true)
            return
        }
        val srv = ServerSocket()
        try {
            srv.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), profile.localPort), 50)
        } catch (e: IOException) {
            runCatching { srv.close() }
            log("Cannot listen on port ${profile.localPort}: ${e.message}")
            setState(null, TunnelState.ERROR, "Local port ${profile.localPort} is not available (${e.message})")
            notifications.show(profile.name, "Local port ${profile.localPort} is already in use.", NoticeLevel.ERROR)
            return
        }
        val accept = Thread.ofVirtual().name("accept-${profile.localPort}").unstarted { acceptLoop(srv) }
        lock.withLock {
            active = true
            generation++
            failures = 0
            dropped = false
            server = srv
            acceptor = accept
        }
        log("Listening on 127.0.0.1:${profile.localPort}")
        accept.start()
        ensureSession(userInitiated = true)
    }

    /** Closes the session and releases the local port. */
    fun stop() {
        var oldSession: BackendSession? = null
        var oldServer: ServerSocket? = null
        var oldAcceptor: Thread? = null
        var oldPending: CompletableFuture<BackendSession>? = null
        lock.withLock {
            if (!active && state == TunnelState.STOPPED) return
            active = false
            generation++
            retryTask?.cancel(false)
            retryTask = null
            oldSession = session
            session = null
            oldServer = server
            server = null
            oldAcceptor = acceptor
            acceptor = null
            oldPending = pending
            pending = null
        }
        runCatching { oldServer?.close() }
        // close() alone does not free the port: while a thread is blocked in accept() the socket is only really closed
        // once that thread has returned from it, so restart() could otherwise find its own port still in use.
        try {
            oldAcceptor?.join(ACCEPT_EXIT_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        clients.forEach { runCatching { it.close() } }
        oldPending?.completeExceptionally(CancellationException("Disconnected"))
        oldSession?.close()
        log("Disconnected")
        setState(null, TunnelState.STOPPED)
    }

    fun restart() {
        stop()
        start()
    }

    /** Retries right away, e.g. after the user signed in again. */
    fun retryNow(userInitiated: Boolean) {
        if (isActive) ensureSession(userInitiated)
    }

    // ------------------------------------------------------------------ session management

    private fun ensureSession(userInitiated: Boolean): CompletableFuture<BackendSession> {
        val future = CompletableFuture<BackendSession>()
        val gen = lock.withLock {
            if (!active) return CompletableFuture.failedFuture(CancellationException("Disconnected"))
            session?.takeIf { it.isAlive() }?.let { return CompletableFuture.completedFuture(it) }
            pending?.let { return it }
            pending = future
            retryTask?.cancel(false)
            retryTask = null
            generation
        }
        Thread.ofVirtual().name("connect-${profile.localPort}").start { establish(future, gen, userInitiated) }
        return future
    }

    private fun establish(future: CompletableFuture<BackendSession>, gen: Int, userInitiated: Boolean) {
        val (isReconnect, priorFailures) = lock.withLock { dropped to failures }
        try {
            setState(gen, if (isReconnect) TunnelState.RECONNECTING else TunnelState.CONNECTING)
            if (isReconnect && priorFailures == 0 && !userInitiated) {
                notifications.show(
                    profile.name,
                    "The Session Manager connection was lost. Attempting to reconnect…",
                    NoticeLevel.WARNING,
                )
            }
            val opened = try {
                backend.open(profile, ::log)
            } catch (e: AuthRequiredException) {
                log("Credentials expired: ${e.message}")
                setState(gen, TunnelState.AUTH_REQUIRED, "Waiting for AWS sign-in…")
                val signedIn = runCatching {
                    loginGate.login(profile.awsProfile, userInitiated).get(LOGIN_WAIT_MINUTES, TimeUnit.MINUTES)
                }.getOrDefault(false)
                if (!signedIn) throw e
                if (lock.withLock { generation != gen }) throw CancellationException("Disconnected")
                setState(gen, if (isReconnect) TunnelState.RECONNECTING else TunnelState.CONNECTING)
                backend.open(profile, ::log)
            }
            val stale = lock.withLock {
                if (generation != gen || !active) true else {
                    session = opened
                    pending = null
                    failures = 0
                    dropped = false
                    remoteSeen = false
                    lastRemoteCheck = System.currentTimeMillis()
                    false
                }
            }
            if (stale) {
                opened.close()
                future.completeExceptionally(CancellationException("Disconnected"))
                return
            }
            log(if (isReconnect) "Reconnected (session ${opened.id})" else "Connected (session ${opened.id})")
            setState(gen, TunnelState.CONNECTED)
            opened.onExit { sessionLost(opened, "Session Manager session ended") }
            future.complete(opened)
        } catch (e: Throwable) {
            val (stale, attempt) = lock.withLock {
                if (pending === future) pending = null
                val s = generation != gen || !active
                if (!s) failures++
                s to failures
            }
            future.completeExceptionally(e)
            if (stale) return
            if (e is AuthRequiredException) {
                // No automatic retries here: the user has to sign in (tray menu / next client connection).
                setState(gen, TunnelState.AUTH_REQUIRED, "AWS session expired – sign in to continue")
                return
            }
            val message = e.message ?: e.javaClass.simpleName
            log("Connection failed: $message")
            setState(gen, TunnelState.ERROR, message)
            if (attempt == 1) notifications.show(profile.name, "Connection failed: $message", NoticeLevel.ERROR)
            if (profile.keepAlive) scheduleRetry(gen, attempt)
        }
    }

    private fun scheduleRetry(gen: Int, attempt: Int) {
        val delay = retryDelaysSeconds[(attempt - 1).coerceIn(0, retryDelaysSeconds.lastIndex)]
        lock.withLock {
            if (generation != gen || !active) return
            retryTask?.cancel(false)
            retryTask = scheduler.schedule({
                val due = lock.withLock { generation == gen && active && session == null }
                if (due) ensureSession(userInitiated = false)
            }, delay, TimeUnit.SECONDS)
        }
        log("Retrying in ${delay}s")
    }

    private fun sessionLost(lost: BackendSession, reason: String) {
        val (stillActive, gen) = lock.withLock {
            if (session !== lost) return
            session = null
            dropped = true
            active to generation
        }
        if (!stillActive) return
        log(reason)
        lost.close()
        if (profile.keepAlive) {
            ensureSession(userInitiated = false)
        } else {
            setState(gen, TunnelState.IDLE, "Session ended – reconnects when a client connects")
        }
    }

    /**
     * Periodic monitoring. The plugin process is watched continuously; additionally the session
     * is looked up on the AWS side every [remoteIntervalSeconds] (0 = never).
     * @return a short human readable status.
     */
    fun healthCheck(remoteIntervalSeconds: Int, force: Boolean = false): String {
        val current = lock.withLock { session }
        if (current == null) return state.label + detail.takeIf { it.isNotBlank() }?.let { " – $it" }.orEmpty()
        if (!current.isAlive()) {
            sessionLost(current, "Session Manager plugin is no longer running")
            return "Session lost"
        }
        val now = System.currentTimeMillis()
        val due = lock.withLock {
            val d = force || (remoteIntervalSeconds > 0 && now - lastRemoteCheck >= remoteIntervalSeconds * 1000L)
            if (d) lastRemoteCheck = now
            d
        }
        if (!due) return "Connected"
        return when (current.checkRemote()) {
            true -> {
                lock.withLock { remoteSeen = true }
                "Connected – session ${current.id} is active"
            }
            false -> {
                // Only trust "not found" if the lookup has worked for this session before.
                if (lock.withLock { remoteSeen }) {
                    sessionLost(current, "Session ${current.id} is no longer active on the AWS side")
                    "Session lost"
                } else "Connected (remote status unknown)"
            }
            null -> "Connected (remote status could not be verified)"
        }
    }

    // ------------------------------------------------------------------ local listener / proxy

    private fun acceptLoop(srv: ServerSocket) {
        while (true) {
            val client = try {
                srv.accept()
            } catch (_: IOException) {
                break
            }
            Thread.ofVirtual().name("client-${profile.localPort}").start { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        clients += client
        onChange(this)
        try {
            client.tcpNoDelay = true
            repeat(2) {
                val current = try {
                    ensureSession(userInitiated = false).get(CLIENT_WAIT_MINUTES, TimeUnit.MINUTES)
                } catch (_: Exception) {
                    return
                }
                val upstream = Socket()
                try {
                    upstream.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), current.localPort), 5000)
                } catch (e: IOException) {
                    runCatching { upstream.close() }
                    sessionLost(current, "Session is not accepting connections (${e.message})")
                    return@repeat
                }
                upstream.tcpNoDelay = true
                pump(client, upstream)
                return
            }
        } finally {
            runCatching { client.close() }
            clients -= client
            onChange(this)
        }
    }

    private fun pump(a: Socket, b: Socket) {
        fun copy(from: Socket, to: Socket) {
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    output.flush()
                }
                runCatching { to.shutdownOutput() }
            } catch (_: IOException) {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }
        val forward = Thread.ofVirtual().start { copy(a, b) }
        copy(b, a)
        forward.join()
        runCatching { a.close() }
        runCatching { b.close() }
    }

    companion object {
        private const val LOGIN_WAIT_MINUTES = 6L
        private const val CLIENT_WAIT_MINUTES = 7L
        private const val ACCEPT_EXIT_TIMEOUT_MS = 5_000L
    }
}
