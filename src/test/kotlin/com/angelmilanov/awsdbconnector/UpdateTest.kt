package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.AppVersion
import com.angelmilanov.awsdbconnector.update.Asset
import com.angelmilanov.awsdbconnector.update.InstallKind
import com.angelmilanov.awsdbconnector.update.Installer
import com.angelmilanov.awsdbconnector.update.Release
import com.angelmilanov.awsdbconnector.update.ReleaseSource
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.update.UpdateState
import com.angelmilanov.awsdbconnector.update.download
import com.angelmilanov.awsdbconnector.update.parseRelease
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import com.angelmilanov.awsdbconnector.util.Platform
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {
    private val dir = Files.createTempDirectory("awsdbc-update-test")
    private val store = ConfigStore(dir.resolve("config.json"))
    private val notices = CopyOnWriteArrayList<String>()
    private val notifications = Notifications { title, text, _ -> notices += "$title: $text" }

    private fun version(text: String) = AppVersion.parse(text)!!

    private fun release(tag: String) = Release(
        version(tag), tag, "notes", "https://github.com/angel-milanov/AWS-DB-Connector/releases/tag/$tag",
        listOf("windows-x64.msi", "windows-x64.zip", "windows-x64.exe", "linux-arm64.deb", "macos-arm64.dmg")
            .map { Asset("AWSDBConnector-$tag-$it", "https://github.com/x", 1, "0".repeat(64)) },
    )

    private fun service(installed: String, kind: InstallKind = InstallKind.WINDOWS_INSTALLER, platform: String = "windows-x64", source: ReleaseSource) =
        UpdateService(store, ActivityLog(), notifications, AppInstall(version(installed), Paths.get("app"), kind, platform), source)

    @Test
    fun `versions are compared numerically and tag suffixes are ignored`() {
        assertTrue(version("v1.10.0") > version("1.9.3"))
        assertTrue(version("1.2") < version("1.2.1"))
        assertEquals(0, version("v2.0").compareTo(version("2.0.0")))
        assertEquals(0, version("v1.3.0-rc.1").compareTo(version("1.3.0")))
        assertEquals("1.3.0", version("v1.3.0-rc.1").toString())
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("1.x"))
    }

    @Test
    fun `a release is read from the GitHub answer and everything outside the repository is dropped`() {
        val sha = "90e74e350c265ff0877e1dee6885ac0355b721ce8cca416d5c6b74636345eb14"
        val downloads = "https://github.com/angel-milanov/AWS-DB-Connector/releases/download/v1.0.2"
        val release = parseRelease(
            """
            {"tag_name": "v1.0.2", "name": "Adds better support for Windows", "body": null, "draft": false, "prerelease": false,
             "html_url": "https://github.com/angel-milanov/AWS-DB-Connector/releases/tag/v1.0.2", "author": {"login": "someone"},
             "assets": [
               {"name": "AWSDBConnector-v1.0.2-windows-x64.msi", "size": 89568277, "digest": "sha256:$sha",
                "browser_download_url": "$downloads/AWSDBConnector-v1.0.2-windows-x64.msi"},
               {"name": "SHA256SUMS.txt", "size": 936, "digest": null, "browser_download_url": "$downloads/SHA256SUMS.txt"},
               {"name": "elsewhere.msi", "size": 1, "browser_download_url": "https://example.com/elsewhere.msi"},
               {"name": "..\\evil.msi", "size": 1, "browser_download_url": "$downloads/evil.msi"}
             ]}
            """,
        )!!
        assertEquals("1.0.2", release.version.toString())
        assertEquals("", release.notes)
        assertEquals(listOf("AWSDBConnector-v1.0.2-windows-x64.msi", "SHA256SUMS.txt"), release.assets.map { it.name })
        assertEquals(Asset("AWSDBConnector-v1.0.2-windows-x64.msi", "$downloads/AWSDBConnector-v1.0.2-windows-x64.msi", 89568277, sha), release.assets[0])
        assertNull(release.assets[1].sha256)

        assertNull(parseRelease("""{"tag_name": "v2.0.0-beta", "prerelease": true}"""))
        assertNull(parseRelease("""{"tag_name": "nightly"}"""))
        assertEquals(
            "https://github.com/angel-milanov/AWS-DB-Connector/releases",
            parseRelease("""{"tag_name": "v2.0.0", "html_url": "https://example.com/phishing"}""")!!.pageUrl,
        )
    }

    @Test
    fun `a newer release is offered with the package that fits the installation`() {
        val newer = ReleaseSource { release("v1.4.0") }
        fun offered(kind: InstallKind, platform: String) =
            assertIs<UpdateState.Available>(service("1.3.2", kind, platform, newer).check(manual = true)).update.asset?.name

        assertEquals("AWSDBConnector-v1.4.0-windows-x64.msi", offered(InstallKind.WINDOWS_INSTALLER, "windows-x64"))
        assertEquals("AWSDBConnector-v1.4.0-windows-x64.zip", offered(InstallKind.WINDOWS_PORTABLE, "windows-x64"))
        assertEquals("AWSDBConnector-v1.4.0-linux-arm64.deb", offered(InstallKind.LINUX_DEB, "linux-arm64"))
        assertEquals("AWSDBConnector-v1.4.0-macos-arm64.dmg", offered(InstallKind.MAC_DMG, "macos-arm64"))
        assertNull(offered(InstallKind.MAC_DMG, "macos-x64"), "the release has no package for this platform")
    }

    @Test
    fun `the same, an older or no release is not an update`() {
        assertEquals(UpdateState.UpToDate, service("1.4.0") { release("v1.4.0") }.check(manual = true))
        assertEquals(UpdateState.UpToDate, service("1.4.1") { release("v1.4.0") }.check(manual = true))
        assertEquals(UpdateState.UpToDate, service("1.4.0") { null }.check(manual = true))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `an automatic check notifies once per version and a manual check never does`() {
        val service = service("1.3.2") { release("v1.4.0") }
        service.check(manual = false)
        service.check(manual = false)
        assertEquals(1, notices.size, notices.toString())
        assertTrue("1.4.0" in notices[0])

        notices.clear()
        service("1.3.2") { release("v1.4.0") }.check(manual = true)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a skipped version is only offered again when the user asks`() {
        val service = service("1.3.2") { release("v1.4.0") }
        service.skip(assertIs<UpdateState.Available>(service.check(manual = false)).update)
        assertEquals("v1.4.0", store.settings.skippedUpdate)
        assertNull(service.available)

        assertEquals(UpdateState.UpToDate, service.check(manual = false))
        assertIs<UpdateState.Available>(service.check(manual = true))
        assertIs<UpdateState.Available>(service("1.3.2") { release("v1.4.1") }.check(manual = false), "a later version is offered")
    }

    @Test
    fun `a failed automatic check keeps a known update and a failed manual check is reported`() {
        var online = true
        val service = service("1.3.2") { if (online) release("v1.4.0") else throw IOException("no network") }
        service.check(manual = false)
        online = false
        assertIs<UpdateState.Available>(service.check(manual = false))
        assertEquals(UpdateState.Failed("no network"), service.check(manual = true))
        assertEquals(UpdateState.Failed("no network"), service("1.3.2") { throw IOException("no network") }.check(manual = false))
    }

    @Test
    fun `a download is only kept if its checksum matches`() {
        val content = ByteArray(300_000) { (it % 251).toByte() }
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/package.zip") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/package.zip"
            val target = dir.resolve("downloads")
            var reported = 0L

            val file = download(Asset("package.zip", url, content.size.toLong(), sha), target) { done, _ -> reported = done }
            assertEquals(target.resolve("package.zip"), file)
            assertTrue(content.contentEquals(Files.readAllBytes(file)))
            assertEquals(content.size.toLong(), reported)
            Files.delete(file)

            fun rejected(asset: Asset) {
                assertFailsWith<IOException> { download(asset, target) { _, _ -> } }
                assertEquals(emptyList(), Files.list(target).use { it.toList() }, "nothing is left behind")
            }
            rejected(Asset("package.zip", url, content.size.toLong(), "0".repeat(64))) // tampered or damaged
            rejected(Asset("package.zip", url, content.size.toLong(), null)) // no checksum published
            rejected(Asset("package.zip", url, 1000, sha)) // more data than announced
            rejected(Asset("package.zip", url.replace("package", "missing"), content.size.toLong(), sha)) // 404
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------ helper scripts (macOS, Linux)

    private val sh = Platform.findExecutable("", "sh", listOf("/bin/sh"))

    /** A path as the shell expects it, also when the tests run on Windows (Git Bash). */
    private fun Path.sh() = toString().replace('\\', '/')

    private fun tool(name: String, script: String): Path {
        val file = Files.createDirectories(dir.resolve("bin")).resolve(name)
        Files.writeString(file, "#!/bin/sh\n$script\n")
        file.toFile().setExecutable(true)
        return file
    }

    /**
     * Runs [script] with the stand-ins of [tool] first on the PATH. The application passes the script with -c;
     * here it is read from a file, because Java on Windows does not escape the quotes in an argument.
     */
    private fun runScript(script: String, vararg args: String): Int {
        val file = Files.writeString(dir.resolve("script.sh"), script)
        val output = dir.resolve("script.out")
        val builder = ProcessBuilder(sh, file.sh(), *args).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment().apply {
            put("PATH", dir.resolve("bin").toString() + File.pathSeparator + System.getenv("PATH"))
            put("TMPDIR", Files.createDirectories(dir.resolve("tmp")).sh())
            put("LOG", dir.resolve("log").sh())
        }
        val process = builder.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw AssertionError("The script did not end:\n" + Files.readString(output))
        }
        print(Files.readString(output))
        return process.exitValue()
    }

    @Test
    fun `the macOS script swaps the application bundle in and keeps the old one if that fails`() {
        if (sh == null) return // Windows without a POSIX shell: nothing to run the script with
        // Stand-ins: a "disk image" is a folder here, mounting it copies its content to the mount point.
        tool("hdiutil", $$"""if [ "$1" = attach ]; then for mnt; do :; done; cp -R "$2/." "$mnt"; fi""")
        tool("ditto", $$"""cp -R "$1" "$2"""")
        tool("open", $$"""echo "open $1" >> "$LOG"""")
        val gone = "2147483647" // no such process: nothing to wait for

        fun bundle(parent: String, version: String): Path {
            val app = Files.createDirectories(dir.resolve(parent).resolve("AWSDBConnector.app"))
            Files.writeString(Files.createDirectories(app.resolve("Contents")).resolve("version"), version)
            return app
        }
        fun installed() = Files.list(dir.resolve("Applications")).use { apps -> apps.map { it.fileName.toString() }.toList() }
        val app = bundle("Applications", "old")
        val log = dir.resolve("log")

        bundle("image", "new")
        runScript(Installer.MAC_SCRIPT, gone, dir.resolve("image").sh(), app.sh())
        assertEquals("new", Files.readString(app.resolve("Contents/version")))
        assertEquals(listOf("AWSDBConnector.app"), installed(), "no temporary copies are left behind")
        assertEquals(listOf("open ${app.sh()}"), Files.readAllLines(log), "the new version is started")

        Files.delete(log)
        val broken = Files.createDirectories(dir.resolve("broken image")) // contains no application
        runScript(Installer.MAC_SCRIPT, gone, broken.sh(), app.sh())
        assertEquals("new", Files.readString(app.resolve("Contents/version")), "the installed version is untouched")
        assertEquals(listOf("AWSDBConnector.app"), installed())
        assertEquals(listOf("open ${broken.sh()}", "open ${app.sh()}"), Files.readAllLines(log), "the image is opened to install by hand")
    }

    @Test
    fun `the Linux script starts the new version once the old process is gone`() {
        if (sh == null) return
        val launcher = tool("AWSDBConnector", $$"""echo started >> "$LOG"""")
        assertEquals(0, runScript(Installer.LINUX_RESTART_SCRIPT, "2147483647", launcher.sh()))
        assertEquals(listOf("started"), Files.readAllLines(dir.resolve("log")))
    }
}
