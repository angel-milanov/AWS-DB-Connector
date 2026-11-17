package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.tunnel.BackendSession
import com.angelmilanov.awsdbconnector.tunnel.TunnelBackend
import com.angelmilanov.awsdbconnector.tunnel.TunnelException
import com.angelmilanov.awsdbconnector.tunnel.TunnelManager
import com.angelmilanov.awsdbconnector.ui.AppIcon
import com.angelmilanov.awsdbconnector.ui.MainWindow
import com.angelmilanov.awsdbconnector.ui.ProfileDialog
import com.angelmilanov.awsdbconnector.ui.Theme
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import java.awt.Color
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Development helper: renders the UI off-screen into PNG files (args: output directory). */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val dir = Files.createTempDirectory("awsdbc-preview")
    val store = ConfigStore(dir.resolve("config.json"))
    val samples = listOf(
        ConnectionProfile(name = "Invoice PROD", awsProfile = "invoice-prod", dbHost = "invoice.cluster-c1x2y3.eu-central-1.rds.amazonaws.com", bastionInstanceId = "i-0a1b2c3d4e5f60718", localPort = 25432),
        ConnectionProfile(name = "Payments TEST", awsProfile = "pay-test", dbHost = "pay-test.c1x2y3.eu-central-1.rds.amazonaws.com", dbPort = 3306, bastionInstanceId = "i-0ffeeddccbbaa9988", localPort = 13306),
        ConnectionProfile(name = "Fulfilment DEV", awsProfile = "fulfill-dev", dbHost = "fulfill-dev.c9z8y7.eu-west-1.rds.amazonaws.com", bastionInstanceId = "i-01234567890abcdef", localPort = 15433),
        ConnectionProfile(name = "Sandbox", awsProfile = "sandbox", dbHost = "sandbox.c9z8y7.eu-west-1.rds.amazonaws.com", bastionInstanceId = "i-0aaaabbbbccccdddd", localPort = 15434, autoConnect = false),
    )
    samples.forEach(store::saveProfile)
    val log = ActivityLog()
    val backend = TunnelBackend { p, _ ->
        when (p.awsProfile) {
            "pay-test" -> throw TunnelException("TargetNotConnected: i-0ffeeddccbbaa9988 is not connected.")
            "fulfill-dev" -> throw AuthRequiredException("Token is expired")
            else -> object : BackendSession {
                override val id = "user-0f3a9c1d2b4e5f607"
                override val localPort = 1
                override fun isAlive() = true
                override fun onExit(callback: () -> Unit) {}
                override fun checkRemote() = true
                override fun close() {}
            }
        }
    }
    val manager = TunnelManager({ store.settings }, backend, LoginGate { _, _ -> CompletableFuture.completedFuture(false) }, Notifications { _, _, _ -> }, log)
    manager.sync(store.config.profiles, startNew = true)
    Thread.sleep(1500)

    for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) SwingUtilities.invokeAndWait {
        Theme.apply(mode)
        val window = MainWindow(store, manager, LoginGate { _, _ -> CompletableFuture.completedFuture(false) }, log, true) {}
        window.addNotify()
        window.validate()
        val tabs = window.contentPane as JTabbedPane
        for (tab in 0..2) {
            tabs.selectedIndex = tab
            if (tab == 0) findTable(tabs)?.setRowSelectionInterval(1, 1)
            window.validate()
            snapshot(window.contentPane, File(out, "${mode.name.lowercase()}-tab$tab.png"))
        }
        val dialog = ProfileDialog(window, samples[0], samples.drop(1), LoginGate { _, _ -> CompletableFuture.completedFuture(false) })
        dialog.addNotify()
        dialog.validate()
        snapshot(dialog.contentPane, File(out, "${mode.name.lowercase()}-dialog.png"))
        dialog.dispose()
        window.dispose()
    }

    val strip = BufferedImage(64 * 6, 64, BufferedImage.TYPE_INT_ARGB)
    strip.createGraphics().apply {
        color = Color(0x20, 0x20, 0x20); fillRect(0, 0, 192, 64); color = Color(0xEE, 0xEE, 0xEE); fillRect(192, 0, 192, 64)
        listOf(null, Color(0x2E, 0xA0, 0x43), Color(0xF8, 0x51, 0x49)).forEachIndexed { i, badge ->
            drawImage(AppIcon.render(16, badge), 8 + i * 28, 24, null)
            drawImage(AppIcon.render(16, badge), 200 + i * 28, 24, null)
        }
        drawImage(AppIcon.render(48, Color(0x2E, 0xA0, 0x43)), 100, 8, null)
        drawImage(AppIcon.render(48, Color(0x2E, 0xA0, 0x43)), 300, 8, null)
        dispose()
    }
    ImageIO.write(strip, "png", File(out, "icons.png"))
    manager.shutdown()
    exitProcess(0)
}

private fun findTable(c: Container): javax.swing.JTable? {
    for (child in c.components) {
        if (child is javax.swing.JTable) return child
        if (child is Container) findTable(child)?.let { return it }
    }
    return null
}

private fun snapshot(c: Container, file: File) {
    val scale = 1.5
    val img = BufferedImage((c.width * scale).toInt(), (c.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.scale(scale, scale)
    c.paint(g)
    g.dispose()
    ImageIO.write(img, "png", file)
}
