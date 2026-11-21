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
import com.angelmilanov.awsdbconnector.ui.ProfileDialog
import com.angelmilanov.awsdbconnector.ui.StatusDot
import com.angelmilanov.awsdbconnector.ui.Theme
import com.angelmilanov.awsdbconnector.ui.UpdateDialog
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.AppVersion
import com.angelmilanov.awsdbconnector.update.InstallKind
import com.angelmilanov.awsdbconnector.update.Release
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import java.awt.Container
import java.awt.Dialog
import java.awt.Font
import java.awt.Frame
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.MenuSelectionManager
import javax.swing.SwingConstants
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainWindowTest {
    private val dir = TestEnv.tempDir("window")
    private val store = ConfigStore(dir.resolve("config.json"))
    private val echo = EchoServer()
    private val backend = FakeBackend(echo.port) { profile, _ ->
        when (profile.awsProfile) {
            "stopped-bastion" -> TunnelException("TargetNotConnected: i-0ffeeddccbbaa9988 is not connected.")
            "signed-out" -> AuthRequiredException("Token is expired")
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
    private val windows = mutableListOf<MainWindow>()

    private val orders = ConnectionProfile(name = "Orders", awsProfile = "keys", dbHost = "orders.example", bastionInstanceId = "i-0a1b2c3d4e5f60718", localPort = freePort())
    private val payments = ConnectionProfile(name = "Payments", awsProfile = "stopped-bastion", dbHost = "payments.example", dbPort = 3306, bastionInstanceId = "i-0ffeeddccbbaa9988", localPort = freePort(), keepAlive = false)
    private val reports = ConnectionProfile(name = "Reports", awsProfile = "signed-out", dbHost = "reports.example", bastionInstanceId = "i-01234567890abcdef", localPort = freePort())
    private val sandbox = ConnectionProfile(name = "Sandbox", awsProfile = "keys", dbHost = "sandbox.example", bastionInstanceId = "i-0aaaabbbbccccdddd", localPort = freePort(), autoConnect = false)

    private fun window(hideOnClose: Boolean = true): MainWindow =
        onEdt { MainWindow(store, manager, gate, log, updates, hideOnClose) { exits.incrementAndGet() } }.also { windows += it }

    /** The four connections of the screenshot in the README: connected, failed, signed out and switched off. */
    private fun withConnections(): MainWindow {
        listOf(orders, payments, reports, sandbox).forEach(store::saveProfile)
        await("the connections to settle") {
            state(orders) == TunnelState.CONNECTED && state(payments) == TunnelState.ERROR && state(reports) == TunnelState.AUTH_REQUIRED &&
                manager.get(reports.id)!!.detail.startsWith("AWS session expired")
        }
        return window().also { flushEdt() }
    }

    private fun state(connection: ConnectionProfile) = manager.get(connection.id)?.state
    private fun names() = store.config.profiles.map { it.name }

    private fun MainWindow.table() = first<JTable>()
    private fun MainWindow.tabs() = contentPane as JTabbedPane
    private fun MainWindow.select(row: Int) = onEdt { table().setRowSelectionInterval(row, row) }

    /** The cell as it is drawn. */
    private fun MainWindow.cell(row: Int, column: Int) = table().let { it.prepareRenderer(it.getCellRenderer(row, column), row, column) as JLabel }
    private fun MainWindow.row(row: Int) = onEdt { (0 until table().columnCount).map { cell(row, it).text } }

    /** The line below the table. */
    private fun MainWindow.detail() = onEdt { ((tabs().getComponentAt(0) as Container).components.last() as JLabel).text }
    private fun MainWindow.enabled(vararg buttons: String) = onEdt { buttons.associateWith { button(it).isEnabled } }

    private fun MainWindow.mouse(id: Int, row: Int, clicks: Int = 1, popup: Boolean = false) {
        val table = table()
        val at = if (row >= 0) table.getCellRect(row, 0, true).let { java.awt.Point(it.x + 20, it.y + it.height / 2) } else java.awt.Point(20, table.height - 2)
        table.dispatchEvent(MouseEvent(table, id, System.currentTimeMillis(), 0, at.x, at.y, clicks, popup, if (popup) MouseEvent.BUTTON3 else MouseEvent.BUTTON1))
    }

    /** Opens the context menu of a row and returns it. */
    private fun MainWindow.contextMenu(row: Int): JPopupMenu = onEdt {
        mouse(MouseEvent.MOUSE_PRESSED, row, popup = true)
        MenuSelectionManager.defaultManager().selectedPath.first() as JPopupMenu
    }

    private fun JPopupMenu.choose(text: String) = onEdt {
        components.filterIsInstance<JMenuItem>().first { it.text == text }.doClick(0)
        isVisible = false
    }

    private fun Dialog.fill(label: String, text: String) {
        (labelled(label) as JTextField).text = text
    }

    @BeforeTest
    fun setup() {
        FakeAws.reset()
        onEdt { Theme.apply(ThemeMode.LIGHT) }
        store.addListener { manager.sync(it.profiles, startNew = true) }
    }

    @AfterTest
    fun cleanup() {
        onEdt { windows.forEach { it.dispose() } }
        manager.shutdown()
        echo.close()
        programs.close()
        onEdt { Theme.apply(ThemeMode.LIGHT) }
    }

    // ------------------------------------------------------------------ the list of connections

    @Test
    fun `an empty window invites to create the first connection`() {
        val window = window()
        val invitation = onEdt { window.first<JLabel> { it.text == "No connections yet" }.parent }
        assertTrue(onEdt { invitation.isVisible })
        assertFalse(onEdt { window.first<JScrollPane> { it.viewport.view is JTable }.isVisible })
        assertEquals(" ", window.detail())
        assertEquals("AWS DB Connector", window.title)

        answering({ dialog ->
            assertTrue(dialog is ProfileDialog && dialog.title == "New connection")
            dialog.fill("Name", "Orders")
            dialog.fill("Host name", "orders.example")
            (dialog.labelled("Bastion host") as JComboBox<*>).selectedItem = "i-0a1b2c3d4e5f60718"
            (dialog.labelled("Local port") as JSpinner).value = freePort() // the suggested 5432 may be in use where the tests run
            dialog.click("Save")
        }) { (invitation as Container).click("New connection") }

        assertEquals(listOf("Orders"), names())
        await("the list to replace the invitation") { onEdt { !invitation.isVisible && window.table().rowCount == 1 } }
        await("the new connection to connect") { manager.all().single().state == TunnelState.CONNECTED }
    }

    @Test
    fun `the table shows every connection with its state`() {
        val window = withConnections()

        assertEquals(listOf("Name", "Status", "AWS profile", "Local port", "Database", "Bastion host"), onEdt { (0 until 6).map { window.table().getColumnName(it) } })
        assertEquals(listOf("Orders", "Connected", "keys", orders.localPort.toString(), "orders.example:5432", "i-0a1b2c3d4e5f60718"), window.row(0))
        assertEquals(listOf("Payments", "Error", "stopped-bastion", payments.localPort.toString(), "payments.example:3306", "i-0ffeeddccbbaa9988"), window.row(1))
        assertEquals("Sign-in required", window.row(2)[1])
        assertEquals("Disconnected", window.row(3)[1])
        onEdt {
            assertTrue(window.cell(0, 1).icon is StatusDot)
            assertNull(window.cell(0, 0).icon)
            assertEquals("Connected", window.cell(0, 1).toolTipText)
            assertEquals("TargetNotConnected: i-0ffeeddccbbaa9988 is not connected.", window.cell(1, 1).toolTipText, "the reason is one mouse-over away")
            assertEquals("orders.example:5432", window.cell(0, 4).toolTipText, "long values can be read in full")
            assertTrue(window.cell(0, 0).font.isBold && window.cell(0, 2).font.style == Font.PLAIN)

            val table = window.table()
            val header = table.tableHeader.defaultRenderer.getTableCellRendererComponent(table, "Name", false, false, -1, 0) as JLabel
            assertEquals(SwingConstants.LEFT, header.horizontalAlignment)
        }

        // A database client that is connected is counted.
        Socket(InetAddress.getLoopbackAddress(), orders.localPort).use {
            await("the client in the table") { window.row(0)[1] == "Connected  (1)" }
        }
        await("the client to be gone") { window.row(0)[1] == "Connected" }
    }

    @Test
    fun `selecting a connection shows its details and what can be done with it`() {
        val window = withConnections()
        val actions = arrayOf("Connect", "Disconnect", "Reconnect", "Edit", "Duplicate", "Delete")

        assertEquals("Select a connection to see details. Connections stay available from the tray icon when this window is closed.", window.detail())
        assertEquals(actions.associateWith { false }, window.enabled(*actions))

        val session = manager.get(orders.id)!!.sessionId
        window.select(0)
        assertEquals("127.0.0.1:${orders.localPort}  →  orders.example:5432   ·   Connected   ·   session $session", window.detail())
        assertEquals(mapOf("Connect" to false, "Disconnect" to true, "Reconnect" to true, "Edit" to true, "Duplicate" to true, "Delete" to true), window.enabled(*actions))

        window.select(1)
        assertEquals(
            "127.0.0.1:${payments.localPort}  →  payments.example:3306   ·   Error   ·   TargetNotConnected: i-0ffeeddccbbaa9988 is not connected.",
            window.detail(),
        )

        window.select(3)
        assertEquals("127.0.0.1:${sandbox.localPort}  →  sandbox.example:5432   ·   Disconnected", window.detail())
        assertEquals(mapOf("Connect" to true, "Disconnect" to false, "Reconnect" to false), window.enabled("Connect", "Disconnect", "Reconnect"))

        window.select(0)
        Socket(InetAddress.getLoopbackAddress(), orders.localPort).use {
            await("the client in the details") { window.detail().endsWith("session $session   ·   1 open connection(s)") }
        }
    }

    @Test
    fun `the buttons connect, disconnect and reconnect the selected connection`() {
        val window = withConnections()

        window.select(3)
        onEdt { window.click("Connect") }
        await("Sandbox to connect") { state(sandbox) == TunnelState.CONNECTED }
        await("the buttons to follow") { window.enabled("Disconnect") == mapOf("Disconnect" to true) }

        val before = backend.opens.get()
        onEdt { window.click("Reconnect") }
        await("Sandbox to reconnect") { backend.opens.get() == before + 1 && state(sandbox) == TunnelState.CONNECTED }

        onEdt { window.click("Disconnect") }
        await("Sandbox to disconnect") { state(sandbox) == TunnelState.STOPPED }
        await("the table to follow") { window.row(3)[1] == "Disconnected" }
    }

    // ------------------------------------------------------------------ changing connections

    @Test
    fun `a connection is edited from the toolbar, with a double click or from another window`() {
        val window = withConnections()

        window.select(3)
        answering({ dialog ->
            assertEquals("Edit connection", dialog.title)
            dialog.fill("Name", "Sandbox EU")
            dialog.click("Save")
        }) { window.click("Edit") }
        assertEquals(listOf("Orders", "Payments", "Reports", "Sandbox EU"), names())
        await("the table to show the new name") { window.row(3)[0] == "Sandbox EU" }
        assertEquals(3, onEdt { window.table().selectedRow }, "the connection stays selected")

        answering({ dialog ->
            dialog.fill("Name", "Changed my mind")
            dialog.click("Cancel")
        }) { window.mouse(MouseEvent.MOUSE_CLICKED, row = 3, clicks = 2) }
        assertEquals("Sandbox EU", names().last())

        onEdt {
            window.mouse(MouseEvent.MOUSE_CLICKED, row = 3, clicks = 1) // a single click only selects
            window.mouse(MouseEvent.MOUSE_CLICKED, row = -1, clicks = 2) // nothing to edit below the last row
        }

        // The tray menu edits and creates connections through the window.
        onEdt { window.tabs().selectedIndex = 2 }
        answering({ dialog ->
            assertEquals("Orders", (dialog.labelled("Name") as JTextField).text)
            dialog.click("Cancel")
        }) { window.editProfile(orders.id) }
        assertEquals(0, onEdt { window.tabs().selectedIndex }, "the list of connections is brought to the front")
        assertTrue(onEdt { window.isVisible })
        onEdt { window.editProfile("no-such-connection") }

        answering({ dialog ->
            assertEquals("New connection", dialog.title)
            dialog.click("Cancel")
        }) { window.newProfile() }
        answering({ dialog -> dialog.click("Cancel") }) { window.click("New connection") }
        assertEquals(4, names().size)
    }

    @Test
    fun `a duplicate gets a free port and does not connect on its own`() {
        store.saveProfile(orders)
        store.saveProfile(sandbox.copy(localPort = orders.localPort + 1))
        val window = window().also { flushEdt() }

        window.select(0)
        onEdt { window.click("Duplicate") }

        val copy = store.config.profiles.last()
        assertEquals("Orders (copy)", copy.name)
        assertEquals(orders.localPort + 2, copy.localPort, "the next port is taken by another connection")
        assertFalse(copy.autoConnect)
        assertEquals(orders.copy(id = copy.id, name = copy.name, localPort = copy.localPort, autoConnect = false), copy)
        await("the copy in the table") { onEdt { window.table().rowCount } == 3 }
        assertEquals(TunnelState.STOPPED, state(copy))
    }

    @Test
    fun `a connection is deleted after a confirmation`() {
        val window = withConnections()
        window.select(1)

        answering({ dialog ->
            assertEquals("Delete connection", dialog.title)
            assertEquals("Delete the connection \"Payments\"?", dialog.first<javax.swing.JOptionPane>().message)
            dialog.click("Cancel")
        }) { window.click("Delete") }
        assertEquals(4, names().size)

        answering({ dialog -> dialog.click("OK") }) { window.click("Delete") }
        assertEquals(listOf("Orders", "Reports", "Sandbox"), names())
        await("the row to disappear") { onEdt { window.table().rowCount } == 3 }
        assertEquals(-1, onEdt { window.table().selectedRow })
        assertTrue(window.detail().startsWith("Select a connection"))
    }

    @Test
    fun `the context menu of a row offers the same actions`() {
        val window = withConnections()
        onEdt { window.showWindow() }

        val disconnected = window.contextMenu(3)
        assertEquals(3, onEdt { window.table().selectedRow }, "the row under the mouse is selected")
        assertEquals(
            listOf("Connect" to true, "Disconnect" to false, "Reconnect" to false, "Check status" to true, "Edit…" to true, "Duplicate" to true, "Delete" to true),
            onEdt { disconnected.components.filterIsInstance<JMenuItem>().map { it.text to it.isEnabled } },
        )
        disconnected.choose("Connect")
        await("Sandbox to connect") { state(sandbox) == TunnelState.CONNECTED }

        val before = backend.opens.get()
        window.contextMenu(3).choose("Reconnect")
        await("Sandbox to reconnect") { backend.opens.get() == before + 1 && state(sandbox) == TunnelState.CONNECTED }
        window.contextMenu(3).choose("Check status")
        await("the status report") { notices.any { it.startsWith("Sandbox: Connected") } }
        window.contextMenu(3).choose("Disconnect")
        await("Sandbox to disconnect") { state(sandbox) == TunnelState.STOPPED }

        // Signing in is offered where it is needed.
        val signedOut = window.contextMenu(2)
        assertTrue(onEdt { signedOut.components.filterIsInstance<JMenuItem>().any { it.text == "Sign in to AWS…" } })
        logins.clear()
        signedOut.choose("Sign in to AWS…")
        assertEquals(listOf("signed-out"), logins)

        val menu = window.contextMenu(3)
        answering({ dialog -> dialog.click("Cancel") }) { menu.choose("Edit…") }
        window.contextMenu(3).choose("Duplicate")
        assertEquals("Sandbox (copy)", names().last())
        await("the copy in the table") { onEdt { window.table().rowCount } == 5 }
        val last = window.contextMenu(4)
        answering({ dialog -> dialog.click("OK") }) { last.choose("Delete") }
        assertEquals(4, names().size)

        // No menu without a row, and none for an ordinary click.
        onEdt {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            window.mouse(MouseEvent.MOUSE_PRESSED, row = -1, popup = true)
            window.mouse(MouseEvent.MOUSE_RELEASED, row = 0)
            assertTrue(MenuSelectionManager.defaultManager().selectedPath.isEmpty())
        }
    }

    @Test
    fun `the selection follows its connection when the list changes`() {
        val window = withConnections()
        window.select(2)

        store.deleteProfile(orders.id)
        await("the list to change") { onEdt { window.table().rowCount } == 3 }
        assertEquals("Reports", onEdt { window.table().getValueAt(window.table().selectedRow, 0) })
    }

    // ------------------------------------------------------------------ the window itself

    @Test
    fun `closing the window hides it when there is a tray icon, and exits when there is none`() {
        val withTray = window(hideOnClose = true)
        onEdt {
            withTray.showWindow()
            withTray.dispatchEvent(WindowEvent(withTray, WindowEvent.WINDOW_CLOSING))
        }
        assertFalse(onEdt { withTray.isVisible })
        assertEquals(0, exits.get())

        val withoutTray = window(hideOnClose = false)
        onEdt {
            withoutTray.showWindow()
            withoutTray.dispatchEvent(WindowEvent(withoutTray, WindowEvent.WINDOW_CLOSING))
        }
        assertEquals(1, exits.get())
    }

    @Test
    fun `showing the window brings it back from wherever it is`() {
        val window = window()
        onEdt {
            window.showWindow()
            window.extendedState = Frame.ICONIFIED
            window.showWindow()
        }
        assertTrue(onEdt { window.isVisible })
        assertEquals(0, onEdt { window.extendedState and Frame.ICONIFIED })

        onEdt { window.showSettings() }
        assertEquals("Settings", onEdt { window.tabs().getTitleAt(window.tabs().selectedIndex) })
    }

    @Test
    fun `the theme of the system is read again when the window is activated`() {
        val window = window()
        onEdt { Theme.apply(ThemeMode.SYSTEM) }
        assertFalse(Theme.isDark)

        programs.respond = { FakeProcess("AppsUseLightTheme REG_DWORD 0x0 / Dark / 'prefer-dark'") }
        onEdt { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_ACTIVATED)) }
        await("the dark theme") { Theme.isDark }
    }

    @Test
    fun `an available update is announced next to the tabs`() {
        val window = window()
        fun announcement() = onEdt { window.all<JButton>().firstOrNull { it.text.orEmpty().startsWith("Update to") && it.isVisible } }
        assertNull(announcement())
        onEdt { window.showUpdate() } // nothing to show

        latest = Release(AppVersion.parse("1.4.0")!!, "v1.4.0", "What is new", "https://github.com/", emptyList())
        updates.check(manual = true)
        await("the announcement") { announcement()?.text == "Update to 1.4.0" }

        answering({ dialog ->
            assertTrue(dialog is UpdateDialog)
            window.showUpdate() // asking again, from the tray menu for example, does not open a second dialog
            assertEquals(1, java.awt.Window.getWindows().count { it is UpdateDialog && it.isShowing })
            dialog.click("Later")
        }) { announcement()!!.doClick(0) }

        answering({ dialog -> dialog.click("Skip this version") }) { window.showUpdate() }
        await("the announcement to go away") { announcement() == null }
    }

    @Test
    fun `the activity tab shows what happens, and can be cleared`() {
        log.log("app", "Started")
        val window = window()
        val activity = onEdt { window.first<JTextArea>() }
        assertTrue(onEdt { activity.text }.endsWith("[app] Started\n"))
        assertFalse(onEdt { activity.isEditable })

        log.log("Orders", "Connected (session fake-1)")
        await("the new line") { onEdt { activity.text }.endsWith("[Orders] Connected (session fake-1)\n") }
        assertEquals(2, onEdt { activity.lineCount } - 1)

        log.log("Orders", "x".repeat(400_100))
        await("the oldest text to make room") { onEdt { activity.document.length } == 400_000 }

        onEdt { (window.tabs().getComponentAt(1) as Container).click("Clear") }
        assertEquals("", onEdt { activity.text })
        assertTrue(log.snapshot().isEmpty())
    }
}
