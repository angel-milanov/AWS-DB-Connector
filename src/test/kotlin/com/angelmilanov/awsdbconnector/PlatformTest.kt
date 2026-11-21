package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.NoticeLevel
import com.angelmilanov.awsdbconnector.util.Os
import com.angelmilanov.awsdbconnector.util.OsNotifier
import com.angelmilanov.awsdbconnector.ui.AppIcon
import com.angelmilanov.awsdbconnector.util.Platform
import com.angelmilanov.awsdbconnector.util.systemTray
import java.awt.Color
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.event.MouseAdapter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlatformTest {
    private val dir = TestEnv.tempDir("platform")
    private val java = Paths.get(System.getProperty("java.home"), "bin", if (Platform.isWindows) "java.exe" else "java").toString()
    private var programs: FakePrograms? = null

    private fun programs(respond: (List<String>) -> Process) = FakePrograms(respond).also { programs = it }

    @AfterTest
    fun cleanup() {
        programs?.close()
    }

    // ------------------------------------------------------------------ operating system and directories

    @Test
    fun `the operating system is recognised by its name`() {
        assertEquals(Os.WINDOWS, Os.of("Windows 11"))
        assertEquals(Os.MAC, Os.of("Mac OS X"))
        assertEquals(Os.MAC, Os.of("Darwin"))
        assertEquals(Os.LINUX, Os.of("Linux"))
        assertEquals(Os.LINUX, Os.of(""))
        assertEquals(1, listOf(Platform.isWindows, Platform.isMac, Platform.isLinux).count { it }, "exactly one of them is running the tests")
        assertEquals(Platform.os, Os.of(System.getProperty("os.name")))
    }

    @Test
    fun `configuration and cache are kept where each system expects them`() {
        val env = mapOf("APPDATA" to "roaming", "LOCALAPPDATA" to "local", "XDG_CONFIG_HOME" to "xdg-config", "XDG_CACHE_HOME" to "xdg-cache")
        val blank = mapOf("XDG_CONFIG_HOME" to " ", "XDG_CACHE_HOME" to "")

        assertEquals(Paths.get("roaming", "AWSDBConnector"), Platform.configDir(Os.WINDOWS, env::get, "home"))
        assertEquals(Paths.get("home\\AppData\\Roaming", "AWSDBConnector"), Platform.configDir(Os.WINDOWS, { null }, "home"))
        assertEquals(Paths.get("home", "Library", "Application Support", "AWSDBConnector"), Platform.configDir(Os.MAC, env::get, "home"))
        assertEquals(Paths.get("xdg-config", "awsdbconnector"), Platform.configDir(Os.LINUX, env::get, "home"))
        assertEquals(Paths.get("home/.config", "awsdbconnector"), Platform.configDir(Os.LINUX, blank::get, "home"))

        assertEquals(Paths.get("local", "AWSDBConnector"), Platform.cacheDir(Os.WINDOWS, env::get, "home"))
        assertEquals(Paths.get("home\\AppData\\Local", "AWSDBConnector"), Platform.cacheDir(Os.WINDOWS, { null }, "home"))
        assertEquals(Paths.get("home", "Library", "Caches", "AWSDBConnector"), Platform.cacheDir(Os.MAC, env::get, "home"))
        assertEquals(Paths.get("xdg-cache", "awsdbconnector"), Platform.cacheDir(Os.LINUX, env::get, "home"))
        assertEquals(Paths.get("home/.cache", "awsdbconnector"), Platform.cacheDir(Os.LINUX, blank::get, "home"))

        TestEnv.requireIsolated()
        assertTrue(Files.isDirectory(Platform.configDir), "the directory is created on first use")
        assertTrue(Files.isDirectory(Platform.cacheDir))
    }

    @Test
    fun `downloads go to the download folder, or to the home directory if there is none`() {
        TestEnv.requireIsolated()
        val home = Paths.get(System.getProperty("user.home"))
        val downloads = home.resolve("Downloads")
        Files.deleteIfExists(downloads)
        val programs = programs { FakeProcess(exitCode = 1) }

        assertEquals(home, Platform.downloadsDir(Os.WINDOWS))
        assertEquals(home, Platform.downloadsDir, "the same on the system that runs the tests")
        Files.createDirectories(downloads)
        assertEquals(downloads, Platform.downloadsDir(Os.MAC))
        assertEquals(emptyList(), programs.commands.filter { it.first() != "xdg-user-dir" }, "only Linux has a program to ask")

        // Linux: the folder may have another name, in the language of the user.
        programs.started.clear()
        assertEquals(downloads, Platform.downloadsDir(Os.LINUX), "xdg-user-dir failed")
        assertEquals(listOf(listOf("xdg-user-dir", "DOWNLOAD")), programs.commands)
        programs.respond = { FakeProcess("$dir\n") }
        assertEquals(dir, Platform.downloadsDir(Os.LINUX))
        programs.respond = { FakeProcess("\n") }
        assertEquals(downloads, Platform.downloadsDir(Os.LINUX), "xdg-user-dir did not know")
        programs.respond = { FakeProcess(dir.resolve("gone").toString()) }
        assertEquals(downloads, Platform.downloadsDir(Os.LINUX), "xdg-user-dir named a folder that does not exist")
        Files.delete(downloads)
    }

    // ------------------------------------------------------------------ finding programs

    @Test
    fun `a program is looked for at the given path, on the PATH and where its installer puts it`() {
        val tool = Files.writeString(dir.resolve("tool"), "").toString()
        val missing = dir.resolve("missing").toString()

        assertEquals(tool, Platform.findExecutable("  $tool ", "anything", emptyList()), "the path from the settings wins")
        assertNull(Platform.findExecutable(missing, "java", listOf(tool)), "a wrong path in the settings is not replaced by a guess")

        val onPath = assertNotNull(Platform.findExecutable("", if (Platform.isWindows) "cmd" else "sh", emptyList()))
        assertTrue(Files.isRegularFile(Paths.get(onPath)), onPath)

        assertEquals(tool, Platform.findExecutable("", "awsdbc-no-such-program", listOf(missing, tool)))
        assertNull(Platform.findExecutable("", "awsdbc-no-such-program", listOf(missing)))
    }

    @Test
    fun `the AWS tools are looked for in the install locations of the operating system`() {
        val tool = Files.writeString(dir.resolve("tool"), "").toString()
        assertEquals(tool, Platform.findAwsCli(tool))
        assertEquals(tool, Platform.findSessionManagerPlugin(tool))

        assertEquals(listOf("C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe"), Platform.awsCliLocations(Os.WINDOWS))
        assertTrue(Platform.pluginLocations(Os.WINDOWS).all { it.endsWith("\\session-manager-plugin.exe") })
        for (os in listOf(Os.MAC, Os.LINUX)) {
            assertTrue(Platform.awsCliLocations(os).all { it.startsWith("/") && it.endsWith("/aws") })
            assertTrue(Platform.pluginLocations(os).all { it.startsWith("/") && it.endsWith("/session-manager-plugin") })
            assertTrue("/opt/homebrew/bin/aws" in Platform.awsCliLocations(os))
        }
    }

    @Test
    fun `quotes in a JSON argument are escaped on Windows only`() {
        val json = """{"Target":"i-0123456789abcdef0"}"""
        assertEquals("""{\"Target\":\"i-0123456789abcdef0\"}""", Platform.jsonArg(json, Os.WINDOWS))
        assertEquals(json, Platform.jsonArg(json, Os.LINUX))
        assertEquals(json, Platform.jsonArg(json, Os.MAC))
        assertEquals(Platform.jsonArg(json, Platform.os), Platform.jsonArg(json))
        assertFailsWith<IllegalArgumentException> { Platform.jsonArg("""{"Target": "i-0123456789abcdef0"}""") }
    }

    // ------------------------------------------------------------------ running programs

    @Test
    fun `a short command returns its output, and null if it fails, hangs or cannot be started`() {
        val version = assertNotNull(Platform.runQuick(java, "-version"), "a real program, started for real")
        assertTrue("version" in version, version)
        assertNull(Platform.runQuick(java, "--awsdbc-no-such-option"), "exit code other than 0")
        assertNull(Platform.runQuick(dir.resolve("awsdbc-no-such-program").toString()))

        val hanging = FakeProcess("some output", exitCode = null, timesOut = true)
        programs { hanging }
        assertNull(Platform.runQuick("slow", timeoutSeconds = 1))
        assertTrue(hanging.destroyed, "a program that takes too long is ended")
    }

    @Test
    fun `every program is started through the launcher`() {
        val programs = programs { FakeProcess("hello\n") }
        assertEquals("hello\n", Platform.runQuick("echo", "hello"))
        assertEquals(listOf(listOf("echo", "hello")), programs.commands)
        assertTrue(programs.started.single().redirectErrorStream())

        programs.respond = { throw IOException("Cannot run program") }
        assertFailsWith<IOException> { Platform.start(ProcessBuilder("anything")) }
    }

    @Test
    fun `the dark mode of the system is read with the tool of each system`() {
        val answers = mutableMapOf<String, Process>()
        val programs = programs { command -> answers[command.last()] ?: FakeProcess(exitCode = 1) }

        answers["AppsUseLightTheme"] = FakeProcess("    AppsUseLightTheme    REG_DWORD    0x0\n")
        assertTrue(Platform.systemIsDark(Os.WINDOWS))
        assertEquals("reg", programs.commands.single().first())
        answers["AppsUseLightTheme"] = FakeProcess("    AppsUseLightTheme    REG_DWORD    0x1\n")
        assertFalse(Platform.systemIsDark(Os.WINDOWS))
        answers.clear()
        assertFalse(Platform.systemIsDark(Os.WINDOWS), "the value cannot be read")

        answers["AppleInterfaceStyle"] = FakeProcess("Dark\n")
        assertTrue(Platform.systemIsDark(Os.MAC))
        answers.clear()
        assertFalse(Platform.systemIsDark(Os.MAC), "the setting does not exist in light mode")

        answers["color-scheme"] = FakeProcess("'prefer-dark'\n")
        assertTrue(Platform.systemIsDark(Os.LINUX))
        answers["color-scheme"] = FakeProcess("'default'\n")
        assertFalse(Platform.systemIsDark(Os.LINUX))
        answers.clear()
        answers["gtk-theme"] = FakeProcess("'Adwaita-dark'\n")
        assertTrue(Platform.systemIsDark(Os.LINUX), "older desktops only have a theme name")
        answers.clear()
        assertFalse(Platform.systemIsDark(Os.LINUX), "no gsettings at all")
        assertEquals(listOf("gsettings", "gsettings"), programs.commands.takeLast(2).map { it.first() })

        assertFalse(Platform.systemIsDark(), "the system that runs the tests is asked with its own tool")
    }

    // ------------------------------------------------------------------ notifications

    private val log = ActivityLog()
    private val tray = FakeTray()

    private fun notifier(os: Os, enabled: Boolean = true) = OsNotifier({ enabled }, log, os).also { it.tray = tray }

    @Test
    fun `notifications are logged, and shown only while they are switched on`() {
        val programs = programs { FakeProcess() }
        notifier(Os.LINUX, enabled = false).show("Orders", "Connection lost", NoticeLevel.WARNING)
        OsNotifier({ false }, log).show("Orders", "Connected", NoticeLevel.INFO)
        assertEquals(2, log.snapshot().size)
        assertTrue(log.snapshot().first().endsWith("[notify] Orders – Connection lost"), log.snapshot().first())
        assertTrue(programs.commands.isEmpty() && tray.messages.isEmpty())
    }

    @Test
    fun `Windows shows notifications from the tray icon`() {
        val programs = programs { FakeProcess() }
        val notifier = notifier(Os.WINDOWS)
        notifier.show("Orders", "Connected", NoticeLevel.INFO)
        notifier.show("Orders", "Connection lost", NoticeLevel.WARNING)
        notifier.show("Orders", "Connection failed", NoticeLevel.ERROR)
        await("three messages") { tray.messages.size == 3 }
        assertEquals(
            setOf("INFO Orders: Connected", "WARNING Orders: Connection lost", "ERROR Orders: Connection failed"),
            tray.messages.toSet(),
        )
        assertTrue(programs.commands.isEmpty())
    }

    @Test
    fun `macOS shows notifications with osascript`() {
        val programs = programs { FakeProcess() }
        notifier(Os.MAC).show("The \"Orders\" database", "C:\\path is \"gone\"", NoticeLevel.ERROR)
        await("osascript") { programs.commands.size == 1 }
        assertEquals(
            listOf("osascript", "-e", """display notification "C:\\path is \"gone\"" with title "The \"Orders\" database""""),
            programs.commands.single(),
        )

        programs.respond = { FakeProcess(exitCode = 1) }
        notifier(Os.MAC).show("Orders", "Connected", NoticeLevel.INFO)
        await("fallback") { tray.messages.size == 1 }
        assertEquals("INFO Orders: Connected", tray.messages.single(), "the tray icon is used where osascript fails")
    }

    @Test
    fun `Linux shows notifications with notify-send`() {
        val programs = programs { FakeProcess() }
        notifier(Os.LINUX).show("Orders", "Connection failed", NoticeLevel.ERROR)
        await("notify-send") { programs.commands.size == 1 }
        assertEquals(listOf("notify-send", "-a", "AWS DB Connector", "-u", "critical", "Orders", "Connection failed"), programs.commands[0])
        notifier(Os.LINUX).show("Orders", "Connected", NoticeLevel.INFO)
        await("notify-send") { programs.commands.size == 2 }
        assertEquals("normal", programs.commands[1][4])
        assertTrue(tray.messages.isEmpty())

        programs.respond = { throw IOException("notify-send is not installed") }
        notifier(Os.LINUX).show("Orders", "Connection lost", NoticeLevel.WARNING)
        await("fallback") { tray.messages.size == 1 }
        assertEquals("WARNING Orders: Connection lost", tray.messages.single())
    }

    @Test
    fun `a notification that cannot be shown is dropped`() {
        val programs = programs { FakeProcess(exitCode = 1) }
        val broken = OsNotifier({ true }, log, Os.LINUX).also { it.tray = FakeTray(broken = true) }
        broken.show("Orders", "Connected", NoticeLevel.INFO)
        await("first attempt") { programs.commands.size == 1 }
        val none = OsNotifier({ true }, log, Os.MAC) // a desktop without a system tray
        none.show("Orders", "Connected", NoticeLevel.INFO)
        await("second attempt") { programs.commands.size == 2 }
        assertEquals(2, log.snapshot().size, "they are in the activity log all the same")
    }

    // ------------------------------------------------------------------ the real system tray

    /**
     * The one piece of the desktop the tests can touch without getting in the way: an icon that is in the tray for a
     * moment. On a build server there is no tray, and the application has to cope with exactly that.
     */
    @Test
    fun `the icon is put into the system tray where the desktop has one`() {
        val handle = systemTray(AppIcon.tray(null), "AWS DB Connector (test)")
        if (!SystemTray.isSupported()) return assertNull(handle)

        val tray = SystemTray.getSystemTray()
        try {
            assertNotNull(handle)
            assertEquals("AWS DB Connector (test)", tray.trayIcons.last().toolTip)
            handle.toolTip = "AWS DB Connector – 1 of 2 connected"
            assertEquals("AWS DB Connector – 1 of 2 connected", tray.trayIcons.last().toolTip)
            val badged = AppIcon.tray(Color.RED)
            handle.image = badged
            assertSame(badged, handle.image)
            handle.popupMenu = PopupMenu()
            assertSame(handle.popupMenu, tray.trayIcons.last().popupMenu)
            handle.addMouseListener(object : MouseAdapter() {})
            assertEquals(1, tray.trayIcons.last().mouseListeners.size)
        } finally {
            val before = tray.trayIcons.size
            handle?.remove()
            assertEquals(before - 1, tray.trayIcons.size)
        }
    }

    // ------------------------------------------------------------------ activity log

    @Test
    fun `the activity log keeps the newest lines and tells its listeners`() {
        val activity = ActivityLog(capacity = 3)
        val heard = mutableListOf<String>()
        activity.addListener { heard += it }
        for (n in 1..5) activity.log("app", "line $n  \n")

        assertEquals(listOf("line 3", "line 4", "line 5"), activity.snapshot().map { it.substringAfter("] ") })
        assertEquals(5, heard.size)
        assertTrue(Regex("""\d\d:\d\d:\d\d {2}\[app] line 5""").matches(heard.last()), heard.last())
        activity.clear()
        assertTrue(activity.snapshot().isEmpty())
    }
}
