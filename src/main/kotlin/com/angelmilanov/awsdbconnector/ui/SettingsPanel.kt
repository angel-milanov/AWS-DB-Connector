package com.angelmilanov.awsdbconnector.ui

import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.update.UpdateState
import com.angelmilanov.awsdbconnector.util.Platform
import net.miginfocom.swing.MigLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager

/** Application settings. Every change is applied and saved immediately. */
class SettingsPanel(
    private val store: ConfigStore,
    private val updates: UpdateService,
    private val showUpdate: () -> Unit,
) : JPanel() {
    private val theme = JComboBox(ThemeMode.entries.toTypedArray())
    private val showWindow = JCheckBox("Open this window when the application starts (otherwise start in the tray)")
    private val notifications = JCheckBox("Show desktop notifications (reconnects, errors, sign-in)")
    private val autoSso = JCheckBox("Open the AWS SSO sign-in automatically when a session has expired")
    private val healthCheck = JSpinner(SpinnerNumberModel(30, 0, 3600, 5))
    private val awsCli = JTextField()
    private val plugin = JTextField()
    private val awsCliStatus = hint("")
    private val pluginStatus = hint("")
    private val autoUpdate = JCheckBox("Check for updates automatically (once a day, on GitHub)")
    private val checkNow = JButton("Check for updates")
    private val updateStatus = hint("")

    init {
        layout = MigLayout("insets 24 28 24 28, fillx, wrap 2, hidemode 3", "[right]14[grow,fill]", "[]10[]")
        val s = store.settings

        add(section("Appearance"), "span, growx")
        add(JLabel("Theme"))
        add(theme, "growx 0, width 200!")

        add(section("Behaviour"), "span, growx, gaptop 14")
        add(JLabel(""))
        add(showWindow)
        add(JLabel(""))
        add(notifications)
        add(JLabel(""))
        add(autoSso)
        add(JLabel("Session check"))
        add(JPanel(MigLayout("insets 0", "[][]")).apply {
            add(healthCheck, "width 90!")
            add(hint("seconds between checks that the session is still active on AWS (0 = off)"))
        })

        add(section("Tools"), "span, growx, gaptop 14")
        add(JLabel("AWS CLI"))
        add(pathRow(awsCli))
        add(JLabel(""))
        add(awsCliStatus)
        add(JLabel("Session Manager plugin"))
        add(pathRow(plugin))
        add(JLabel(""))
        add(pluginStatus)

        add(section("Updates"), "span, growx, gaptop 14")
        add(JLabel("Version"))
        add(JPanel(MigLayout("insets 0", "[]14[]10[]")).apply {
            add(JLabel(updates.install?.version?.toString() ?: "Development build"))
            add(checkNow)
            add(updateStatus)
        })
        add(JLabel(""))
        add(autoUpdate)

        add(section("Storage"), "span, growx, gaptop 14")
        add(JLabel("Connections"))
        add(readOnly(store.location.toString()))
        add(JLabel("AWS profiles"))
        add(readOnly(Paths.get(System.getProperty("user.home"), ".aws", "config").toString()))
        add(JLabel(""))
        add(hint("Only profile names, host names, instance ids and ports are stored. No credentials or database passwords."))

        theme.selectedItem = s.theme
        showWindow.isSelected = s.showWindowOnStart
        notifications.isSelected = s.notifications
        autoSso.isSelected = s.autoSsoLogin
        autoUpdate.isSelected = s.checkForUpdates
        healthCheck.value = s.healthCheckSeconds
        awsCli.text = s.awsCliPath
        plugin.text = s.pluginPath
        awsCli.putClientProperty("JTextField.placeholderText", "detect automatically")
        plugin.putClientProperty("JTextField.placeholderText", "detect automatically")

        theme.addActionListener {
            val mode = theme.selectedItem as ThemeMode
            store.updateSettings { it.copy(theme = mode) }
            Theme.apply(mode)
        }
        showWindow.addActionListener { store.updateSettings { it.copy(showWindowOnStart = showWindow.isSelected) } }
        notifications.addActionListener { store.updateSettings { it.copy(notifications = notifications.isSelected) } }
        autoSso.addActionListener { store.updateSettings { it.copy(autoSsoLogin = autoSso.isSelected) } }
        healthCheck.addChangeListener { store.updateSettings { it.copy(healthCheckSeconds = healthCheck.value as Int) } }
        onCommit(awsCli) { store.updateSettings { it.copy(awsCliPath = awsCli.text.trim()) }; refreshToolStatus() }
        onCommit(plugin) { store.updateSettings { it.copy(pluginPath = plugin.text.trim()) }; refreshToolStatus() }
        refreshToolStatus()

        autoUpdate.addActionListener {
            store.updateSettings { it.copy(checkForUpdates = autoUpdate.isSelected) }
            if (autoUpdate.isSelected) Thread.ofVirtual().start { updates.check(manual = false) }
        }
        checkNow.addActionListener {
            Thread.ofVirtual().start {
                if (updates.check(manual = true) is UpdateState.Available) javax.swing.SwingUtilities.invokeLater(showUpdate)
            }
        }
        updates.addListener { javax.swing.SwingUtilities.invokeLater(::refreshUpdateStatus) }
        refreshUpdateStatus()
    }

    private fun refreshUpdateStatus() {
        val installed = updates.install != null
        val state = updates.state
        autoUpdate.isEnabled = installed
        checkNow.isEnabled = installed && state != UpdateState.Checking
        updateStatus.text = when {
            !installed -> "Updates are only available in an installed version."
            state is UpdateState.Checking -> "Checking…"
            state is UpdateState.UpToDate -> "You have the latest version."
            state is UpdateState.Available -> "Version ${state.update.release.version} is available."
            state is UpdateState.Failed -> "The check failed: ${state.message}"
            else -> ""
        }
    }

    private fun section(text: String) = JPanel(MigLayout("insets 0, fillx", "[][grow,fill]")).apply {
        add(JLabel(text).apply { putClientProperty("FlatLaf.styleClass", "h3") })
        add(JSeparator(), "growx, gapleft 10")
    }

    private fun hint(text: String) = JLabel(text).apply {
        putClientProperty("FlatLaf.styleClass", "small")
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    private fun readOnly(text: String) = JTextField(text).apply {
        isEditable = false
        border = null
        background = null
        isOpaque = false
    }

    private fun pathRow(field: JTextField) = JPanel(MigLayout("insets 0, fillx", "[grow,fill][]")).apply {
        add(field)
        add(JButton("Browse…").apply {
            addActionListener {
                val chooser = JFileChooser(field.text.takeIf { it.isNotBlank() })
                if (chooser.showOpenDialog(this@SettingsPanel) == JFileChooser.APPROVE_OPTION) {
                    field.text = chooser.selectedFile.absolutePath
                    field.postActionEvent()
                }
            }
        })
    }

    private fun onCommit(field: JTextField, action: () -> Unit) {
        field.addActionListener { action() }
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = action()
        })
    }

    private fun refreshToolStatus() {
        val s = store.settings
        Thread.ofVirtual().start {
            val cli = Platform.findAwsCli(s.awsCliPath)
            val plug = Platform.findSessionManagerPlugin(s.pluginPath)
            val plugVersion = plug?.let { Platform.runQuick(it, "--version")?.trim()?.lineSequence()?.firstOrNull() }
            javax.swing.SwingUtilities.invokeLater {
                awsCliStatus.text = cli?.let { "Found: $it" } ?: "Not found – needed for the SSO sign-in"
                pluginStatus.text = plug?.let { "Found: $it" + (plugVersion?.let { v -> "  (version $v)" } ?: "") }
                    ?: "Not found – required to open tunnels. Install the AWS Session Manager plugin."
            }
        }
    }
}
