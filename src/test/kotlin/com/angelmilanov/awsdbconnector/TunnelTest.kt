package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.tunnel.BackendSession
import com.angelmilanov.awsdbconnector.tunnel.Tunnel
import com.angelmilanov.awsdbconnector.tunnel.TunnelBackend
import com.angelmilanov.awsdbconnector.tunnel.TunnelException
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.util.Notifications
import com.angelmilanov.awsdbconnector.util.Platform
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TunnelTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val notices = CopyOnWriteArrayList<String>()
    private val notifications = Notifications { title, text, _ -> notices += "$title: $text" }
    private val tunnels = mutableListOf<Tunnel>()

    /** Stands in for the database behind the session-manager-plugin: echoes everything back. */
    private val echo = ServerSocket(0, 50, loopback).also { server ->
        Thread.ofVirtual().start {
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread.ofVirtual().start { s.use { it.getInputStream().transferTo(it.getOutputStream()) } }
            }
        }
    }

    private class FakeSession(override val localPort: Int, n: Int) : BackendSession {
        override val id = "fake-$n"
        @Volatile var alive = true
        @Volatile var remote: Boolean? = true
        private val callbacks = CopyOnWriteArrayList<() -> Unit>()
        override fun isAlive() = alive
        override fun onExit(callback: () -> Unit) { callbacks += callback }
        override fun checkRemote() = remote
        override fun close() { kill() }
        fun kill() {
            if (alive) { alive = false; callbacks.forEach { it() } }
        }
    }

    private inner class FakeBackend(var failure: (Int) -> Exception? = { null }) : TunnelBackend {
        val opens = AtomicInteger()
        val sessions = CopyOnWriteArrayList<FakeSession>()
        override fun open(profile: ConnectionProfile, log: (String) -> Unit): BackendSession {
            val n = opens.incrementAndGet()
            failure(n)?.let { throw it }
            return FakeSession(echo.localPort, n).also { sessions += it }
        }
    }

    private fun freePort() = ServerSocket(0, 1, loopback).use { it.localPort }

    private fun tunnel(backend: TunnelBackend, keepAlive: Boolean, login: LoginGate = LoginGate { _, _ -> CompletableFuture.completedFuture(false) }) =
        Tunnel(
            ConnectionProfile(name = "test", awsProfile = "p", dbHost = "db", bastionInstanceId = "i-012345678", localPort = freePort(), keepAlive = keepAlive),
            backend, login, notifications, scheduler, logger = { println("[tunnel] $it") }, onChange = {},
            retryDelaysSeconds = listOf(1),
        ).also { tunnels += it }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    private fun roundTrip(port: Int, message: String): String = Socket(loopback, port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().write(message.toByteArray())
        s.shutdownOutput()
        String(s.getInputStream().readAllBytes())
    }

    @AfterTest
    fun cleanup() {
        tunnels.forEach { it.stop() }
        echo.close()
        scheduler.shutdownNow()
    }

    @Test
    fun `proxies traffic through the session`() {
        val backend = FakeBackend()
        val t = tunnel(backend, keepAlive = true)
        t.start()
        await("connected") { t.state == TunnelState.CONNECTED }
        assertEquals("hello", roundTrip(t.profile.localPort, "hello"))
        assertEquals("again", roundTrip(t.profile.localPort, "again"))
        assertEquals(1, backend.opens.get())
    }

    @Test
    fun `reconnects on demand when a client connects after the session dropped`() {
        val backend = FakeBackend()
        val t = tunnel(backend, keepAlive = false)
        t.start()
        await("connected") { t.state == TunnelState.CONNECTED }
        backend.sessions.single().kill()
        await("idle") { t.state == TunnelState.IDLE }
        assertEquals(1, backend.opens.get(), "must not reconnect before a client connects")

        assertEquals("ping", roundTrip(t.profile.localPort, "ping"))
        assertEquals(2, backend.opens.get())
        assertEquals(TunnelState.CONNECTED, t.state)
        assertTrue(notices.any { "reconnect" in it.lowercase() }, "a reconnect notification is expected: $notices")
    }

    @Test
    fun `keep alive re-creates a dropped session immediately and notifies`() {
        val backend = FakeBackend()
        val t = tunnel(backend, keepAlive = true)
        t.start()
        await("connected") { t.state == TunnelState.CONNECTED }
        backend.sessions.single().kill()
        await("second session") { backend.opens.get() == 2 && t.state == TunnelState.CONNECTED }
        assertTrue(notices.any { "reconnect" in it.lowercase() })
        assertEquals("ok", roundTrip(t.profile.localPort, "ok"))
    }

    @Test
    fun `retries with backoff after a failure while keep alive is on`() {
        val backend = FakeBackend { n -> if (n <= 2) TunnelException("boom") else null }
        val t = tunnel(backend, keepAlive = true)
        t.start()
        await("error state") { t.state == TunnelState.ERROR }
        await("eventually connected") { t.state == TunnelState.CONNECTED }
        assertEquals(3, backend.opens.get())
        assertEquals(1, notices.count { "boom" in it }, "only the first failure is announced")
    }

    @Test
    fun `expired credentials trigger a sign-in and the connection resumes`() {
        val backend = FakeBackend { n -> if (n == 1) AuthRequiredException("Token is expired") else null }
        val logins = AtomicInteger()
        val t = tunnel(backend, keepAlive = true) { _, _ ->
            logins.incrementAndGet()
            CompletableFuture.completedFuture(true)
        }
        t.start()
        await("connected") { t.state == TunnelState.CONNECTED }
        assertEquals(1, logins.get())
        assertEquals(2, backend.opens.get())
    }

    @Test
    fun `stays in sign-in required without retry loop when the login fails`() {
        val backend = FakeBackend { AuthRequiredException("Token is expired") }
        val t = tunnel(backend, keepAlive = true)
        t.start()
        await("auth required") { t.state == TunnelState.AUTH_REQUIRED && backend.opens.get() == 1 }
        Thread.sleep(1500)
        assertEquals(1, backend.opens.get(), "no automatic retries while signed out")
        t.retryNow(userInitiated = false)
        await("retried") { backend.opens.get() == 2 }
    }

    @Test
    fun `health check detects a session that vanished on the AWS side`() {
        val backend = FakeBackend()
        val t = tunnel(backend, keepAlive = true)
        t.start()
        await("connected") { t.state == TunnelState.CONNECTED }
        val first = backend.sessions.single()
        first.remote = false
        t.healthCheck(30, force = true)
        assertEquals(1, backend.opens.get(), "'not found' is not trusted before the lookup worked once")
        first.remote = true
        t.healthCheck(30, force = true)
        first.remote = false
        t.healthCheck(30, force = true)
        await("reconnected") { backend.opens.get() == 2 && t.state == TunnelState.CONNECTED }
        assertFalse(first.alive)
    }

    @Test
    fun `stop releases the local port and reports a busy port`() {
        val backend = FakeBackend()
        val t = tunnel(backend, keepAlive = true)
        ServerSocket(t.profile.localPort, 1, loopback).use {
            t.start()
            assertEquals(TunnelState.ERROR, t.state)
            assertFalse(t.isActive)
        }
        t.start()
        await("connected") { t.state == TunnelState.CONNECTED }
        t.stop()
        assertEquals(TunnelState.STOPPED, t.state)
        ServerSocket(t.profile.localPort, 1, loopback).close() // port is free again
    }

    @Test
    fun `json arguments survive the trip to a child process`() {
        val json = """{"Target":"i-0123456789abcdef0"}"""
        val java = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", if (Platform.isWindows) "java.exe" else "java")
        val process = ProcessBuilder(
            java.toString(), "-cp", System.getProperty("java.class.path"), "com.angelmilanov.awsdbconnector.ArgEcho",
            "AWS_SSM_START_SESSION_RESPONSE", "eu-central-1", Platform.jsonArg(json), "https://ssm.eu-central-1.amazonaws.com",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim().lines()
        assertEquals(listOf("AWS_SSM_START_SESSION_RESPONSE", "eu-central-1", json, "https://ssm.eu-central-1.amazonaws.com"), output)
    }
}

object ArgEcho {
    @JvmStatic
    fun main(args: Array<String>) = args.forEach(::println)
}
