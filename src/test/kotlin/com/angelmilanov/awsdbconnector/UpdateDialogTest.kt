package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.ui.Theme
import com.angelmilanov.awsdbconnector.ui.UpdateDialog
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.AppVersion
import com.angelmilanov.awsdbconnector.update.Asset
import com.angelmilanov.awsdbconnector.update.InstallKind
import com.angelmilanov.awsdbconnector.update.Release
import com.angelmilanov.awsdbconnector.update.Update
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import com.angelmilanov.awsdbconnector.util.Platform
import com.sun.net.httpserver.HttpServer
import java.awt.event.WindowEvent
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JProgressBar
import javax.swing.JTextArea
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateDialogTest {
    private val dir = TestEnv.tempDir("update-dialog")
    private val store = ConfigStore(dir.resolve("config.json"))
    private val programs = FakePrograms()
    private val desktop = FakeDesktop()
    private val exits = AtomicInteger()
    private val owner = onEdt { JFrame() }

    private val content = ByteArray(400_000) { (it % 251).toByte() }
    private val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
    private val stalled = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { server ->
        server.createContext("/package") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        // Sends the beginning and then nothing more, like a connection that hangs.
        server.createContext("/stalling") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.write(content, 0, 100_000)
            exchange.responseBody.flush()
            stalled.await()
            exchange.close()
        }
        server.start()
    }
    private val page = "https://github.com/angel-milanov/AWS-DB-Connector/releases/tag/v1.4.0"

    private fun service(kind: InstallKind?) = UpdateService(
        store, ActivityLog(), Notifications { _, _, _ -> },
        kind?.let { AppInstall(AppVersion.parse("1.3.2")!!, dir.resolve("app").resolve("AWSDBConnector"), it, "any") }, { null },
    )

    /** @param path what the package is downloaded from; null for a release without a package for this system */
    private fun update(path: String? = "/package", notes: String = "What is new", file: String = "AWSDBConnector-v1.4.0.pkg") = Update(
        Release(AppVersion.parse("1.4.0")!!, "v1.4.0", notes, page, emptyList()),
        path?.let { Asset(file, "http://127.0.0.1:${server.address.port}$it", content.size.toLong(), sha) },
    )

    private fun dialog(kind: InstallKind?, update: Update = update(), updates: UpdateService = service(kind)) =
        onEdt { UpdateDialog(owner, updates, update) { exits.incrementAndGet() } }

    private fun UpdateDialog.texts() = onEdt { all<JLabel>().mapNotNull { it.text } }

    /** The line below the progress bar. */
    private fun UpdateDialog.status(): JLabel = onEdt {
        val bar = first<JProgressBar>()
        bar.parent.components[bar.parent.components.indexOf(bar) + 1] as JLabel
    }

    private fun UpdateDialog.enabled(text: String) = onEdt { button(text).isEnabled }

    @BeforeTest
    fun setup() = onEdt { Theme.apply(ThemeMode.LIGHT) }

    @AfterTest
    fun cleanup() {
        stalled.countDown()
        server.stop(0)
        programs.close()
        desktop.close()
        onEdt { owner.dispose() }
    }

    @Test
    fun `the dialog says what will happen for each kind of installation`() {
        fun offer(kind: InstallKind) = dialog(kind).let { d -> d.texts().first { it.startsWith("You are using version 1.3.2. ") }.substringAfter("1.3.2. ") to onEdt { d.rootPane.defaultButton.text } }

        assertEquals("Windows will ask for permission to install it." to "Install and restart", offer(InstallKind.WINDOWS_INSTALLER))
        assertEquals("You will be asked for your password to install it." to "Install and restart", offer(InstallKind.LINUX_DEB))
        assertEquals("Open connections are closed while it is installed." to "Install and restart", offer(InstallKind.MAC_DMG))
        assertEquals("It is downloaded to your download folder." to "Download", offer(InstallKind.WINDOWS_PORTABLE))
        assertEquals("It is downloaded to your download folder." to "Download", offer(InstallKind.LINUX_PORTABLE))

        val d = dialog(InstallKind.MAC_DMG, update(notes = "<b>Faster</b> reconnects"))
        assertTrue("AWS DB Connector 1.4.0 is available" in d.texts())
        assertEquals("<b>Faster</b> reconnects", onEdt { d.first<JTextArea>().text }, "the notes come from the internet and are shown as plain text")
        assertEquals("No release notes were published for this version.", onEdt { dialog(InstallKind.MAC_DMG, update(notes = " ")).first<JTextArea>().text })
    }

    @Test
    fun `a release without a package for this system leads to the release page`() {
        val d = dialog(InstallKind.MAC_DMG, update(path = null))
        assertTrue(d.texts().any { it.endsWith("This release has no package for your system.") })
        onEdt {
            d.click("Open the release page")
            d.click("Release notes on GitHub")
        }
        assertEquals(listOf(page, page), desktop.opened)
        assertTrue(programs.commands.isEmpty())

        FakeDesktop(broken = true) // no browser: nothing happens, and nothing breaks
        onEdt { d.click("Release notes on GitHub") }
    }

    @Test
    fun `later closes the dialog and skip remembers the version`() {
        val later = dialog(InstallKind.WINDOWS_INSTALLER)
        onEdt { later.click("Later") }
        assertFalse(later.isDisplayable)
        assertEquals("", store.settings.skippedUpdate)

        val closed = dialog(InstallKind.WINDOWS_INSTALLER)
        onEdt { closed.dispatchEvent(WindowEvent(closed, WindowEvent.WINDOW_CLOSING)) }
        assertFalse(closed.isDisplayable, "the close button of the window is the same as Later")

        val updates = service(InstallKind.WINDOWS_INSTALLER)
        val skipped = dialog(InstallKind.WINDOWS_INSTALLER, updates = updates)
        onEdt { skipped.click("Skip this version") }
        assertFalse(skipped.isDisplayable)
        assertEquals("v1.4.0", store.settings.skippedUpdate)
        assertNull(updates.available)
    }

    @Test
    fun `an update is downloaded, installed and the application restarted`() {
        TestEnv.requireIsolated()
        val d = dialog(InstallKind.WINDOWS_INSTALLER)
        onEdt { d.click("Install and restart") }

        await("the restart") { exits.get() == 1 }
        assertEquals("<html>Restarting…</html>", d.status().text)
        assertTrue(programs.commands.single().first().endsWith("powershell.exe"), "the installer was started")
        val installer = Platform.cacheDir.resolve("updates").resolve("AWSDBConnector-v1.4.0.pkg")
        assertTrue(content.contentEquals(Files.readAllBytes(installer)))
        assertFalse(d.enabled("Install and restart") || d.enabled("Skip this version") || d.enabled("Cancel"), "nothing can be clicked while it installs")
        assertTrue(onEdt { d.first<JProgressBar>().isVisible })
        Files.delete(installer)
    }

    @Test
    fun `a portable package is downloaded and the user is told what to do with it`() {
        TestEnv.requireIsolated()
        val d = dialog(InstallKind.LINUX_PORTABLE)
        var message = ""
        val told = DialogAnswer { dialog ->
            message = dialog.first<JOptionPane>().message.toString()
            dialog.click("OK")
        }
        onEdt { d.click("Download") }
        told.await()

        val downloaded = Platform.downloadsDir.resolve("AWSDBConnector-v1.4.0.pkg")
        assertTrue(message.startsWith("Version 1.4.0 was downloaded to\n$downloaded\n\nTo update, exit AWS DB Connector and replace the folder"), message)
        assertTrue(content.contentEquals(Files.readAllBytes(downloaded)))
        assertFalse(d.isDisplayable, "the update dialog has done its part")
        assertEquals(listOf(downloaded.toString()), desktop.opened, "the archive is shown in the file manager")
        assertEquals(0, exits.get())
        Files.delete(downloaded)
    }

    @Test
    fun `a failed update can be tried again`() {
        val d = dialog(InstallKind.WINDOWS_INSTALLER, update(path = "/missing"))
        onEdt { d.click("Install and restart") }
        await("the failure") { onEdt { d.rootPane.defaultButton.text } == "Try again" }
        assertEquals("<html>The download failed with HTTP 404.</html>", d.status().text)
        assertEquals(Theme.stateColor(TunnelState.ERROR), d.status().foreground)
        assertTrue(d.enabled("Try again") && d.enabled("Skip this version") && d.enabled("Later"))
        assertFalse(onEdt { d.first<JProgressBar>().isVisible })

        // A failure of the installer: what it says is shown as text, whatever it contains.
        val failing = dialog(InstallKind.WINDOWS_INSTALLER)
        programs.respond = { throw IOException("msiexec <not found> & nothing else\nInstall it by hand") }
        onEdt { failing.click("Install and restart") }
        await("the failure") { onEdt { failing.rootPane.defaultButton.text } == "Try again" }
        assertEquals("<html>msiexec &lt;not found> &amp; nothing else<br>Install it by hand</html>", failing.status().text)

        programs.respond = { throw IOException() }
        onEdt { failing.click("Try again") }
        await("the second failure") { failing.status().text == "<html>IOException</html>" }

        programs.respond = { FakeProcess() }
        onEdt { failing.click("Try again") }
        await("the restart") { exits.get() == 1 }
    }

    @Test
    fun `a download can be cancelled`() {
        TestEnv.requireIsolated()
        val d = dialog(InstallKind.WINDOWS_INSTALLER, update(path = "/stalling", file = "stalling.pkg"))
        onEdt { d.click("Install and restart") }
        await("the download to be under way") { d.status().text.startsWith("<html>Downloading…") }
        assertTrue(onEdt { d.first<JProgressBar>().isVisible })
        assertFalse(d.enabled("Install and restart"))

        onEdt { d.click("Cancel") } // "Later" is "Cancel" while a download is running
        await("the dialog to be back where it was") { d.enabled("Install and restart") }
        assertEquals("<html>&nbsp;</html>", d.status().text, "cancelling is not an error")
        assertTrue(d.enabled("Later"))
        assertTrue(d.isDisplayable)
        assertEquals(
            emptyList(), Files.list(Platform.cacheDir.resolve("updates")).use { files -> files.filter { "stalling" in it.fileName.toString() }.toList() },
            "nothing is left of the download",
        )
        assertEquals(0, exits.get())

        onEdt { d.click("Later") }
        assertFalse(d.isDisplayable)
    }
}
