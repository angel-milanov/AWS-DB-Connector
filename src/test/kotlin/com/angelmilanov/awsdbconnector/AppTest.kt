package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.ui.MainWindow
import com.angelmilanov.awsdbconnector.ui.Theme
import com.angelmilanov.awsdbconnector.util.TrayHandle
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.MenuSelectionManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The application as a whole, from start to exit. Only the tray icon and the end of the process are stand-ins. */
class AppTest {
    private val dir = TestEnv.tempDir("app")
    private val programs = FakePrograms { FakeProcess(exitCode = 1) }
    private val terminated = AtomicInteger()
    private val apps = mutableListOf<App>()

    /** The applications that are running, with their tray icon. */
    private val running = mutableListOf<Pair<MainWindow, TrayHandle?>>()

    /** Those a test has shut down itself. */
    private val exited = mutableListOf<MainWindow>()
    private val sandbox = ConnectionProfile(name = "Sandbox", awsProfile = "keys", dbHost = "sandbox.example", bastionInstanceId = "i-0aaaabbbbccccdddd", localPort = freePort(), autoConnect = false)

    private fun app(tray: TrayHandle?, configDir: Path = dir) =
        App(tray = { tray }, terminate = { terminated.incrementAndGet() }, configDir = configDir, install = null, releases = { null }).also { apps += it }

    /** Starts the application and waits until its window exists. */
    private fun started(tray: TrayHandle?, configDir: Path = dir): MainWindow {
        val app = app(tray, configDir)
        assertTrue(app.start())
        await("the window to be created") { app.window != null }
        flushEdt()
        return app.window!!.also { running += it to tray }
    }

    /** Settings and connections as an earlier run of the application left them. */
    private fun saved(showWindowOnStart: Boolean, vararg connections: ConnectionProfile, configDir: Path = dir) {
        val store = ConfigStore(configDir.resolve("config.json"))
        store.updateSettings { it.copy(showWindowOnStart = showWindowOnStart, theme = ThemeMode.LIGHT) }
        connections.forEach(store::saveProfile)
    }

    /** Chooses "Exit" in the menu of the tray icon: the native menu, or on Windows the menu of the application. */
    private fun exitFromTray(tray: FakeTray, window: MainWindow = running.first { it.second === tray }.first) = onEdt {
        val native = tray.popupMenu
        if (native != null) {
            val exit = (0 until native.itemCount).map { native.getItem(it) }.first { it.label == "Exit" }
            exit.actionListeners.forEach { it.actionPerformed(ActionEvent(exit, ActionEvent.ACTION_PERFORMED, "Exit")) }
        } else {
            val click = MouseEvent(window, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 0, 0, 1, true, MouseEvent.BUTTON3)
            tray.mouseListeners.forEach { it.mousePressed(click) }
            val menu = MenuSelectionManager.defaultManager().selectedPath.first() as JPopupMenu
            menu.components.filterIsInstance<JMenuItem>().first { it.text == "Exit" }.doClick(0)
        }
    }

    @BeforeTest
    fun setup() = FakeAws.reset()

