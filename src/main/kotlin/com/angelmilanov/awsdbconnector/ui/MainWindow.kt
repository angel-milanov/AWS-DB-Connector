package com.angelmilanov.awsdbconnector.ui

import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.tunnel.Tunnel
import com.angelmilanov.awsdbconnector.tunnel.TunnelManager
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.ActivityLog
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class MainWindow(
    private val store: ConfigStore,
    private val manager: TunnelManager,
    private val loginGate: LoginGate,
    private val activity: ActivityLog,
    private val updates: UpdateService,
    private val hideOnClose: Boolean,
    private val onExit: () -> Unit,
) : JFrame("AWS DB Connector") {

    private val model = TunnelTableModel()
    private val table = JTable(model)
    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val detail = JLabel(" ")
    private val tabs = JTabbedPane()
    private val refreshQueued = AtomicBoolean(false)

    private val newButton = JButton("New connection")
    private val editButton = JButton("Edit")
    private val duplicateButton = JButton("Duplicate")
    private val deleteButton = JButton("Delete")
    private val connectButton = JButton("Connect")
    private val disconnectButton = JButton("Disconnect")
    private val reconnectButton = JButton("Reconnect")
    private val updateButton = JButton()
    private var updateDialog: UpdateDialog? = null

    init {
        iconImages = AppIcon.windowIcons()
        defaultCloseOperation = if (hideOnClose) HIDE_ON_CLOSE else DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (!hideOnClose) onExit()
            }

            override fun windowActivated(e: WindowEvent) = Theme.refreshFromSystem()
        })

        tabs.addTab("Connections", buildConnections())
        tabs.addTab("Activity", buildActivity())
        tabs.addTab("Settings", JScrollPane(SettingsPanel(store, updates, ::showUpdate)).apply { border = null })
        tabs.putClientProperty("JTabbedPane.tabInsets", java.awt.Insets(4, 18, 4, 18))
        // An available update is announced next to the tabs: visible on every tab, in nobody's way.
        updateButton.putClientProperty("FlatLaf.style", "arc: 999; margin: 3,12,3,12; borderColor: #3b82f6; foreground: #3b82f6")
        updateButton.addActionListener { showUpdate() }
        tabs.putClientProperty(
            "JTabbedPane.trailingComponent",
            JPanel(MigLayout("insets 0 0 0 14, filly", "push[]", "[center]")).apply { add(updateButton) },
        )
        contentPane = tabs

        updates.addListener { SwingUtilities.invokeLater(::refreshUpdate) }
        refreshUpdate()
        manager.addListener(::queueRefresh)
        refresh()
        minimumSize = Dimension(760, 420)
        size = Dimension(1020, 600)
        setLocationRelativeTo(null)
    }

    // ------------------------------------------------------------------ connections tab

    private fun buildConnections(): JPanel {
        val panel = JPanel(BorderLayout())

        val toolbar = JPanel(MigLayout("insets 16 20 12 20, fillx", "[][]push[][][]16[][][]"))
        newButton.putClientProperty(
            "FlatLaf.style",
            "background: #3b82f6; foreground: #ffffff; borderWidth: 0; focusWidth: 0;" +
                "hoverBackground: #2f6fe0; pressedBackground: #1d4ed8; arc: 10; margin: 6,14,6,14",
        )
        val title = JLabel("Connections").apply { putClientProperty("FlatLaf.styleClass", "h2") }
        toolbar.add(title)
        toolbar.add(JLabel(""))
        toolbar.add(connectButton)
        toolbar.add(disconnectButton)
        toolbar.add(reconnectButton)
        toolbar.add(editButton)
        toolbar.add(duplicateButton)
        toolbar.add(deleteButton, "gapright 16")
        toolbar.add(newButton)
        panel.add(toolbar, BorderLayout.NORTH)

        table.rowHeight = 38
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        table.autoCreateRowSorter = false
        table.tableHeader.reorderingAllowed = false
        table.tableHeader.defaultRenderer = HeaderRenderer(table.tableHeader.defaultRenderer)
        table.setDefaultRenderer(Any::class.java, PaddedRenderer())
        table.columnModel.getColumn(1).cellRenderer = StatusRenderer()
        val widths = intArrayOf(190, 210, 150, 80, 300, 170)
        widths.forEachIndexed { i, w -> table.columnModel.getColumn(i).preferredWidth = w }
        table.selectionModel.addListSelectionListener { updateSelection() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e) && table.rowAtPoint(e.point) >= 0) editSelected()
            }

            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
        })

        val scroll = JScrollPane(table).apply {
            border = BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Component.borderColor"))
        }
        val empty = JPanel(MigLayout("fill, wrap 1", "[center]", "push[]8[]14[]push")).apply {
            add(JLabel(javax.swing.ImageIcon(AppIcon.render(64, null))))
            add(JLabel("No connections yet").apply { putClientProperty("FlatLaf.styleClass", "h2") })
            add(JLabel("Create a connection to reach an RDS database through a bastion host.").apply {
                foreground = UIManager.getColor("Label.disabledForeground")
            })
            add(JButton("New connection").apply { addActionListener { newProfile() } }, "gaptop 6")
        }
        center.add(scroll, "table")
        center.add(empty, "empty")
        panel.add(center, BorderLayout.CENTER)

        detail.border = BorderFactory.createEmptyBorder(10, 20, 10, 20)
        detail.foreground = UIManager.getColor("Label.disabledForeground")
        panel.add(detail, BorderLayout.SOUTH)

        newButton.addActionListener { newProfile() }
        editButton.addActionListener { editSelected() }
        duplicateButton.addActionListener { duplicateSelected() }
        deleteButton.addActionListener { deleteSelected() }
        connectButton.addActionListener { selected()?.let { manager.connect(it.profile.id) } }
        disconnectButton.addActionListener { selected()?.let { manager.disconnect(it.profile.id) } }
        reconnectButton.addActionListener { selected()?.let { manager.reconnect(it.profile.id) } }
        return panel
    }

    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        table.setRowSelectionInterval(row, row)
        val t = selected() ?: return
        val id = t.profile.id
        JPopupMenu().apply {
            add(item("Connect", !t.isActive) { manager.connect(id) })
            add(item("Disconnect", t.isActive) { manager.disconnect(id) })
            add(item("Reconnect", t.isActive) { manager.reconnect(id) })
            add(item("Check status", true) { manager.checkStatus(id) })
            if (t.state == TunnelState.AUTH_REQUIRED) add(item("Sign in to AWS…", true) { manager.signIn(t.profile.awsProfile) })
            addSeparator()
            add(item("Edit…", true) { editSelected() })
            add(item("Duplicate", true) { duplicateSelected() })
            add(item("Delete", true) { deleteSelected() })
        }.show(table, e.x, e.y)
    }

    private fun item(text: String, enabled: Boolean, action: () -> Unit) = JMenuItem(text).apply {
        isEnabled = enabled
        addActionListener { action() }
    }

    private fun selected(): Tunnel? = table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(it) }

    private fun updateSelection() {
        val t = selected()
        val any = t != null
        editButton.isEnabled = any
        duplicateButton.isEnabled = any
        deleteButton.isEnabled = any
        connectButton.isEnabled = any && !t!!.isActive
        disconnectButton.isEnabled = any && t!!.isActive
        reconnectButton.isEnabled = any && t!!.isActive
        detail.text = when {
            t == null -> if (model.rows.isEmpty()) " " else "Select a connection to see details. Connections stay available from the tray icon when this window is closed."
            else -> {
                val p = t.profile
                val parts = mutableListOf("127.0.0.1:${p.localPort}  →  ${p.dbHost}:${p.dbPort}", t.state.label)
                if (t.detail.isNotBlank()) parts += t.detail
                t.sessionId?.let { parts += "session $it" }
                if (t.clientCount > 0) parts += "${t.clientCount} open connection(s)"
                parts.joinToString("   ·   ")
            }
        }
        detail.toolTipText = t?.detail?.takeIf { it.isNotBlank() }
    }

    private fun queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) SwingUtilities.invokeLater {
            refreshQueued.set(false)
            refresh()
        }
    }

    private fun refresh() {
        val selectedId = selected()?.profile?.id
        val next = manager.all()
        val sameRows = next.size == model.rows.size && next.indices.all { next[it] === model.rows[it] }
        model.rows = next
        if (sameRows) {
            if (next.isNotEmpty()) model.fireTableRowsUpdated(0, next.size - 1)
        } else {
            model.fireTableDataChanged()
            val index = next.indexOfFirst { it.profile.id == selectedId }
            if (index >= 0) table.setRowSelectionInterval(index, index)
        }
        cards.show(center, if (next.isEmpty()) "empty" else "table")
        updateSelection()
    }

    // ------------------------------------------------------------------ profile actions

    fun newProfile() = edit(null)

    fun editProfile(id: String) {
        store.config.profiles.firstOrNull { it.id == id }?.let(::edit)
    }

    private fun editSelected() {
        selected()?.let { edit(it.profile) }
    }

    private fun edit(profile: ConnectionProfile?) {
        showWindow()
        tabs.selectedIndex = 0
        val others = store.config.profiles.filter { it.id != profile?.id }
        val dialog = ProfileDialog(this, profile, others, loginGate)
        dialog.isVisible = true
        dialog.result?.let { store.saveProfile(it) }
    }

    private fun duplicateSelected() {
        val p = selected()?.profile ?: return
        val used = store.config.profiles.map { it.localPort }.toSet()
        var port = p.localPort + 1
        while (port in used) port++
        store.saveProfile(p.copy(id = UUID.randomUUID().toString(), name = p.name + " (copy)", localPort = port, autoConnect = false))
    }

    private fun deleteSelected() {
        val p = selected()?.profile ?: return
        val answer = JOptionPane.showConfirmDialog(
            this, "Delete the connection \"${p.name}\"?", "Delete connection",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
        )
        if (answer == JOptionPane.OK_OPTION) store.deleteProfile(p.id)
    }

    fun showWindow() {
        if (!isVisible) isVisible = true
        if (extendedState and ICONIFIED != 0) extendedState = extendedState and ICONIFIED.inv()
        toFront()
        requestFocus()
    }

    fun showSettings() {
        showWindow()
        tabs.selectedIndex = 2
    }

    /** Offers the available update, if there is one. */
    fun showUpdate() {
        val update = updates.available ?: return
        showWindow()
        // The tray menu stays usable while the (modal) dialog is open: do not open a second one.
        updateDialog?.takeIf { it.isDisplayable }?.let { return it.toFront() }
        UpdateDialog(this, updates, update, onExit).also { updateDialog = it }.isVisible = true
    }

    private fun refreshUpdate() {
        val update = updates.available
        updateButton.isVisible = update != null
        if (update != null) updateButton.text = "Update to ${update.release.version}"
    }

    // ------------------------------------------------------------------ activity tab

    private fun buildActivity(): JPanel {
        val area = JTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").size)
            border = BorderFactory.createEmptyBorder(10, 16, 10, 16)
            text = activity.snapshot().joinToString("\n", postfix = "\n")
        }
        activity.addListener { line ->
            SwingUtilities.invokeLater {
                area.append(line + "\n")
                val excess = area.document.length - 400_000
                if (excess > 0) area.replaceRange("", 0, excess)
                area.caretPosition = area.document.length
            }
        }
        val clear = JButton("Clear").apply {
            addActionListener {
                activity.clear()
                area.text = ""
            }
        }
        return JPanel(BorderLayout()).apply {
            add(JPanel(MigLayout("insets 16 20 12 20, fillx", "[]push[]")).apply {
                add(JLabel("Activity").apply { putClientProperty("FlatLaf.styleClass", "h2") })
                add(clear)
            }, BorderLayout.NORTH)
            add(JScrollPane(area).apply {
                border = BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor"))
            }, BorderLayout.CENTER)
        }
    }

    // ------------------------------------------------------------------ table

    private class TunnelTableModel : AbstractTableModel() {
        var rows: List<Tunnel> = emptyList()
        private val columns = arrayOf("Name", "Status", "AWS profile", "Local port", "Database", "Bastion host")

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val t = rows[row]
            val p = t.profile
            return when (column) {
                0 -> p.name
                1 -> t
                2 -> p.awsProfile
                3 -> p.localPort.toString()
                4 -> "${p.dbHost}:${p.dbPort}"
                else -> p.bastionInstanceId
            }
        }
    }

    /** Left aligned column titles with the same indent as the cells. */
    private class HeaderRenderer(private val delegate: javax.swing.table.TableCellRenderer) : javax.swing.table.TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).also {
            (it as? JLabel)?.apply {
                horizontalAlignment = SwingConstants.LEFT
                border = BorderFactory.createCompoundBorder(border, BorderFactory.createEmptyBorder(0, 9, 0, 0))
            }
        }
    }

    private open class PaddedRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            border = BorderFactory.createEmptyBorder(0, 14, 0, 8)
            icon = null
            toolTipText = value?.toString()
            if (column == 0) font = font.deriveFont(Font.BOLD)
            return this
        }
    }

    private class StatusRenderer : PaddedRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val t = value as? Tunnel ?: return this
            icon = StatusDot.of(t.state)
            iconTextGap = 8
            horizontalAlignment = SwingConstants.LEFT
            text = t.state.label + if (t.clientCount > 0) "  (${t.clientCount})" else ""
            toolTipText = t.detail.ifBlank { t.state.label }
            return this
        }
    }
}
