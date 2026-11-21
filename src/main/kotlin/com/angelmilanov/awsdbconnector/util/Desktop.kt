package com.angelmilanov.awsdbconnector.util

import java.awt.Desktop
import java.awt.Image
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseListener
import java.net.URI
import java.nio.file.Path

/** What the application asks the desktop environment to do for it. */
interface DesktopShell {
    /** Opens [url] in the browser. */
    fun browse(url: String)

    /** Shows [file] in the file manager. */
    fun reveal(file: Path)

    /** Puts [text] on the clipboard. */
    fun copy(text: String)
}

@ExcludeFromJacocoGeneratedReport("Opens the browser and the file manager of the real desktop, and changes its clipboard")
object SystemDesktop : DesktopShell {
    override fun browse(url: String) = Desktop.getDesktop().browse(URI(url))

    override fun reveal(file: Path) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(file.toFile())
        else desktop.open(file.parent.toFile())
    }

    override fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** The icon in the system tray, as far as the application uses it. */
interface TrayHandle {
    var image: Image
    var toolTip: String?

    /** The native menu of the icon. Not used on Windows, where the application shows a menu of its own. */
    var popupMenu: PopupMenu?
    fun addMouseListener(listener: MouseListener)
    fun displayMessage(title: String, text: String, type: TrayIcon.MessageType)
    fun remove()
}

@ExcludeFromJacocoGeneratedReport("Needs the system tray of a real desktop, which a build server does not have")
private class SystemTrayHandle(private val icon: TrayIcon) : TrayHandle {
    override var image: Image
        get() = icon.image
        set(value) {
            icon.image = value
        }
    override var toolTip: String?
        get() = icon.toolTip
        set(value) {
            icon.toolTip = value
        }
    override var popupMenu: PopupMenu?
        get() = icon.popupMenu
        set(value) {
            icon.popupMenu = value
        }

    override fun addMouseListener(listener: MouseListener) = icon.addMouseListener(listener)

    override fun displayMessage(title: String, text: String, type: TrayIcon.MessageType) = icon.displayMessage(title, text, type)

    override fun remove() = SystemTray.getSystemTray().remove(icon)
}

/** Puts an icon into the system tray. Null where there is none, for example on GNOME without an extension. */
@ExcludeFromJacocoGeneratedReport("Needs the system tray of a real desktop, which a build server does not have")
fun systemTray(image: Image, toolTip: String): TrayHandle? {
    if (!SystemTray.isSupported()) return null
    val icon = TrayIcon(image, toolTip)
    icon.isImageAutoSize = true
    try {
        SystemTray.getSystemTray().add(icon)
    } catch (_: Exception) {
        // The tray went away between the two calls: the application runs without a visible icon.
    }
    return SystemTrayHandle(icon)
}
