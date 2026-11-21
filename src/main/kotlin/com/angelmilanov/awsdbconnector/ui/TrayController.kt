package com.angelmilanov.awsdbconnector.ui

import com.angelmilanov.awsdbconnector.aws.AwsProfiles
import com.angelmilanov.awsdbconnector.tunnel.Tunnel
import com.angelmilanov.awsdbconnector.tunnel.TunnelManager
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.Platform
import com.angelmilanov.awsdbconnector.util.TrayHandle
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Menu
import java.awt.MenuItem
import java.awt.MouseInfo
import java.awt.Point
import java.awt.PopupMenu
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JDialog
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * System tray icon with a menu listing every connection.
 * On Windows a themed Swing popup is used (dark mode, status icons, HiDPI);
 * on other platforms the native AWT popup menu, which is what their trays support reliably.
 */
class TrayController(
    private val manager: TunnelManager,
    private val window: MainWindow,
    private val updates: UpdateService,
    private val onExit: () -> Unit,
    /** Null on a desktop without a system tray: there is nothing to control then. */
    private val tray: TrayHandle?,
    private val useSwingPopup: Boolean = Platform.isWindows,
    /** Where the mouse pointer is: the Swing popup opens there. */
    private val pointer: () -> Point? = { MouseInfo.getPointerInfo()?.location },
) {
    private sealed interface Node
    private data class Item(val text: String, val enabled: Boolean = true, val icon: Icon? = null, val action: (() -> Unit)? = null) : Node
    private data class Sub(val text: String, val plainText: String, val icon: Icon?, val children: List<Node>) : Node
    private data object Separator : Node

    private val refreshQueued = AtomicBoolean(false)
    private var lastBadge: Color? = Color(0, 0, 0, 0)
    private var openPopup: JPopupMenu? = null

    init {
        tray?.let { icon ->
            icon.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) window.showWindow()
                }

                override fun mousePressed(e: MouseEvent) = popup(e)
                override fun mouseReleased(e: MouseEvent) = popup(e)

                private fun popup(e: MouseEvent) {
                    if (useSwingPopup && e.isPopupTrigger) showSwingPopup()
                }
            })
            manager.addListener(::queueRefresh)
            updates.addListener(::queueRefresh)
            refresh()
        }
    }

    fun remove() {
        runCatching { tray?.remove() }
    }

    private fun queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) SwingUtilities.invokeLater {
            refreshQueued.set(false)
            refresh()
        }
    }

    private fun refresh() {
        val icon = tray ?: return
        val tunnels = manager.all()
        val states = tunnels.map { it.state }
        val badgeState = listOf(
            TunnelState.ERROR, TunnelState.AUTH_REQUIRED, TunnelState.RECONNECTING, TunnelState.CONNECTING,
            TunnelState.CONNECTED, TunnelState.IDLE,
        ).firstOrNull { it in states }
        val badge = badgeState?.let(Theme::stateColor)
        if (badge != lastBadge) {
            lastBadge = badge
            icon.image = AppIcon.tray(badge)
        }
        val connected = states.count { it == TunnelState.CONNECTED }
        val problems = tunnels.filter { it.state == TunnelState.ERROR || it.state == TunnelState.AUTH_REQUIRED }
        icon.toolTip = buildString {
            append("AWS DB Connector")
            if (tunnels.isNotEmpty()) append(" – $connected of ${tunnels.size} connected")
            problems.take(3).forEach { append("\n${it.profile.name}: ${it.state.label}") }
        }.take(120)
        if (!useSwingPopup) icon.popupMenu = buildAwtMenu(buildModel())
    }

    // ------------------------------------------------------------------ menu model

    private fun buildModel(): List<Node> {
        val tunnels = manager.all()
        val nodes = mutableListOf<Node>()
        if (tunnels.isEmpty()) nodes += Item("No connections configured", enabled = false)
        tunnels.forEach { nodes += connectionMenu(it) }
        nodes += Separator
        nodes += Item("Connect all", enabled = tunnels.any { !it.isActive }) { manager.connectAll() }
        nodes += Item("Disconnect all", enabled = tunnels.any { it.isActive }) { manager.disconnectAll() }

        val profiles = AwsProfiles.load().associateBy { it.name }
        val ssoSessions = tunnels.mapNotNull { profiles[it.profile.awsProfile] }
            .filter { it.ssoKey != null }.distinctBy { it.ssoKey }
        if (ssoSessions.isNotEmpty()) {
            nodes += Sub("AWS SSO sign-in", "AWS SSO sign-in", null, ssoSessions.map { info ->
                Item("Sign in to ${info.ssoLabel}") { manager.signIn(info.name) }
            })
        }
        nodes += Separator
        updates.available?.let { nodes += Item("Update to version ${it.release.version}…") { window.showUpdate() } }
        nodes += Item("New connection…") { window.newProfile() }
        nodes += Item("Open AWS DB Connector") { window.showWindow() }
        nodes += Item("Settings…") { window.showSettings() }
        nodes += Separator
        nodes += Item("Exit") { onExit() }
        return nodes
    }

    private fun connectionMenu(t: Tunnel): Sub {
        val p = t.profile
        val id = p.id
        val address = "127.0.0.1:${p.localPort}"
        val children = mutableListOf<Node>(
            Item(t.state.label + if (t.clientCount > 0) " – ${t.clientCount} open connection(s)" else "", enabled = false, icon = StatusDot.of(t.state)),
        )
        if (t.detail.isNotBlank()) children += Item(t.detail.take(90), enabled = false)
        children += Item("$address  →  ${p.dbHost.take(48)}:${p.dbPort}", enabled = false)
        children += Item("AWS profile: ${p.awsProfile}", enabled = false)
        children += Separator
        children += Item("Connect", enabled = !t.isActive) { manager.connect(id) }
        children += Item("Disconnect", enabled = t.isActive) { manager.disconnect(id) }
        children += Item("Reconnect", enabled = t.isActive) { manager.reconnect(id) }
        children += Item("Check status") { manager.checkStatus(id) }
        if (t.state == TunnelState.AUTH_REQUIRED) children += Item("Sign in to AWS…") { manager.signIn(p.awsProfile) }
        children += Separator
        children += Item("Copy $address") { Platform.desktop.copy(address) }
        children += Item("Edit…") { window.editProfile(id) }
        return Sub("${p.name}    :${p.localPort}", "${p.name} (:${p.localPort}) – ${t.state.label}", StatusDot.of(t.state), children)
    }

    // ------------------------------------------------------------------ AWT rendering (macOS / Linux)

    private fun buildAwtMenu(nodes: List<Node>): PopupMenu = PopupMenu().also { fillAwt(it, nodes) }

    private fun fillAwt(menu: Menu, nodes: List<Node>) {
        for (node in nodes) when (node) {
            is Separator -> menu.addSeparator()
            is Item -> menu.add(MenuItem(node.text).apply {
                isEnabled = node.enabled && node.action != null
                node.action?.let { a -> addActionListener { a() } }
            })
            is Sub -> menu.add(Menu(node.plainText).also { fillAwt(it, node.children) })
        }
    }

    // ------------------------------------------------------------------ Swing rendering (Windows)

    private fun fillSwing(add: (java.awt.Component) -> Unit, addSeparator: () -> Unit, nodes: List<Node>) {
        for (node in nodes) when (node) {
            is Separator -> addSeparator()
            is Item -> add(JMenuItem(node.text, node.icon).apply {
                isEnabled = node.enabled && node.action != null
                if (node.icon != null) disabledIcon = node.icon
                node.action?.let { a -> addActionListener { a() } }
            })
            is Sub -> add(JMenu(node.text).apply {
                icon = node.icon
                fillSwing({ c -> this.add(c) }, { this.addSeparator() }, node.children)
            })
        }
    }

    private fun showSwingPopup() {
        Theme.refreshFromSystem()
        openPopup?.isVisible = false
        val at = pointer() ?: return
        val popup = JPopupMenu()
        fillSwing({ popup.add(it) }, { popup.addSeparator() }, buildModel())

        // A popup needs a focused owner window so it closes when the user clicks elsewhere.
        val anchor = JDialog(null as Window?).apply {
            isUndecorated = true
            type = Window.Type.UTILITY
            isAlwaysOnTop = true
            setSize(1, 1)
            location = at
            runCatching { opacity = 0f }
        }
        anchor.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) {
                popup.isVisible = false
            }
        })
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
            override fun popupMenuCanceled(e: PopupMenuEvent) {}
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                if (openPopup === popup) openPopup = null
                SwingUtilities.invokeLater { anchor.dispose() }
            }
        })
        anchor.isVisible = true

        // Open upwards / to the left when there is not enough room (tray is usually at the bottom right).
        val size = popup.preferredSize
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }.firstOrNull { it.contains(at) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val dx = if (at.x + size.width > screen.x + screen.width) -size.width else 0
        val dy = if (at.y + size.height > screen.y + screen.height) -size.height else 0
        openPopup = popup
        popup.show(anchor.contentPane, dx, dy)
        anchor.toFront()
        anchor.requestFocus()
    }
}
