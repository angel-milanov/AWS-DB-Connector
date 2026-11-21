package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.SsoLoginManager
import com.angelmilanov.awsdbconnector.model.AppSettings
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SsoLoginTest {
    private val awsCli = Files.writeString(TestEnv.tempDir("sso").resolve("aws"), "").toString()
    private var settings = AppSettings(awsCliPath = awsCli)
    private val log = ActivityLog()
    private val notices = CopyOnWriteArrayList<String>()
    private val signedIn = CopyOnWriteArrayList<String>()
    private val programs = FakePrograms { FakeProcess("Attempting to automatically open the SSO authorization page\n\nSuccessfully logged into Start URL\n") }
    /** What was started with the AWS CLI; anything else that runs meanwhile is none of this test's business. */
    private val logins: List<List<String>> get() = programs.commands.filter { it.first() == awsCli }
    private val manager = SsoLoginManager({ settings }, log, Notifications { title, text, level -> notices += "$level $title: $text" })
        .also { it.addSuccessListener(signedIn::add) }

    private fun login(profile: String, userInitiated: Boolean) = manager.login(profile, userInitiated).get(15, TimeUnit.SECONDS)
    private fun logged(text: String) = log.snapshot().any { text in it }

    @BeforeTest
    fun setup() = FakeAws.reset()

    @AfterTest
    fun cleanup() = programs.close()

    @Test
    fun `signing in runs the AWS CLI for the profile and tells who was waiting`() {
        assertTrue(login("sso", userInitiated = true))

        assertEquals(listOf(listOf(awsCli, "sso", "login", "--profile", "sso")), logins)
        assertEquals(listOf("session:corp"), signedIn)
        assertEquals(listOf("WARNING AWS sign-in required: The SSO session 'corp' has expired. Complete the sign-in in your browser."), notices)
        await("the output of the AWS CLI in the log") { logged("[sso] Successfully logged into Start URL") }
        assertTrue(logged("Running: aws sso login --profile sso") && logged("Signed in to 'corp'"), log.snapshot().toString())
        assertFalse(log.snapshot().any { it.endsWith("[sso] ") }, "blank lines of the AWS CLI are left out")
    }

    @Test
    fun `a role is signed in to through the profile it is based on`() {
        assertTrue(login("role", userInitiated = true))
        assertEquals(listOf(awsCli, "sso", "login", "--profile", "sso"), logins.single())

        assertTrue(login("legacy", userInitiated = true))
        assertEquals(listOf("session:corp", "url:https://legacy.awsapps.com/start"), signedIn)
        assertTrue(notices.last().contains("'https://legacy.awsapps.com/start'"), "without a session name the start URL names the session")
    }

    @Test
    fun `profiles without SSO cannot be signed in to`() {
        assertFalse(login("keys", userInitiated = true))
        assertFalse(login("no-such-profile", userInitiated = true))
        assertTrue(logged("Profile 'keys' does not use AWS SSO"))
        assertTrue(logins.isEmpty() && notices.isEmpty())
    }

    @Test
    fun `a sign-in that fails, is abandoned or cannot be started is reported as not signed in`() {
        programs.respond = { FakeProcess("Error: the request was denied\n", exitCode = 1) }
        assertFalse(login("sso", userInitiated = true))

        val abandoned = FakeProcess(exitCode = null, timesOut = true) // the browser tab was closed
        programs.respond = { abandoned }
        assertFalse(login("sso", userInitiated = true))
        assertTrue(abandoned.destroyed)
        assertTrue(logged("Sign-in timed out after 5 minutes"))

        programs.respond = { throw IOException("Cannot run program") }
        assertFalse(login("sso", userInitiated = true))
        assertTrue(logged("Sign-in failed: Cannot run program"))
        assertEquals(emptyList(), signedIn)
    }

    @Test
    fun `without the AWS CLI the user is told how to sign in by hand`() {
        settings = AppSettings(awsCliPath = "$awsCli-is-not-here")
        assertFalse(login("role", userInitiated = true))
        assertEquals(
            listOf("ERROR AWS sign-in required: The AWS CLI was not found. Run 'aws sso login --profile sso' manually or set its path in Settings."),
            notices,
        )
        assertTrue(logins.isEmpty())
    }

    @Test
    fun `two connections of the same SSO session share one sign-in`() {
        val browser = FakeProcess(exitCode = null)
        programs.respond = { browser }

        val first = manager.login("sso", userInitiated = true)
        val second = manager.login("role", userInitiated = false)
        assertSame(first, second)
        assertFalse(first.isDone)
        browser.exit(0)
        assertTrue(first.get(15, TimeUnit.SECONDS))
        assertEquals(1, logins.size)
        assertEquals(listOf("session:corp"), signedIn)
    }

    @Test
    fun `the browser is not opened on its own when that is switched off`() {
        settings = settings.copy(autoSsoLogin = false)

        assertFalse(login("sso", userInitiated = false))
        assertFalse(login("role", userInitiated = false))
        assertEquals(
            listOf("WARNING AWS sign-in required: The SSO session 'corp' has expired. Use \"Sign in\" in the tray menu."), notices,
            "the hint is shown once, not for every connection",
        )
        assertTrue(logins.isEmpty())

        assertTrue(login("sso", userInitiated = true), "the user can always sign in")
    }

    @Test
    fun `an unattended computer does not keep opening browser tabs`() {
        programs.respond = { FakeProcess(exitCode = 1) }
        assertFalse(login("sso", userInitiated = false))
        assertFalse(login("sso", userInitiated = false))
        assertFalse(login("role", userInitiated = false))
        assertEquals(1, logins.size, "one automatic attempt, then silence for a while")
        assertEquals(1, notices.size)

        // Another SSO session is not affected, and after a sign-in that worked the next expiry opens the browser again.
        programs.respond = { FakeProcess() }
        assertTrue(login("legacy", userInitiated = false))
        assertTrue(login("sso", userInitiated = true))
        assertTrue(login("sso", userInitiated = false))
        assertEquals(4, logins.size)
    }
}
