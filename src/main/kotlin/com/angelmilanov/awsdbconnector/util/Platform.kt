package com.angelmilanov.awsdbconnector.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

enum class Os {
    WINDOWS, MAC, LINUX;

    companion object {
        /** @param name the value of the system property os.name */
        fun of(name: String): Os {
            val n = name.lowercase()
            return when {
                n.contains("mac") || n.contains("darwin") -> MAC
                n.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

object Platform {
    val os = Os.of(System.getProperty("os.name", ""))
    val isWindows = os == Os.WINDOWS
    val isMac = os == Os.MAC
    val isLinux = os == Os.LINUX

    /**
     * Starts every program the application runs (Session Manager plugin, AWS CLI, installers, …).
     * Tests replace it to stand in for programs that are not installed where they run.
     */
    @Volatile
    internal var launcher: (ProcessBuilder) -> Process = ProcessBuilder::start

    /** Opens browsers and file managers and fills the clipboard. Tests replace it, as they must leave the desktop alone. */
    @Volatile
    internal var desktop: DesktopShell = SystemDesktop

    fun start(builder: ProcessBuilder): Process = launcher(builder)

    /** Per-user directory in which the application keeps its (non confidential) configuration. */
    val configDir: Path by lazy { Files.createDirectories(configDir(os, System::getenv, System.getProperty("user.home"))) }

    /** Per-user directory for files that can be fetched again, such as downloaded updates. Not readable by other users. */
    val cacheDir: Path by lazy { Files.createDirectories(cacheDir(os, System::getenv, System.getProperty("user.home"))) }

    internal fun configDir(os: Os, env: (String) -> String?, home: String): Path = when (os) {
        Os.WINDOWS -> Paths.get(env("APPDATA") ?: "$home\\AppData\\Roaming", "AWSDBConnector")
        Os.MAC -> Paths.get(home, "Library", "Application Support", "AWSDBConnector")
        Os.LINUX -> Paths.get(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "awsdbconnector")
    }

    internal fun cacheDir(os: Os, env: (String) -> String?, home: String): Path = when (os) {
        Os.WINDOWS -> Paths.get(env("LOCALAPPDATA") ?: "$home\\AppData\\Local", "AWSDBConnector")
        Os.MAC -> Paths.get(home, "Library", "Caches", "AWSDBConnector")
        Os.LINUX -> Paths.get(env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache", "awsdbconnector")
    }

    /** The user's download folder, or the home directory if there is none. */
    val downloadsDir: Path get() = downloadsDir(os)

    internal fun downloadsDir(os: Os): Path {
        val home = Paths.get(System.getProperty("user.home"))
        val xdg = if (os == Os.LINUX) runQuick("xdg-user-dir", "DOWNLOAD")?.trim()?.takeIf { it.isNotEmpty() }?.let(Paths::get) else null
        return listOfNotNull(xdg, home.resolve("Downloads")).firstOrNull(Files::isDirectory) ?: home
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

    fun findAwsCli(override: String): String? = findExecutable(override, "aws", awsCliLocations(os))

    fun findSessionManagerPlugin(override: String): String? = findExecutable(override, "session-manager-plugin", pluginLocations(os))

    /** Where the installers of the AWS CLI put it. */
    internal fun awsCliLocations(os: Os): List<String> = when (os) {
        Os.WINDOWS -> listOf("C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe")
        else -> listOf("/usr/local/bin/aws", "/opt/homebrew/bin/aws", "/usr/bin/aws")
    }

    /** Where the installers of the Session Manager plugin put it. */
    internal fun pluginLocations(os: Os): List<String> = when (os) {
        Os.WINDOWS -> listOf(
            "C:\\Program Files\\Amazon\\SessionManagerPlugin\\bin\\session-manager-plugin.exe",
            "C:\\Program Files (x86)\\Amazon\\SessionManagerPlugin\\bin\\session-manager-plugin.exe",
        )
        else -> listOf(
            "/usr/local/sessionmanagerplugin/bin/session-manager-plugin",
            "/usr/local/bin/session-manager-plugin",
            "/opt/homebrew/bin/session-manager-plugin",
            "/usr/bin/session-manager-plugin",
        )
    }

    /**
     * Prepares an argument that contains double quotes (JSON) for [ProcessBuilder].
     * On Windows the JVM does not escape embedded quotes, so they have to be backslash-escaped
     * for the child's command line parser. The argument must not contain whitespace.
     */
    fun jsonArg(json: String, os: Os = this.os): String {
        require(json.none { it.isWhitespace() }) { "JSON argument must not contain whitespace" }
        return if (os == Os.WINDOWS) json.replace("\"", "\\\"") else json
    }

    /** Runs a short command and returns its output, or null if it failed or timed out. */
    fun runQuick(vararg cmd: String, timeoutSeconds: Long = 5): String? = try {
        val p = start(ProcessBuilder(*cmd).redirectErrorStream(true))
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (p.waitFor(timeoutSeconds, TimeUnit.SECONDS) && p.exitValue() == 0) out else {
            p.destroyForcibly(); null
        }
    } catch (_: Exception) {
        null
    }

    /** Whether the operating system is currently using a dark colour scheme. */
    fun systemIsDark(os: Os = this.os): Boolean = when (os) {
        Os.WINDOWS -> runQuick(
            "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        )?.contains("0x0") ?: false
        Os.MAC -> runQuick("defaults", "read", "-g", "AppleInterfaceStyle")?.contains("Dark", true) ?: false
        Os.LINUX -> runQuick("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")?.contains("dark", true)
            ?: runQuick("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")?.contains("dark", true)
            ?: false
    }
}
