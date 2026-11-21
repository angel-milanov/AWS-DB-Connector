package com.angelmilanov.awsdbconnector.ui

import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.update.InstallKind
import com.angelmilanov.awsdbconnector.update.InstallResult
import com.angelmilanov.awsdbconnector.update.Installer
import com.angelmilanov.awsdbconnector.update.Update
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.Platform
import net.miginfocom.swing.MigLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Offers an update: what is new, and the choice to install it now, later or never. */
class UpdateDialog(
    owner: Window,
    private val updates: UpdateService,
    private val update: Update,
    private val onExit: () -> Unit,
) : JDialog(owner, "Software update", ModalityType.APPLICATION_MODAL) {

    private val kind = updates.install?.kind
    private val installs = update.asset != null && kind != null && !kind.isPortable
    private val action = JButton(
        when {
            update.asset == null -> "Open the release page"
            installs -> "Install and restart"
            else -> "Download"
        },
    )
    private val later = JButton("Later")
    private val skip = JButton("Skip this version")
    private val status = JLabel(" ")
    private val progress = JProgressBar(0, 1000).apply { isVisible = false }

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var cancelled = false

    init {
        val release = update.release
        val content = JPanel(MigLayout("insets 20 24 16 24, fillx, wrap 2, hidemode 3", "[]16[grow,fill]", "[][]14[grow,fill]10[][]16[]"))

        content.add(JLabel(ImageIcon(AppIcon.logo(48))), "spany 2, top")
        content.add(JLabel("AWS DB Connector ${release.version} is available").apply { putClientProperty("FlatLaf.styleClass", "h2") })
        content.add(JLabel("You are using version ${updates.install?.version}. " + when {
            update.asset == null -> "This release has no package for your system."
            kind == InstallKind.WINDOWS_INSTALLER -> "Windows will ask for permission to install it."
            kind == InstallKind.LINUX_DEB -> "You will be asked for your password to install it."
            installs -> "Open connections are closed while it is installed."
            else -> "It is downloaded to your download folder."
        }).apply { foreground = UIManager.getColor("Label.disabledForeground") })

        // Plain text on purpose: the notes come from the internet and are not to be interpreted as HTML.
        val notes = JTextArea(release.notes.ifBlank { "No release notes were published for this version." }, 11, 58).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
            caretPosition = 0
        }
        content.add(JScrollPane(notes), "span, grow")
        content.add(progress, "span, growx, height 6!")
        content.add(status, "span, growx, wmin 0")

        action.putClientProperty("JButton.buttonType", "default")
        val buttons = JPanel(MigLayout("insets 0, fillx", "[]push[][][]"))
        buttons.add(JButton("Release notes on GitHub").apply { addActionListener { browse(release.pageUrl) } })
        buttons.add(skip)
        buttons.add(later)
        buttons.add(action)
        content.add(buttons, "span, growx")

        action.addActionListener { if (update.asset == null) browse(release.pageUrl) else start() }
        later.addActionListener { close() }
        skip.addActionListener {
            updates.skip(update)
            dispose()
        }
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = close()
        })
        rootPane.defaultButton = action
        contentPane = content
        pack()
        setLocationRelativeTo(owner)
    }

    /** "Later" is "Cancel" while a download is running. */
    private fun close() {
        val running = worker
        if (running == null) dispose() else {
            cancelled = true
            running.interrupt()
        }
    }

    private fun browse(url: String) {
        runCatching { Platform.desktop.browse(url) }
    }

    private fun start() {
        val install = updates.install ?: return
        cancelled = false
        action.isEnabled = false
        skip.isEnabled = false
        later.text = "Cancel"
        progress.isVisible = true
        progress.isIndeterminate = true
        setStatus("Connecting…", error = false)

        worker = Thread.ofVirtual().name("update-download").start {
            try {
                var shown = -1
                val file = updates.download(update) { done, total ->
                    val permille = if (total > 0) (done * 1000 / total).toInt() else 0
                    if (permille != shown) {
                        shown = permille
                        SwingUtilities.invokeLater {
                            progress.isIndeterminate = false
                            progress.value = permille
                            setStatus("Downloading… ${done / MB} of ${total / MB} MB", error = false)
                        }
                    }
                }
                SwingUtilities.invokeLater {
                    later.isEnabled = false // the installation itself cannot be interrupted
                    progress.isIndeterminate = true
                    setStatus(if (installs) "Installing…" else "Download complete", error = false)
                }
                val result = Installer.install(install, update, file)
                SwingUtilities.invokeLater { finished(result) }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { reset(if (cancelled) null else e.message ?: e.javaClass.simpleName) }
            } finally {
                worker = null
            }
        }
    }

    private fun finished(result: InstallResult) {
        when (result) {
            is InstallResult.Restart -> {
                setStatus("Restarting…", error = false)
                onExit()
            }
            is InstallResult.Manual -> {
                dispose()
                JOptionPane.showMessageDialog(owner, result.message, "Software update", JOptionPane.INFORMATION_MESSAGE)
            }
        }
    }

    private fun reset(error: String?) {
        action.isEnabled = true
        action.text = if (error != null) "Try again" else action.text
        skip.isEnabled = true
        later.isEnabled = true
        later.text = "Later"
        progress.isVisible = false
        if (error != null) setStatus(error, error = true) else setStatus(" ", error = false)
    }

    private fun setStatus(text: String, error: Boolean) {
        // Escaped HTML: long messages wrap, line breaks (a command to run by hand) are kept, and nothing in it is interpreted.
        status.text = "<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>").ifBlank { "&nbsp;" } + "</html>"
        status.foreground = if (error) Theme.stateColor(TunnelState.ERROR) else UIManager.getColor("Label.disabledForeground")
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}
