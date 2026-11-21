package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.tunnel.TunnelException
import com.angelmilanov.awsdbconnector.tunnel.TunnelManager
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.ui.MainWindow
import com.angelmilanov.awsdbconnector.ui.StatusDot
import com.angelmilanov.awsdbconnector.ui.Theme
import com.angelmilanov.awsdbconnector.ui.TrayController
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.AppVersion
import com.angelmilanov.awsdbconnector.update.InstallKind
import com.angelmilanov.awsdbconnector.update.Release
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Menu
import java.awt.MenuItem
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tray menu, against a stand-in for the tray icon: build servers have no system tray. */
class TrayControllerTest {
    private val dir = TestEnv.tempDir("tray")
    private val store = ConfigStore(dir.resolve("config.json"))
    private val echo = EchoServer()
    private val backend = FakeBackend(echo.port) { profile, _ ->
        when (profile.name) {
            "Payments" -> TunnelException("TargetNotConnected: i-0ffeeddccbbaa9988 is not connected.")
            "Reports" -> AuthRequiredException("Token is expired")
            else -> null
        }
    }
    private val log = ActivityLog()
    private val logins = CopyOnWriteArrayList<String>()
    private val notices = CopyOnWriteArrayList<String>()
    private val gate = LoginGate { profile, _ ->
        logins += profile
        CompletableFuture.completedFuture(false)
    }
    private val manager = TunnelManager({ store.settings }, backend, gate, { title, text, _ -> notices += "$title: $text" }, log)
    private var latest: Release? = null
    private val updates = UpdateService(
        store, log, Notifications { _, _, _ -> },
        AppInstall(AppVersion.parse("1.3.2")!!, Paths.get("app"), InstallKind.WINDOWS_INSTALLER, "windows-x64"), { latest },
    )
    private val exits = AtomicInteger()
    private val programs = FakePrograms { FakeProcess(exitCode = 1) }
    private val desktop = FakeDesktop()
    private val tray = FakeTray()
    private lateinit var window: MainWindow

    private val orders = ConnectionProfile(name = "Orders", awsProfile = "sso", dbHost = "orders.example", bastionInstanceId = "i-0a1b2c3d4e5f60718", localPort = freePort())
    private val payments = ConnectionProfile(name = "Payments", awsProfile = "keys", dbHost = "payments.example", dbPort = 3306, bastionInstanceId = "i-0ffeeddccbbaa9988", localPort = freePort(), keepAlive = false)
    private val reports = ConnectionProfile(name = "Reports", awsProfile = "legacy", dbHost = "reports.example", bastionInstanceId = "i-01234567890abcdef", localPort = freePort())
    private val sandbox = ConnectionProfile(name = "Sandbox", awsProfile = "role", dbHost = "sandbox.example", bastionInstanceId = "i-0aaaabbbbccccdddd", localPort = freePort(), autoConnect = false)

    private fun state(connection: ConnectionProfile) = manager.get(connection.id)?.state

    private fun withConnections() {
        listOf(orders, payments, reports, sandbox).forEach(store::saveProfile)
        await("the connections to settle") {
            state(orders) == TunnelState.CONNECTED && state(payments) == TunnelState.ERROR && state(reports) == TunnelState.AUTH_REQUIRED &&
                manager.get(reports.id)!!.detail.startsWith("AWS session expired")
        }
    }

    /** The tray as on macOS and Linux: the icon carries a native menu. */
    private fun nativeMenu() = onEdt { TrayController(manager, window, updates, { exits.incrementAndGet() }, tray, useSwingPopup = false) }.also { flushEdt() }

    /** The tray as on Windows: the application opens a menu of its own where the mouse is. */
    private fun ownMenu(pointer: () -> Point? = { Point(60, 60) }) =
        onEdt { TrayController(manager, window, updates, { exits.incrementAndGet() }, tray, useSwingPopup = true, pointer = pointer) }.also { flushEdt() }

