package com.angelmilanov.awsdbconnector.util

import java.awt.TrayIcon

enum class NoticeLevel { INFO, WARNING, ERROR }

fun interface Notifications {
    fun show(title: String, text: String, level: NoticeLevel)
}

/** Desktop notifications using the native mechanism of the respective operating system. */
class OsNotifier(
    private val enabled: () -> Boolean,
    private val log: ActivityLog,
    private val os: Os = Platform.os,
) : Notifications {
    @Volatile
    var tray: TrayHandle? = null

    override fun show(title: String, text: String, level: NoticeLevel) {
        log.log("notify", "$title – $text")
        if (!enabled()) return
        Thread.ofVirtual().start {
            val delivered = when (os) {
                Os.MAC -> mac(title, text)
                Os.LINUX -> linux(title, text, level)
                Os.WINDOWS -> false
            }
            if (!delivered) tray(title, text, level) // Windows toast (and fallback elsewhere)
        }
    }

    private fun tray(title: String, text: String, level: NoticeLevel) {
        val type = when (level) {
            NoticeLevel.INFO -> TrayIcon.MessageType.INFO
            NoticeLevel.WARNING -> TrayIcon.MessageType.WARNING
            NoticeLevel.ERROR -> TrayIcon.MessageType.ERROR
        }
        runCatching { tray?.displayMessage(title, text, type) }
    }

    private fun mac(title: String, text: String): Boolean {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return Platform.runQuick(
            "osascript", "-e", "display notification \"${esc(text)}\" with title \"${esc(title)}\"",
        ) != null
    }

    private fun linux(title: String, text: String, level: NoticeLevel): Boolean {
        val urgency = if (level == NoticeLevel.ERROR) "critical" else "normal"
        return Platform.runQuick("notify-send", "-a", "AWS DB Connector", "-u", urgency, title, text) != null
    }
}
