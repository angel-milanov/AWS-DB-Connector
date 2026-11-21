package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.model.AppSettings
import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.ui.SettingsPanel
import com.angelmilanov.awsdbconnector.ui.Theme
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.AppVersion
import com.angelmilanov.awsdbconnector.update.InstallKind
import com.angelmilanov.awsdbconnector.update.Release
import com.angelmilanov.awsdbconnector.update.ReleaseSource
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import java.awt.Container
import java.awt.event.FocusEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.JTextField
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPanelTest {
    private val dir = TestEnv.tempDir("settings")
    private val store = ConfigStore(dir.resolve("config.json"))
    private val tool = Files.writeString(dir.resolve("tool"), "").toString()
    private val programs = FakePrograms { command -> if (command.last() == "--version") FakeProcess("1.2.707.0\nsecond line\n") else FakeProcess(exitCode = 1) }
    private var latest: () -> Release? = { null }
    private val checks = AtomicInteger()
    private val offered = AtomicInteger()

    private fun panel(installed: Boolean = true): SettingsPanel = onEdt {
        val install = if (installed) AppInstall(AppVersion.parse("1.3.2")!!, Paths.get("app"), InstallKind.WINDOWS_INSTALLER, "windows-x64") else null
        val source = ReleaseSource {
            checks.incrementAndGet()
            latest()
        }
        SettingsPanel(store, UpdateService(store, ActivityLog(), Notifications { _, _, _ -> }, install, source)) { offered.incrementAndGet() }
    }

    private fun SettingsPanel.check(text: String) = first<JCheckBox> { it.text.startsWith(text) }
    private fun SettingsPanel.path(label: String) = (labelled(label) as Container).first<JTextField>()
    private fun SettingsPanel.texts() = onEdt { all<JLabel>().map { it.text } }
    private fun SettingsPanel.says(text: String) = texts().any { text in it }

    @BeforeTest
    fun setup() = onEdt { Theme.apply(ThemeMode.LIGHT) }

    @AfterTest
    fun cleanup() {
        programs.close()
        onEdt { Theme.apply(ThemeMode.LIGHT) }
    }

    @Test
    fun `the settings are shown as they were saved`() {
        store.updateSettings {
            AppSettings(
                theme = ThemeMode.DARK, notifications = false, autoSsoLogin = false, showWindowOnStart = false, healthCheckSeconds = 45,
                awsCliPath = tool, pluginPath = "$tool-is-not-here", checkForUpdates = false,
            )
        }
        val panel = panel()
        onEdt {
            assertEquals(ThemeMode.DARK, (panel.labelled("Theme") as JComboBox<*>).selectedItem)
            assertFalse(panel.check("Open this window").isSelected)
            assertFalse(panel.check("Show desktop notifications").isSelected)
            assertFalse(panel.check("Open the AWS SSO sign-in").isSelected)
            assertFalse(panel.check("Check for updates automatically").isSelected)
            assertEquals(45, (panel.labelled("Session check") as Container).first<JSpinner>().value)
            assertEquals(tool, panel.path("AWS CLI").text)
            assertEquals("$tool-is-not-here", panel.path("Session Manager plugin").text)
            assertEquals(store.location.toString(), (panel.labelled("Connections") as JTextField).text)
            assertEquals(Paths.get(System.getProperty("user.home"), ".aws", "config").toString(), (panel.labelled("AWS profiles") as JTextField).text)
        }
        await("the tools to be looked for") { panel.says("Found: $tool") }
        assertTrue(panel.says("Not found – required to open tunnels. Install the AWS Session Manager plugin."))
    }

    @Test
    fun `every change is saved at once`() {
        val panel = panel()
        onEdt {
            panel.check("Open this window").doClick(0)
            panel.check("Show desktop notifications").doClick(0)
            panel.check("Open the AWS SSO sign-in").doClick(0)
            (panel.labelled("Session check") as Container).first<JSpinner>().value = 0
            (panel.labelled("Theme") as JComboBox<*>).selectedItem = ThemeMode.DARK
        }
        assertEquals(
            AppSettings(theme = ThemeMode.DARK, notifications = false, autoSsoLogin = false, showWindowOnStart = false, healthCheckSeconds = 0),
            store.settings,
        )
        assertEquals(store.settings, ConfigStore(store.location).settings, "and written to the file")
        assertTrue(Theme.isDark, "the theme is applied right away")
    }

    @Test
    fun `the paths of the tools are saved when the field is left, and checked`() {
        val panel = panel()
        // The panel looks for the tools when it is opened; let that finish, so that its answer does not arrive after the ones below.
        await("the first look for the tools") { panel.texts().any { it.startsWith("Found: ") || it.startsWith("Not found") } }
        onEdt {
            panel.path("Session Manager plugin").apply {
                text = "  $tool "
                postActionEvent() // Enter
            }
        }
        assertEquals(tool, store.settings.pluginPath)
        await("the version of the plugin") { panel.says("Found: $tool  (version 1.2.707.0)") }

        onEdt {
            panel.path("AWS CLI").apply {
                text = "$tool-is-not-here"
                // The focus moves on. Swing drops a focus event for a field that never had the focus, so the listeners are told directly.
                focusListeners.forEach { it.focusLost(FocusEvent(this, FocusEvent.FOCUS_LOST)) }
            }
        }
        assertEquals("$tool-is-not-here", store.settings.awsCliPath)
        await("the missing AWS CLI to be reported") { panel.says("Not found – needed for the SSO sign-in") }
    }

    @Test
    fun `a tool can be picked in a file dialog`() {
        val panel = panel()
        val row = onEdt { panel.labelled("AWS CLI") as Container }

        answering({ dialog ->
            dialog.first<JFileChooser>().apply {
                selectedFile = Paths.get(tool).toFile()
                approveSelection()
            }
        }) { row.click("Browse…") }
        assertEquals(tool, onEdt { panel.path("AWS CLI").text })
        assertEquals(tool, store.settings.awsCliPath)

        answering({ dialog -> dialog.first<JFileChooser>().cancelSelection() }) { row.click("Browse…") }
        assertEquals(tool, store.settings.awsCliPath, "cancelling changes nothing")
    }

    @Test
    fun `a development build has nothing to update`() {
        val panel = panel(installed = false)
        onEdt {
            assertTrue("Development build" in panel.texts())
            assertTrue("Updates are only available in an installed version." in panel.texts())
            assertFalse(panel.button("Check for updates").isEnabled)
            assertFalse(panel.check("Check for updates automatically").isEnabled)
        }
    }

    @Test
    fun `updates can be looked for at any time`() {
        val panel = panel()
        assertTrue("1.3.2" in panel.texts())

        onEdt { panel.click("Check for updates") }
        await("the answer") { panel.says("You have the latest version.") }
        assertEquals(0, offered.get())

        latest = { throw IOException("no network") }
        onEdt { panel.click("Check for updates") }
        await("the failure") { panel.says("The check failed: no network") }

        // While GitHub is being asked the button waits.
        val answer = CountDownLatch(1)
        latest = {
            answer.await()
            Release(AppVersion.parse("1.4.0")!!, "v1.4.0", "", "https://github.com/", emptyList())
        }
        onEdt { panel.click("Check for updates") }
        await("the check to start") { panel.says("Checking…") }
        assertFalse(onEdt { panel.button("Check for updates").isEnabled })
        answer.countDown()
        await("the update") { panel.says("Version 1.4.0 is available.") && offered.get() == 1 }
        assertTrue(onEdt { panel.button("Check for updates").isEnabled })
    }

    @Test
    fun `the automatic check can be switched off, and looks at once when it is switched on again`() {
        val panel = panel()
        onEdt { panel.check("Check for updates automatically").doClick(0) }
        assertFalse(store.settings.checkForUpdates)
        assertEquals(0, checks.get())

        onEdt { panel.check("Check for updates automatically").doClick(0) }
        assertTrue(store.settings.checkForUpdates)
        await("a check") { checks.get() == 1 }
        await("the answer") { panel.says("You have the latest version.") }
        assertEquals(0, offered.get(), "an automatic check does not open a dialog")
    }
}
