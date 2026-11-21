package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.SsoLoginManager
import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.tunnel.SsmBackend
import com.angelmilanov.awsdbconnector.tunnel.TunnelManager
import com.angelmilanov.awsdbconnector.ui.MainWindow
import com.angelmilanov.awsdbconnector.ui.Theme
import com.angelmilanov.awsdbconnector.ui.TrayController
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.GitHubReleases
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.OsNotifier
import com.angelmilanov.awsdbconnector.util.Platform
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private const val HANDSHAKE = "AWSDBConnector:show"

fun main() {
    System.setProperty("apple.awt.UIElement", "true") // tray-only on macOS: no dock icon
    System.setProperty("awt.useSystemAAFontSettings", "on")
    if (activateRunningInstance()) return

    val store = ConfigStore()
    val log = ActivityLog()
    val notifier = OsNotifier({ store.settings.notifications }, log)
    val sso = SsoLoginManager({ store.settings }, log, notifier)
    val manager = TunnelManager({ store.settings }, SsmBackend { store.settings }, sso, notifier, log)
    sso.addSuccessListener(manager::onSignedIn)
    val updates = UpdateService(store, log, notifier, AppInstall.detect(), GitHubReleases())

    val exiting = AtomicBoolean(false)
    var tray: TrayController? = null
    val exit = {
        if (exiting.compareAndSet(false, true)) {
            Thread.ofPlatform().start {
                runCatching { manager.shutdown() }
                tray?.remove()
                exitProcess(0)
            }
        }
    }
    // Make sure no session-manager-plugin processes are left behind, however the JVM ends.
    Runtime.getRuntime().addShutdownHook(Thread { if (!exiting.get()) runCatching { manager.shutdown() } })

    SwingUtilities.invokeLater {
        Theme.apply(store.settings.theme)
        val traySupported = java.awt.SystemTray.isSupported()
        val window = MainWindow(store, manager, sso, log, updates, hideOnClose = traySupported, onExit = exit)
        tray = TrayController(manager, window, updates, exit).also { notifier.trayIcon = it.trayIcon }
        listenForActivation { SwingUtilities.invokeLater { window.showWindow() } }

        store.addListener { cfg -> manager.sync(cfg.profiles, startNew = true) }
        manager.sync(store.config.profiles, startNew = true)
        log.log("app", "Started – ${store.config.profiles.size} connection(s) configured")

        if (!traySupported || store.settings.showWindowOnStart || store.config.profiles.isEmpty()) window.showWindow()
        updates.start()
    }
}

private val instanceFile = Platform.configDir.resolve("instance.port")

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