    @AfterTest
    fun cleanup() {
        // Every application is shut down the way a user would do it, so that nothing of it runs on into the next test.
        val before = terminated.get()
        val stillRunning = running.filter { it.first !in exited }
        for ((window, tray) in stillRunning) {
            if (tray is FakeTray) exitFromTray(tray, window) else onEdt { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) }
        }
        await("the applications to shut down") { terminated.get() == before + stillRunning.size }
        onEdt {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            apps.forEach { it.window?.dispose() }
            Theme.apply(ThemeMode.LIGHT)
        }
        programs.close()
    }

    @Test
    fun `the first start opens the window`() {
        val tray = FakeTray()
        val window = started(tray)

        assertTrue(onEdt { window.isVisible }, "there is nothing configured yet")
        assertEquals("AWS DB Connector", tray.toolTip)
        assertEquals(1, tray.mouseListeners.size, "the tray icon is connected")
        val port = Files.readString(dir.resolve("instance.port")).toInt()
        assertTrue(port in 1..65535)
    }

    @Test
    fun `later starts stay in the tray if the user wants that`() {
        saved(showWindowOnStart = false, sandbox)
        val tray = FakeTray()
        val window = started(tray)

        assertFalse(onEdt { window.isVisible })
        assertEquals("AWS DB Connector – 0 of 1 connected", tray.toolTip)
        assertEquals(1, onEdt { window.first<javax.swing.JTable>().rowCount })

        // Closing the window keeps the application running.
        onEdt {
            window.showWindow()
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        }
        assertFalse(onEdt { window.isVisible })
        assertEquals(0, terminated.get())
    }

    @Test
    fun `starting the application a second time shows the window of the first`() {
        saved(showWindowOnStart = false, sandbox)
        val window = started(FakeTray())
        val port = Files.readString(dir.resolve("instance.port")).toInt()

        // Somebody else who connects to the port is not answered.
        Socket(InetAddress.getLoopbackAddress(), port).use { stranger ->
            stranger.soTimeout = 5000
            stranger.getOutputStream().write("hello\n".toByteArray())
            assertNull(stranger.getInputStream().bufferedReader().readLine())
        }
        assertFalse(onEdt { window.isVisible })

        val second = app(FakeTray())
        assertFalse(second.start(), "the second instance has nothing to do")
        assertNull(second.window)
        await("the window of the first instance") { onEdt { window.isVisible } }
    }

    @Test
    fun `what a crashed instance left behind does not keep the application from starting`() {
        val unreadable = Files.createDirectories(dir.resolve("unreadable"))
        Files.writeString(unreadable.resolve("instance.port"), "this is not a port")
        saved(showWindowOnStart = false, sandbox, configDir = unreadable)
        started(FakeTray(), unreadable)

        val stale = Files.createDirectories(dir.resolve("stale"))
        Files.writeString(stale.resolve("instance.port"), freePort().toString()) // nobody listens there any more
        saved(showWindowOnStart = false, sandbox, configDir = stale)
        started(FakeTray(), stale)

        // Another program that happens to use the port now: it does not answer like the application would.
        val taken = Files.createDirectories(dir.resolve("taken"))
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { other ->
            Thread.ofVirtual().start { runCatching { other.accept().use { it.getOutputStream().write("220 mail.example ESMTP\n".toByteArray()) } } }
            Files.writeString(taken.resolve("instance.port"), other.localPort.toString())
            saved(showWindowOnStart = false, sandbox, configDir = taken)
            started(FakeTray(), taken)
        }
    }

    @Test
    fun `connections that are added while the application runs are picked up`() {
        saved(showWindowOnStart = false, sandbox)
        val tray = FakeTray()
        val window = started(tray)

        answering({ dialog ->
            (dialog.labelled("Name") as JTextField).text = "Orders"
            (dialog.labelled("Host name") as JTextField).text = "orders.example"
            (dialog.labelled("Bastion host") as JComboBox<*>).selectedItem = "i-0a1b2c3d4e5f60718"
            (dialog.labelled("Local port") as JSpinner).value = freePort()
            dialog.first<JCheckBox> { it.text.startsWith("Connect automatically") }.isSelected = false
            dialog.click("Save")
        }) { window.newProfile() }

        await("the new connection in the tray") { tray.toolTip == "AWS DB Connector – 0 of 2 connected" }
        assertEquals(listOf("Sandbox", "Orders"), ConfigStore(dir.resolve("config.json")).config.profiles.map { it.name }, "and it is saved")
    }

    @Test
    fun `exit takes the icon out of the tray and ends the process, once`() {
        saved(showWindowOnStart = false, sandbox)
        val tray = FakeTray()
        started(tray)

        exitFromTray(tray)
        await("the end of the process") { terminated.get() == 1 }
        assertTrue(tray.removed)
        exited += running.single().first

        exitFromTray(tray)
        assertEquals(1, terminated.get(), "a second click while the application shuts down does nothing")
    }

    @Test
    fun `without a system tray the window is the application`() {
        saved(showWindowOnStart = false, sandbox)
        val window = started(tray = null)
        assertTrue(onEdt { window.isVisible }, "there is no icon to open it from")

        onEdt { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) }
        await("the end of the process") { terminated.get() == 1 }
        exited += window
    }
}
