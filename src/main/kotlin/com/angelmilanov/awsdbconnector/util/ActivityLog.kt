package com.angelmilanov.awsdbconnector.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/** Small in-memory activity log shown in the UI. Never receives tokens or credentials. */
class ActivityLog(private val capacity: Int = 2000) {
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun log(source: String, message: String) {
        val line = "${LocalTime.now().format(fmt)}  [$source] ${message.trimEnd()}"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > capacity) lines.removeFirst()
        }
        listeners.forEach { it(line) }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }

    fun addListener(l: (String) -> Unit) {
        listeners += l
    }
}
