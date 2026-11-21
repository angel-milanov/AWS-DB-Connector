package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.model.AppSettings
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.tunnel.SsmBackend
import com.angelmilanov.awsdbconnector.tunnel.TunnelException
import com.angelmilanov.awsdbconnector.util.Platform
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsmBackendTest {
    private val plugin = Files.writeString(TestEnv.tempDir("ssm").resolve("session-manager-plugin"), "").toString()
    private var settings = AppSettings(pluginPath = plugin)
    private val log = CopyOnWriteArrayList<String>()
    private val profile = ConnectionProfile(
        name = "Orders", awsProfile = "keys", dbHost = "orders.c1x2y3.eu-central-1.rds.amazonaws.com", dbPort = 5432,
        bastionInstanceId = "i-0123456789abcdef0", localPort = 15432,
    )

    /** What the plugin prints once the tunnel is open; it then runs until it is ended. */
    private fun running() = FakeProcess(
        "\nStarting session with SessionId: user-0f3a9c1d2b4e5f607\nPort 50123 opened for sessionId user-0f3a9c1d2b4e5f607.\nWaiting for connections...\n",
        exitCode = null,
    )

    private var process = running()

    /** Only the plugin is answered with [process]: whatever else is started meanwhile must not get hold of it. */
    private val programs = FakePrograms { command -> if (command.first() == plugin) process else FakeProcess(exitCode = 1) }
    private val backend = SsmBackend({ settings })

    @BeforeTest
    fun setup() {
        FakeAws.reset()
        FakeAws.replies["StartSession"] = FakeAws.session("user-0f3a9c1d2b4e5f607")
        FakeAws.replies["TerminateSession"] = FakeAws.json("""{"SessionId":"user-0f3a9c1d2b4e5f607"}""")
    }

    @AfterTest
    fun cleanup() = programs.close()

    @Test
    fun `a session is started with the SDK and handed to the Session Manager plugin`() {
        val session = backend.open(profile, log::add)

        assertEquals("user-0f3a9c1d2b4e5f607", session.id)
        assertTrue(session.isAlive())
        val request = FakeAws.requests.single { it.first == "StartSession" }.second
        assertTrue(""""Target":"i-0123456789abcdef0"""" in request, request)
        assertTrue("AWS-StartPortForwardingSessionToRemoteHost" in request)
        assertTrue(""""host":["orders.c1x2y3.eu-central-1.rds.amazonaws.com"]""" in request && """"portNumber":["5432"]""" in request)
        assertTrue(""""localPortNumber":["${session.localPort}"]""" in request, "the plugin listens on a port of its own, not on the configured one")
        assertTrue(session.localPort != profile.localPort)

        val started = programs.started.single { it.command().first() == plugin }
        assertEquals(
            listOf(
                plugin, "AWS_SSM_START_SESSION_RESPONSE", "eu-central-1", "StartSession", "keys",
                Platform.jsonArg("""{"Target":"i-0123456789abcdef0"}"""), "https://ssm.eu-central-1.amazonaws.com",
            ),
            started.command(),
        )
        val response = started.environment().getValue("AWS_SSM_START_SESSION_RESPONSE")
        assertTrue(""""TokenValue":"token-of-user-0f3a9c1d2b4e5f607"""" in response && """"StreamUrl":"wss://""" in response, response)
        assertFalse(started.command().any { "token-of" in it }, "the token must not show up in the list of processes")
        assertTrue(started.redirectErrorStream())

        assertEquals("Session user-0f3a9c1d2b4e5f607 started via i-0123456789abcdef0 (eu-central-1)", log.first())
        await("the output of the plugin in the log") { "Waiting for connections..." in log }
        assertFalse(log.any { it.isBlank() || "token-of" in it })
        session.close()
    }

    @Test
    fun `the endpoint follows the region of the connection`() {
        backend.open(profile.copy(region = "cn-north-1"), log::add).close()
        assertEquals(listOf("cn-north-1", "https://ssm.cn-north-1.amazonaws.com.cn"), programs.commands.single { it.first() == plugin }.let { listOf(it[2], it[6]) })
    }

    @Test
    fun `closing a session ends the plugin and the session on the AWS side`() {
        val session = backend.open(profile, log::add)
        val ended = AtomicBoolean()
        session.onExit { ended.set(true) }

        session.close()

        assertTrue(process.destroyed)
        await("the session to be terminated") { FakeAws.called("TerminateSession") == 1 }
        assertTrue("user-0f3a9c1d2b4e5f607" in FakeAws.requests.single { it.first == "TerminateSession" }.second)
        await("the exit callback") { ended.get() }
        assertFalse(session.isAlive())

        session.close()
        assertEquals(1, FakeAws.called("TerminateSession"), "a session whose plugin has ended is gone already")
    }

    @Test
    fun `the session is looked up on the AWS side`() {
        val session = backend.open(profile, log::add)

        FakeAws.replies["DescribeSessions"] = FakeAws.sessions("user-0f3a9c1d2b4e5f607")
        assertEquals(true, session.checkRemote())
        val request = FakeAws.requests.last { it.first == "DescribeSessions" }.second
        assertTrue(""""State":"Active"""" in request && "user-0f3a9c1d2b4e5f607" in request, request)

        FakeAws.replies["DescribeSessions"] = FakeAws.sessions()
        assertEquals(false, session.checkRemote())

        FakeAws.replies["DescribeSessions"] = FakeAws.jsonError("AccessDeniedException", "Not allowed to describe sessions")
        assertNull(session.checkRemote(), "without the permission nothing can be said")
        session.close()
    }

    @Test
    fun `without the plugin no session is started`() {
        settings = AppSettings(pluginPath = "$plugin-is-not-here")
        val failure = assertFailsWith<TunnelException> { backend.open(profile, log::add) }
        assertTrue("session-manager-plugin not found" in failure.message!!)
        assertEquals(0, FakeAws.called("StartSession"))
    }

    @Test
    fun `a refused session is reported with the reason`() {
        FakeAws.replies["StartSession"] = FakeAws.jsonError("TargetNotConnected", "i-0123456789abcdef0 is not connected.")
        val failure = assertFailsWith<TunnelException> { backend.open(profile, log::add) }
        assertEquals("TargetNotConnected: i-0123456789abcdef0 is not connected.", failure.message)
        assertTrue(programs.commands.none { it.first() == plugin })

        assertTrue("was not found" in assertFailsWith<TunnelException> { backend.open(profile.copy(awsProfile = "no-such-profile"), log::add) }.message!!)
    }

    @Test
    fun `expired credentials ask for a sign-in`() {
        FakeAws.replies["StartSession"] = FakeAws.jsonError("ExpiredTokenException", "The security token included in the request is expired")
        val failure = assertFailsWith<AuthRequiredException> { backend.open(profile, log::add) }
        assertEquals("ExpiredTokenException: The security token included in the request is expired", failure.message)
    }

    @Test
    fun `a plugin that cannot be started leaves no session behind`() {
        programs.respond = { throw IOException("Cannot run program") }
        val failure = assertFailsWith<TunnelException> { backend.open(profile, log::add) }
        assertEquals("Could not start session-manager-plugin: Cannot run program", failure.message)
        await("the session to be terminated") { FakeAws.called("TerminateSession") == 1 }
    }

    @Test
    fun `a plugin that ends at once is reported with its exit code`() {
        process = FakeProcess("Cannot perform start session: unsupported version\n", exitCode = 1)
        val failure = assertFailsWith<TunnelException> { backend.open(profile, log::add) }
        assertTrue("exited with code 1" in failure.message!!, failure.message)
        await("the output of the plugin in the log") { "Cannot perform start session: unsupported version" in log }
    }

    @Test
    fun `a plugin that never gets ready is ended`() {
        process = FakeProcess("Starting session with SessionId: user-0f3a9c1d2b4e5f607\n", exitCode = null)
        val impatient = SsmBackend({ settings }, startupTimeoutSeconds = 1)
        val failure = assertFailsWith<TunnelException> { impatient.open(profile, log::add) }
        assertEquals("Timed out waiting for the Session Manager tunnel to become ready", failure.message)
        assertTrue(process.destroyed)
        await("the session to be terminated") { FakeAws.called("TerminateSession") == 1 }
    }
}