    private fun Menu.labels() = (0 until itemCount).map { getItem(it).label }
    private fun Menu.item(label: String): MenuItem = (0 until itemCount).map { getItem(it) }.first { it.label == label }
    private fun Menu.submenu(prefix: String): Menu = (0 until itemCount).map { getItem(it) }.first { it.label.startsWith(prefix) } as Menu
    private fun MenuItem.choose() = onEdt { actionListeners.forEach { it.actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, label)) } }
    private fun menu() = onEdt { tray.popupMenu!! }

    private fun mouse(id: Int, button: Int = MouseEvent.BUTTON1, popup: Boolean = false) =
        MouseEvent(window, id, System.currentTimeMillis(), 0, 0, 0, 1, popup, button)

    /** Right-clicks the tray icon and returns the menu that opens, if one does. */
    private fun rightClick(): JPopupMenu? = onEdt {
        MenuSelectionManager.defaultManager().clearSelectedPath()
        tray.mouseListeners.forEach { it.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3, popup = true)) }
        MenuSelectionManager.defaultManager().selectedPath.firstOrNull() as JPopupMenu?
    }

    private fun JPopupMenu.items() = components.filterIsInstance<JMenuItem>()
    private fun JPopupMenu.choose(text: String) = onEdt { items().first { it.text == text }.doClick(0) }
    private fun JMenu.items() = menuComponents.filterIsInstance<JMenuItem>()

    /** The colour of the badge on the tray icon; null without a badge. */
    private fun badge(): Color? {
        val image = (tray.image as MultiResolutionImage).resolutionVariants.first { it.getWidth(null) == 32 } as BufferedImage
        return Color(image.getRGB(24, 24)).takeIf { color -> TunnelState.entries.any { Theme.stateColor(it) == color } }
    }

    @BeforeTest
    fun setup() {
        FakeAws.reset()
        onEdt { Theme.apply(ThemeMode.LIGHT) }
        store.addListener { manager.sync(it.profiles, startNew = true) }
        window = onEdt { MainWindow(store, manager, gate, log, updates, true) { exits.incrementAndGet() } }
    }

    @AfterTest
    fun cleanup() {
        onEdt {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            window.dispose()
        }
        manager.shutdown()
        echo.close()
        programs.close()
        desktop.close()
    }

    // ------------------------------------------------------------------ what the icon shows

    @Test
    fun `without connections the menu offers to create one`() {
        nativeMenu()
        assertEquals(
            listOf("No connections configured", "-", "Connect all", "Disconnect all", "-", "New connection…", "Open AWS DB Connector", "Settings…", "-", "Exit"),
            menu().labels(),
        )
        assertEquals(listOf(false, false, false), listOf("No connections configured", "Connect all", "Disconnect all").map { menu().item(it).isEnabled })
        assertEquals("AWS DB Connector", tray.toolTip)
        assertNull(badge())
    }

    @Test
    fun `the icon and its tooltip sum up the state of all connections`() {
        withConnections()
        nativeMenu()

        assertEquals("AWS DB Connector – 1 of 4 connected\nPayments: Error\nReports: Sign-in required", tray.toolTip)
        assertEquals(Theme.stateColor(TunnelState.ERROR), badge(), "the most urgent state decides")

        store.deleteProfile(payments.id)
        await("the badge to change") { badge() == Theme.stateColor(TunnelState.AUTH_REQUIRED) }
        store.deleteProfile(reports.id)
        await("the badge to change") { badge() == Theme.stateColor(TunnelState.CONNECTED) }
        assertEquals("AWS DB Connector – 1 of 2 connected", tray.toolTip)
    }

    @Test
    fun `every connection has a menu with its state and its actions`() {
        withConnections()
        nativeMenu()
        val address = "127.0.0.1:${orders.localPort}"

        assertEquals(
            listOf(
                "Orders (:${orders.localPort}) – Connected", "Payments (:${payments.localPort}) – Error", "Reports (:${reports.localPort}) – Sign-in required",
                "Sandbox (:${sandbox.localPort}) – Disconnected", "-", "Connect all", "Disconnect all", "AWS SSO sign-in", "-",
                "New connection…", "Open AWS DB Connector", "Settings…", "-", "Exit",
            ),
            menu().labels(),
        )
        assertEquals(
            listOf("Connected", "$address  →  orders.example:5432", "AWS profile: sso", "-", "Connect", "Disconnect", "Reconnect", "Check status", "-", "Copy $address", "Edit…"),
            menu().submenu("Orders").labels(),
        )
        assertEquals(
            listOf(false, false, false, true, false, true, true, true, true, true, true),
            menu().submenu("Orders").let { m -> (0 until m.itemCount).map { m.getItem(it).isEnabled } }, "information is greyed out, as is what does not apply",
        )
        assertEquals("TargetNotConnected: i-0ffeeddccbbaa9988 is not connected.", menu().submenu("Payments").labels()[1], "the reason of a failure")
        assertTrue("Sign in to AWS…" in menu().submenu("Reports").labels())
        assertFalse("Sign in to AWS…" in menu().submenu("Orders").labels())

        Socket(InetAddress.getLoopbackAddress(), orders.localPort).use {
            await("the client in the menu") { menu().submenu("Orders").labels()[0] == "Connected – 1 open connection(s)" }
        }
    }

    // ------------------------------------------------------------------ what the menu does

    @Test
    fun `a connection is controlled from its menu`() {
        withConnections()
        nativeMenu()
        fun orders() = menu().submenu("Orders")

        orders().item("Disconnect").choose()
        await("Orders to disconnect") { state(orders) == TunnelState.STOPPED }
        await("the menu to follow") { menu().labels()[0].endsWith("Disconnected") }
        orders().item("Connect").choose()
        await("Orders to connect") { state(orders) == TunnelState.CONNECTED }
        val before = backend.opens.get()
        orders().item("Reconnect").choose()
        await("Orders to reconnect") { backend.opens.get() == before + 1 && state(orders) == TunnelState.CONNECTED }

        orders().item("Check status").choose()
        await("the status report") { notices.any { it.startsWith("Orders: Connected") } }
        orders().item("Copy 127.0.0.1:${orders.localPort}").choose()
        assertEquals(listOf("127.0.0.1:${orders.localPort}"), desktop.copied)

        answering({ dialog ->
            assertEquals("Edit connection", dialog.title)
            dialog.click("Cancel")
        }) { orders().item("Edit…").choose() }

        logins.clear()
        menu().submenu("Reports").item("Sign in to AWS…").choose()
        assertEquals(listOf("legacy"), logins)
    }

    @Test
    fun `all connections are switched together, and each SSO session is signed in to once`() {
        withConnections()
        nativeMenu()

        assertEquals(listOf("Sign in to corp", "Sign in to https://legacy.awsapps.com/start"), menu().submenu("AWS SSO sign-in").labels(), "Orders and Sandbox share a session")
        logins.clear()
        menu().submenu("AWS SSO sign-in").item("Sign in to corp").choose()
        assertEquals(listOf("sso"), logins)

        menu().item("Connect all").choose()
        await("Sandbox to connect") { state(sandbox) == TunnelState.CONNECTED }
        menu().item("Disconnect all").choose()
        await("all to disconnect") { manager.all().all { it.state == TunnelState.STOPPED } }
        await("the menu to follow") { !menu().item("Disconnect all").isEnabled && menu().item("Connect all").isEnabled }
    }

    @Test
    fun `the window, a new connection, an update and the exit are one click away`() {
        nativeMenu()

        menu().item("Open AWS DB Connector").choose()
        assertTrue(onEdt { window.isVisible })
        menu().item("Settings…").choose()
        assertEquals(2, onEdt { (window.contentPane as JTabbedPane).selectedIndex })
        answering({ dialog ->
            assertEquals("New connection", dialog.title)
            dialog.click("Cancel")
        }) { menu().item("New connection…").choose() }

        assertFalse(menu().labels().any { it.startsWith("Update") })
        latest = Release(AppVersion.parse("1.4.0")!!, "v1.4.0", "", "https://github.com/", emptyList())
        updates.check(manual = false)
        await("the update in the menu") { "Update to version 1.4.0…" in menu().labels() }
        answering({ dialog ->
            assertEquals("Software update", dialog.title)
            dialog.click("Later")
        }) { menu().item("Update to version 1.4.0…").choose() }

        menu().item("Exit").choose()
        assertEquals(1, exits.get())
    }

    @Test
    fun `a click on the icon opens the window`() {
        nativeMenu()
        onEdt {
            tray.mouseListeners.forEach { it.mouseClicked(mouse(MouseEvent.MOUSE_CLICKED, MouseEvent.BUTTON3)) }
            assertFalse(window.isVisible, "the right button is for the menu")
            tray.mouseListeners.forEach { it.mouseClicked(mouse(MouseEvent.MOUSE_CLICKED)) }
            assertTrue(window.isVisible)
        }
        assertNull(rightClick(), "the native menu is opened by the system, not by the application")
    }

    @Test
    fun `the icon is taken out of the tray on exit`() {
        nativeMenu().remove()
        assertTrue(tray.removed)

        onEdt { TrayController(manager, window, updates, {}, FakeTray(broken = true)) }.remove() // the tray is gone already: nothing to do
        onEdt { TrayController(manager, window, updates, {}, tray = null) }.remove() // a desktop without a tray
    }

    // ------------------------------------------------------------------ the menu of the application (Windows)

    /*
     * The tests below look at a menu in the same turn of the event queue in which it was opened. On a display without
     * a window manager (xvfb on a build server) the focus moves on right afterwards, which closes the menu - as it should.
     */

    @Test
    fun `on Windows the application opens its own menu where the mouse is`() {
        withConnections()
        ownMenu()
        assertNull(tray.popupMenu, "no native menu")
        onEdt {
            tray.mouseListeners.forEach { it.mousePressed(mouse(MouseEvent.MOUSE_PRESSED)) }
            assertNull(MenuSelectionManager.defaultManager().selectedPath.firstOrNull(), "not on a left click")

            val popup = rightClick()!!
            assertTrue(popup.isVisible)
            assertEquals(Point(60, 60), SwingUtilities.getWindowAncestor(popup.invoker).location)
            assertEquals(
                listOf(
                    "Orders    :${orders.localPort}", "Payments    :${payments.localPort}", "Reports    :${reports.localPort}", "Sandbox    :${sandbox.localPort}",
                    "Connect all", "Disconnect all", "AWS SSO sign-in", "New connection…", "Open AWS DB Connector", "Settings…", "Exit",
                ),
                popup.items().map { it.text },
            )
            val submenu = popup.items().first() as JMenu
            assertTrue(submenu.icon is StatusDot, "the state is shown as a coloured dot")
            assertEquals("Connected" to false, submenu.items().first().let { it.text to it.isEnabled })
            assertTrue(submenu.items().first().disabledIcon is StatusDot, "also on the line that cannot be clicked")
            assertEquals(listOf("Connect", "Disconnect", "Reconnect", "Check status"), submenu.items().drop(3).take(4).map { it.text })
            assertEquals(listOf(false, true, true, true), submenu.items().drop(3).take(4).map { it.isEnabled })

            submenu.items().first { it.text == "Disconnect" }.doClick(0)
        }
        await("Orders to disconnect") { state(orders) == TunnelState.STOPPED }

        rightClick()!!.choose("Exit")
        assertEquals(1, exits.get())
    }

    @Test
    fun `the menu closes when something else is clicked, and only one is open at a time`() {
        ownMenu()
        val anchors = onEdt {
            val first = rightClick()!!
            assertTrue(first.isVisible)

            tray.mouseListeners.forEach { it.mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON3, popup = true)) } // Windows reports the click on release
            val second = MenuSelectionManager.defaultManager().selectedPath.first() as JPopupMenu
            assertTrue(second.isVisible && second !== first)
            assertFalse(first.isVisible)

            // The user clicks somewhere else: the invisible window the menu hangs on loses the focus.
            val anchor = SwingUtilities.getWindowAncestor(second.invoker)
            anchor.windowFocusListeners.forEach { it.windowLostFocus(WindowEvent(anchor, WindowEvent.WINDOW_LOST_FOCUS)) }
            assertFalse(second.isVisible)

            // Escape cancels the menu, which Swing reports separately.
            val third = rightClick()!!
            third.putClientProperty("JPopupMenu.firePopupMenuCanceled", true)
            third.isVisible = false
            assertFalse(third.isVisible)

            listOf(first, second, third).map { SwingUtilities.getWindowAncestor(it.invoker) }
        }
        await("the invisible windows to be cleaned up") { onEdt { anchors.none { it.isDisplayable } } }
    }

    @Test
    fun `the menu opens towards the screen when the icon is in a corner`() {
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        val corner = Point(screen.x + screen.width - 2, screen.y + screen.height - 2)
        ownMenu { corner }

        val location = onEdt { rightClick()!!.locationOnScreen }
        assertTrue(location.x < corner.x - 20 && location.y < corner.y - 20, "the menu at $location must open up and to the left of $corner")
    }

    @Test
    fun `no menu is opened while the position of the mouse is unknown`() {
        ownMenu { null }
        assertNull(rightClick())

        // A position outside of every screen: the menu is opened, the system decides where it ends up.
        ownMenu { Point(-20_000, -20_000) }
        assertTrue(onEdt { rightClick()!!.isVisible })
    }

    @Test
    fun `the application asks the system where the mouse is`() {
        onEdt { TrayController(manager, window, updates, {}, tray, useSwingPopup = true) }
        // A build server may have no pointer at all; where there is one, the menu opens.
        onEdt { rightClick()?.let { assertTrue(it.isVisible) } }
    }
}
