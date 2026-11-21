package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.AppSettings
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.tunnel.TunnelException
import com.angelmilanov.awsdbconnector.tunnel.TunnelManager
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TunnelManagerTest {
    private val echo = EchoServer()
    private val backend = FakeBackend(echo.port)
    private val log = ActivityLog()
    private val notices = CopyOnWriteArrayList<String>()
    private val logins = CopyOnWriteArrayList<String>()
    private var notifications = Notifications { title, text, level -> notices += "$level $title: $text" }
    private val manager = TunnelManager(
        { AppSettings(healthCheckSeconds = 1) }, backend,
        LoginGate { profile, userInitiated ->
            logins += "$profile by ${if (userInitiated) "the user" else "the application"}"
            CompletableFuture.completedFuture(false)
        },
        { title, text, level -> notifications.show(title, text, level) }, log,
    )

    private fun connection(name: String, autoConnect: Boolean = true, awsProfile: String = "keys") = ConnectionProfile(
        name = name, awsProfile = awsProfile, dbHost = "$name.example".lowercase(), bastionInstanceId = "i-0123456789abcdef0",
        localPort = freePort(), autoConnect = autoConnect,
    )

    private fun state(connection: ConnectionProfile) = manager.get(connection.id)?.state

    @BeforeTest
    fun setup() = FakeAws.reset()

    @AfterTest
    fun cleanup() {
        manager.shutdown()
        echo.close()
    }

    @Test
    fun `connections follow the configuration`() {
        val changes = AtomicInteger()
        manager.addListener { changes.incrementAndGet() }
        val orders = connection("Orders")
        val billing = connection("Billing", autoConnect = false)

        manager.sync(listOf(orders, billing), startNew = true)
        await("Orders to connect") { state(orders) == TunnelState.CONNECTED }
        assertEquals(TunnelState.STOPPED, state(billing), "not marked to connect automatically")
        assertEquals(listOf("Orders", "Billing"), manager.all().map { it.profile.name })
        assertTrue(changes.get() > 0)

        val before = manager.get(orders.id)
        manager.sync(listOf(orders, billing), startNew = true)
        assertSame(before, manager.get(orders.id), "what did not change is left alone")

        // A changed connection is restarted if it was running, and only then.
        val moved = orders.copy(dbHost = "orders-new.example")
        val renamed = billing.copy(name = "Billing EU")
        manager.sync(listOf(moved, renamed), startNew = true)
        await("Orders to reconnect with the new host") { backend.opens.get() == 2 && state(moved) == TunnelState.CONNECTED }
        assertEquals("orders-new.example", manager.get(orders.id)!!.profile.dbHost)
        assertFalse(backend.sessions[0].alive, "the old session is closed")
        assertEquals(TunnelState.STOPPED, state(renamed))
        assertEquals("Billing EU", manager.get(billing.id)!!.profile.name)

        // A removed connection is stopped; a new one is not started while the application is not asked to.
        val reports = connection("Reports")
        manager.sync(listOf(renamed, reports), startNew = false)
        await("Orders to be closed") { !backend.sessions[1].alive }
        assertNull(manager.get(orders.id))
        assertEquals(TunnelState.STOPPED, state(reports))
        assertEquals(2, backend.opens.get())
    }

    @Test
    fun `connections are switched on and off one by one or all together`() {
        val orders = connection("Orders", autoConnect = false)
        val billing = connection("Billing", autoConnect = false)
        manager.sync(listOf(orders, billing), startNew = true)

        manager.connect(orders.id)
        await("Orders to connect") { state(orders) == TunnelState.CONNECTED }
        manager.reconnect(orders.id)
        await("Orders to reconnect") { backend.opens.get() == 2 && state(orders) == TunnelState.CONNECTED }
        manager.disconnect(orders.id)
        await("Orders to disconnect") { state(orders) == TunnelState.STOPPED }

        manager.connectAll()
        await("both to connect") { state(orders) == TunnelState.CONNECTED && state(billing) == TunnelState.CONNECTED }
        manager.disconnectAll()
        await("both to disconnect") { state(orders) == TunnelState.STOPPED && state(billing) == TunnelState.STOPPED }
        assertTrue(backend.sessions.none { it.alive })

        manager.connect("no-such-connection")
        manager.checkStatus("no-such-connection")
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `the status of a connection is reported on request`() {
        val orders = connection("Orders")
        manager.sync(listOf(orders), startNew = true)
        await("Orders to connect") { state(orders) == TunnelState.CONNECTED }

        manager.checkStatus(orders.id)
        await("the report") { notices.size == 1 }
        assertEquals("INFO Orders: Connected – session fake-1 is active\n127.0.0.1:${orders.localPort} → orders.example:5432", notices[0])

        Socket(InetAddress.getLoopbackAddress(), orders.localPort).use {
            await("the client to be counted") { manager.get(orders.id)!!.clientCount == 1 }
            manager.checkStatus(orders.id)
            await("the report") { notices.size == 2 }
            assertTrue("is active, 1 open connection(s)\n" in notices[1], notices[1])
        }

        backend.failure = { _, _ -> TunnelException("TargetNotConnected: the bastion host is stopped") }
        val broken = connection("Broken").copy(keepAlive = false) // no retries that would change the state in between
        manager.sync(listOf(orders, broken), startNew = true)
        await("Broken to fail") { state(broken) == TunnelState.ERROR && notices.any { "Connection failed" in it } }
        notices.clear()
        manager.checkStatus(broken.id)
        await("the report") { notices.size == 1 }
        assertTrue(notices[0].startsWith("ERROR Broken: Error – TargetNotConnected: the bastion host is stopped\n127.0.0.1:"), notices[0])
    }

    @Test
    fun `after a sign-in the connections that waited for it are resumed`() {
        var signedIn = false
        backend.failure = { profile, _ -> if (profile.awsProfile != "keys" && !signedIn) AuthRequiredException("Token is expired") else null }
        val direct = connection("Direct", awsProfile = "sso")
        val viaRole = connection("Via role", awsProfile = "role")
        val other = connection("Other session", awsProfile = "legacy")
        val keys = connection("Keys", awsProfile = "keys")
        manager.sync(listOf(direct, viaRole, other, keys), startNew = true)
        await("the three to wait for a sign-in") { listOf(direct, viaRole, other).all { state(it) == TunnelState.AUTH_REQUIRED } }
        await("Keys to connect") { state(keys) == TunnelState.CONNECTED }
        assertTrue("sso by the user" in logins, "connecting by hand may open the browser: $logins")

        signedIn = true
        manager.onSignedIn("session:corp")
        await("the connections of that session") { state(direct) == TunnelState.CONNECTED && state(viaRole) == TunnelState.CONNECTED }
        assertEquals(TunnelState.AUTH_REQUIRED, state(other), "another SSO session is still signed out")

        manager.signIn("legacy")
        assertEquals("legacy by the user", logins.last())
    }

    @Test
    fun `sessions are checked in the background`() {
        val orders = connection("Orders")
        manager.sync(listOf(orders), startNew = true)
        await("Orders to connect") { state(orders) == TunnelState.CONNECTED }
        val session = backend.sessions.single()
        await("a check of the session on the AWS side") { session.remoteChecks.get() > 0 }

        // A plugin that died is noticed without asking AWS, and the session is replaced.
        session.alive = false
        await("a new session") { backend.opens.get() == 2 && state(orders) == TunnelState.CONNECTED }
    }

    @Test
    fun `a failure nobody expected ends up in the activity log`() {
        val orders = connection("Orders")
        manager.sync(listOf(orders), startNew = true)
        await("Orders to connect") { state(orders) == TunnelState.CONNECTED }

        notifications = Notifications { _, _, _ -> throw IllegalStateException("the notification service is gone") }
        manager.checkStatus(orders.id)
        await("the log entry") { log.snapshot().any { "[app] Unexpected error: java.lang.IllegalStateException: the notification service is gone" in it } }
    }

    @Test
    fun `shutting down closes every session`() {
        val orders = connection("Orders")
        val billing = connection("Billing")
        manager.sync(listOf(orders, billing), startNew = true)
        await("both to connect") { state(orders) == TunnelState.CONNECTED && state(billing) == TunnelState.CONNECTED }

        manager.shutdown()
        assertTrue(backend.sessions.none { it.alive })
        assertEquals(listOf(TunnelState.STOPPED, TunnelState.STOPPED), manager.all().map { it.state })
    }
}
