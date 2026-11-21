package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.SsoLoginManager
import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.tunnel.SsmBackend
import com.angelmilanov.awsdbconnector.tunnel.TunnelManager
import com.angelmilanov.awsdbconnector.ui.MainWindow
import com.angelmilanov.awsdbconnector.ui.Theme
import com.angelmilanov.awsdbconnector.ui.TrayController
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.ReleaseSource
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.OsNotifier
import com.angelmilanov.awsdbconnector.util.TrayHandle
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

private const val HANDSHAKE = "AWSDBConnector:show"

/**
 * The application: creates its parts, connects them and takes them down again on exit.
 *
 * @param tray puts the icon into the system tray; returns null where the desktop has none
 * @param terminate ends the process once everything has been shut down
 * @param configDir where the connections, the settings and the port of the running instance are kept
 */
class App(
    private val tray: () -> TrayHandle?,
    private val terminate: () -> Unit,
    configDir: Path,
    /** Null in development runs, in which there is nothing to update. */
    private val install: AppInstall?,
    private val releases: ReleaseSource,
) {
    private val instanceFile = configDir.resolve("instance.port")
    private val store = ConfigStore(configDir.resolve("config.json"))
    private val exiting = AtomicBoolean(false)

    /** The main window, once it has been created on the event dispatch thread. */
    @Volatile
    var window: MainWindow? = null
        private set

    /** Returns false if the application is running already: that instance has been asked to show its window. */
    fun start(): Boolean {
        if (activateRunningInstance()) return false

        val log = ActivityLog()
        val notifier = OsNotifier({ store.settings.notifications }, log)
        val sso = SsoLoginManager({ store.settings }, log, notifier)
        val manager = TunnelManager({ store.settings }, SsmBackend({ store.settings }), sso, notifier, log)
        sso.addSuccessListener(manager::onSignedIn)
        val updates = UpdateService(store, log, notifier, install, releases)

        var controller: TrayController? = null
        val exit = {
            if (exiting.compareAndSet(false, true)) {
                Thread.ofPlatform().start {
                    runCatching { manager.shutdown() }
                    controller?.remove()
                    terminate()
                }
            }
        }
        // Make sure no session-manager-plugin processes are left behind, however the JVM ends.
        Runtime.getRuntime().addShutdownHook(Thread { if (!exiting.get()) runCatching { manager.shutdown() } })

        SwingUtilities.invokeLater {
            Theme.apply(store.settings.theme)
            val icon = tray()
            val window = MainWindow(store, manager, sso, log, updates, hideOnClose = icon != null, onExit = exit)
            controller = TrayController(manager, window, updates, exit, icon)
            notifier.tray = icon
            listenForActivation { SwingUtilities.invokeLater { window.showWindow() } }

            store.addListener { cfg -> manager.sync(cfg.profiles, startNew = true) }
            manager.sync(store.config.profiles, startNew = true)
            log.log("app", "Started – ${store.config.profiles.size} connection(s) configured")

            if (icon == null || store.settings.showWindowOnStart || store.config.profiles.isEmpty()) window.showWindow()
            updates.start()
            this.window = window
        }
        return true
    }

    /** If another instance is already running, asks it to show its window and returns true. */
    private fun activateRunningInstance(): Boolean = try {
        val port = Files.readString(instanceFile).trim().toInt()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
            socket.soTimeout = 1000
            socket.getOutputStream().write("$HANDSHAKE\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine() == HANDSHAKE
        }
    } catch (_: Exception) {
        false
    }

    private fun listenForActivation(onActivate: () -> Unit) {
        runCatching {
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            Files.writeString(instanceFile, server.localPort.toString())
            Thread.ofVirtual().name("single-instance").start {
                while (true) {
                    runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 1000
                            if (socket.getInputStream().bufferedReader().readLine() == HANDSHAKE) {
                                socket.getOutputStream().write("$HANDSHAKE\n".toByteArray())
                                socket.getOutputStream().flush()
                                onActivate()
                            }
                        }
                    }
                }
            }
        }
    }
}
