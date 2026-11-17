package com.angelmilanov.awsdbconnector.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

object Platform {
    private val osName = System.getProperty("os.name", "").lowercase()
    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac") || osName.contains("darwin")
    val isLinux = !isWindows && !isMac

    /** Per-user directory in which the application keeps its (non confidential) configuration. */
    val configDir: Path by lazy {
        val home = System.getProperty("user.home")
        val dir = when {
            isWindows -> Paths.get(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "AWSDBConnector")
            isMac -> Paths.get(home, "Library", "Application Support", "AWSDBConnector")
            else -> Paths.get(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "awsdbconnector")
        }
        Files.createDirectories(dir)
        dir
    }

    /** Looks for an executable: explicit override first, then PATH, then well known install locations. */
    fun findExecutable(override: String, name: String, wellKnown: List<String>): String? {
        if (override.isNotBlank()) return override.trim().takeIf { File(it).isFile }
        val exts = if (isWindows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        for (dir in path) for (ext in exts) {
            val f = File(dir.trim('"'), name + ext)
            if (f.isFile) return f.absolutePath
        }
        return wellKnown.firstOrNull { File(it).isFile }
    }

    fun findAwsCli(override: String): String? = findExecutable(
        override, "aws",
        if (isWindows) listOf("C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe")
        else listOf("/usr/local/bin/aws", "/opt/homebrew/bin/aws", "/usr/bin/aws"),
    )

    fun findSessionManagerPlugin(override: String): String? = findExecutable(
        override, "session-manager-plugin",
        if (isWindows) listOf(
            "C:\\Program Files\\Amazon\\SessionManagerPlugin\\bin\\session-manager-plugin.exe",
            "C:\\Program Files (x86)\\Amazon\\SessionManagerPlugin\\bin\\session-manager-plugin.exe",
        ) else listOf(
            "/usr/local/sessionmanagerplugin/bin/session-manager-plugin",
            "/usr/local/bin/session-manager-plugin",
            "/opt/homebrew/bin/session-manager-plugin",
            "/usr/bin/session-manager-plugin",
        ),
    )

    /**
     * Prepares an argument that contains double quotes (JSON) for [ProcessBuilder].
     * On Windows the JVM does not escape embedded quotes, so they have to be backslash-escaped
     * for the child's command line parser. The argument must not contain whitespace.
     */
    fun jsonArg(json: String): String {
        require(json.none { it.isWhitespace() }) { "JSON argument must not contain whitespace" }
        return if (isWindows) json.replace("\"", "\\\"") else json
    }

    /** Runs a short command and returns its output, or null if it failed or timed out. */
    fun runQuick(vararg cmd: String, timeoutSeconds: Long = 5): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (p.waitFor(timeoutSeconds, TimeUnit.SECONDS) && p.exitValue() == 0) out else {
            p.destroyForcibly(); null
        }
    } catch (_: Exception) {
        null
    }

    /** Whether the operating system is currently using a dark colour scheme. */
    fun systemIsDark(): Boolean = when {
        isWindows -> runQuick(
            "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        )?.contains("0x0") ?: false
        isMac -> runQuick("defaults", "read", "-g", "AppleInterfaceStyle")?.contains("Dark", true) ?: false
        else -> runQuick("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")?.contains("dark", true)
            ?: runQuick("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")?.contains("dark", true)
            ?: false
    }
}
