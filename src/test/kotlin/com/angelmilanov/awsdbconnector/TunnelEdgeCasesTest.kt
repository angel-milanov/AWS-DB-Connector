package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.tunnel.Tunnel
import com.angelmilanov.awsdbconnector.tunnel.TunnelException
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.util.Notifications
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What happens to a tunnel when things do not go to plan; the everyday behaviour is in [TunnelTest]. */
class TunnelEdgeCasesTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val echo = EchoServer()
    private val backend = FakeBackend(echo.port)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val log = CopyOnWriteArrayList<String>()
    private val tunnels = mutableListOf<Tunnel>()

    private fun tunnel(keepAlive: Boolean = true) = Tunnel(
        ConnectionProfile(name = "test", awsProfile = "keys", dbHost = "db", bastionInstanceId = "i-0123456789abcdef0", localPort = freePort(), keepAlive = keepAlive),
        backend, LoginGate { _, _ -> CompletableFuture.completedFuture(false) }, Notifications { _, _, _ -> }, scheduler,
        logger = { log += it }, onChange = {}, retryDelaysSeconds = listOf(1),
    ).also { tunnels += it }

    private fun connected(keepAlive: Boolean = true) = tunnel(keepAlive).also { t ->
        t.start()
        await("connected") { t.state == TunnelState.CONNECTED }
    }

    private fun roundTrip(port: Int, message: String): String = Socket(loopback, port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().write(message.toByteArray())
        s.shutdownOutput()
        String(s.getInputStream().readAllBytes())
    }

    /** What a client gets that is not served: nothing. Depending on the timing the connection is closed or reset. */
    private fun turnedAway(port: Int): String = runCatching { roundTrip(port, "anybody there?") }.getOrDefault("")

    @AfterTest
    fun cleanup() {
        tunnels.forEach { it.stop() }
        echo.close()
        scheduler.shutdownNow()
    }

    @Test
    fun `connecting what is connected already changes nothing`() {
        val t = connected()
        assertEquals("fake-1", t.sessionId)
        assertEquals("", t.detail)

        t.start()
        t.start()
        assertEquals(1, backend.opens.get())
        assertEquals(TunnelState.CONNECTED, t.state)
        assertEquals("ok", roundTrip(t.profile.localPort, "ok"))

        t.stop()
        assertNull(t.sessionId)
    }

    @Test
    fun `a tunnel that is stopped while its session is being opened closes that session`() {
        val opening = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        backend.failure = { _, _ ->
            opening.countDown()
            proceed.await()
            null
        }
        val t = tunnel()
        t.start()
        opening.await()
        assertEquals(TunnelState.CONNECTING, t.state)

        t.stop()
        proceed.countDown()

        await("the late session to be closed") { backend.sessions.size == 1 && !backend.sessions[0].alive }
        assertEquals(TunnelState.STOPPED, t.state)
        assertFalse(log.any { it.startsWith("Connected") }, log.toString())
    }

    @Test
    fun `stopping works on a thread that has been interrupted`() {
        val t = connected()
        val interruptedAfterwards = CompletableFuture<Boolean>()
        Thread.ofPlatform().start {
            Thread.currentThread().interrupt()
            t.stop()
            interruptedAfterwards.complete(Thread.currentThread().isInterrupted)
        }
        assertTrue(interruptedAfterwards.get(), "the interruption is passed on to the caller")
        assertEquals(TunnelState.STOPPED, t.state)
        assertFalse(backend.sessions.single().alive)
    }

    @Test
    fun `a session AWS cannot be asked about counts as connected`() {
        val t = connected()
        val session = backend.sessions.single()

        session.remote = null
        assertEquals("Connected (remote status could not be verified)", t.healthCheck(30, force = true))
        session.remote = false
        assertEquals("Connected (remote status unknown)", t.healthCheck(30, force = true))
        assertEquals("Connected", t.healthCheck(30), "not asked again before the interval has passed")
        assertEquals("Connected", t.healthCheck(0), "never asked with the check switched off")
        assertEquals(2, session.remoteChecks.get())
        assertEquals(1, backend.opens.get())
    }

    @Test
    fun `a client that connects while no session can be opened is turned away`() {
        val t = connected(keepAlive = false)
        backend.failure = { _, _ -> TunnelException("TargetNotConnected") }
        backend.sessions.single().kill()
        await("idle") { t.state == TunnelState.IDLE }

        assertEquals("", turnedAway(t.profile.localPort), "the connection is closed without an answer")
        await("the failure to show") { t.state == TunnelState.ERROR }
        assertEquals("TargetNotConnected", t.detail)
        await("the client to be forgotten") { t.clientCount == 0 }
    }

    @Test
    fun `a session that does not accept connections is replaced while the client waits`() {
        // The first session leads nowhere: its plugin has stopped listening without ending.
        backend.port = freePort()
        backend.failure = { _, attempt ->
            if (attempt == 2) backend.port = echo.port
            null
        }
        val t = connected()

        assertEquals("hello", roundTrip(t.profile.localPort, "hello"))
        assertEquals(2, backend.opens.get())
        assertFalse(backend.sessions[0].alive)
        assertTrue(log.any { it.startsWith("Session is not accepting connections") }, log.toString())
    }

    @Test
    fun `a client is turned away when the replacement does not accept connections either`() {
        backend.port = freePort()
        val t = connected()
        assertEquals("", turnedAway(t.profile.localPort))
        assertTrue(backend.opens.get() >= 2)
    }

    @Test
    fun `stopping a tunnel cuts the connections that go through it`() {
        val t = connected()
        Socket(loopback, t.profile.localPort).use { client ->
            client.soTimeout = 10_000
            client.getOutputStream().write("ping".toByteArray())
            assertEquals("ping", String(client.getInputStream().readNBytes(4)))
            assertEquals(1, t.clientCount)

            t.stop()

            assertEquals(-1, runCatching { client.getInputStream().read() }.getOrDefault(-1), "the connection is gone")
        }
        await("the client to be forgotten") { t.clientCount == 0 }
    }
}
