package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.AppVersion
import com.angelmilanov.awsdbconnector.update.GitHubAsset
import com.angelmilanov.awsdbconnector.update.GitHubRelease
import com.angelmilanov.awsdbconnector.update.Asset
import com.angelmilanov.awsdbconnector.update.GitHubReleases
import com.angelmilanov.awsdbconnector.update.InstallKind
import com.angelmilanov.awsdbconnector.update.Release
import com.angelmilanov.awsdbconnector.update.ReleaseSource
import com.angelmilanov.awsdbconnector.update.Update
import com.angelmilanov.awsdbconnector.update.UpdateService
import com.angelmilanov.awsdbconnector.update.UpdateState
import com.angelmilanov.awsdbconnector.update.parseRelease
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.Notifications
import com.angelmilanov.awsdbconnector.util.Os
import com.angelmilanov.awsdbconnector.util.Platform
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {
    private val dir = TestEnv.tempDir("releases")
    private val store = ConfigStore(dir.resolve("config.json"))
    private val notices = CopyOnWriteArrayList<String>()
    private val notifications = Notifications { title, text, _ -> notices += "$title: $text" }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { it.start() }
    private val address = "http://127.0.0.1:${server.address.port}"
    private var programs: FakePrograms? = null

    private fun version(text: String) = AppVersion.parse(text)!!

    @AfterTest
    fun cleanup() {
        server.stop(0)
        programs?.close()
    }

    // ------------------------------------------------------------------ GitHub

    @Test
    fun `the latest release is asked from the GitHub API`() {
        var status = 200
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        server.createContext("/repos/angel-milanov/AWS-DB-Connector/releases/latest") { exchange ->
            headers += listOf("Accept", "User-Agent", "X-GitHub-Api-Version").associateWith { exchange.requestHeaders.getFirst(it) }
            val body = """{"tag_name": "v1.4.0", "body": "What is new", "assets": []}""".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val releases = GitHubReleases(address)

        val release = assertNotNull(releases.latest())
        assertEquals("1.4.0" to "What is new", release.version.toString() to release.notes)
        assertEquals(
            mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "AWSDBConnector-updater", "X-GitHub-Api-Version" to "2022-11-28"),
            headers.single(),
        )

        status = 404
        assertNull(releases.latest(), "nothing has been released yet")
        for (limited in listOf(403, 429)) {
            status = limited
            assertEquals(
                "GitHub is limiting requests from this network at the moment (HTTP $limited)",
                assertFailsWith<IOException> { releases.latest() }.message,
            )
        }
        status = 500
        assertEquals("GitHub answered with HTTP 500", assertFailsWith<IOException> { releases.latest() }.message)

        GitHubReleases() // the real address is only used by the installed application
    }

    @Test
    fun `a release with the least GitHub can say is still read`() {
        val downloads = "https://github.com/angel-milanov/AWS-DB-Connector/releases/download/v2.0.0"
        val release = parseRelease("""{"tag_name": "v2.0.0", "assets": [{"name": "a.zip", "browser_download_url": "$downloads/a.zip"}]}""")!!
        assertEquals(Asset("a.zip", "$downloads/a.zip", 0, null), release.assets.single())
        assertEquals("" to "https://github.com/angel-milanov/AWS-DB-Connector/releases", release.notes to release.pageUrl)
        assertNull(parseRelease("""{"tag_name": "v2.0.0", "draft": true}"""))
        assertNull(parseRelease("""{"tag_name": "v2.0.0", "assets": [{"name": "a.zip", "browser_download_url": "$downloads/a.zip", "digest": "md5:abc"}]}""")!!.assets.single().sha256)
    }

    @Test
    fun `the fields of a release are the ones GitHub documents`() {
        val downloads = "https://github.com/angel-milanov/AWS-DB-Connector/releases/download/v2.1.0"
        val published = GitHubRelease(
            tag = "v2.1.0", body = "## What's Changed", pageUrl = "https://github.com/angel-milanov/AWS-DB-Connector/releases/tag/v2.1.0",
            assets = listOf(GitHubAsset("AWSDBConnector-v2.1.0-linux-x64.deb", "$downloads/AWSDBConnector-v2.1.0-linux-x64.deb", 1234, "sha256:" + "AB".repeat(32))),
        )
        val json = Json.encodeToString(GitHubRelease.serializer(), published)
        for (field in listOf("tag_name", "html_url", "browser_download_url", "digest", "body", "size")) assertTrue("\"$field\"" in json, json)

        val release = parseRelease(json)!!
        assertEquals("v2.1.0" to "## What's Changed", release.tag to release.notes)
        assertEquals(published.pageUrl, release.pageUrl)
        assertEquals(Asset("AWSDBConnector-v2.1.0-linux-x64.deb", published.assets[0].url, 1234, "ab".repeat(32)), release.assets.single())
        assertNull(parseRelease(Json.encodeToString(GitHubRelease.serializer(), GitHubRelease("v2.1.0", prerelease = true))))
        assertEquals(GitHubAsset("SHA256SUMS.txt", "$downloads/SHA256SUMS.txt", size = 0, digest = null), GitHubAsset("SHA256SUMS.txt", "$downloads/SHA256SUMS.txt"))
    }

    // ------------------------------------------------------------------ the installation

    /** Sets system properties for the duration of [block]; null removes one. */
    private fun <T> withProperties(vararg properties: Pair<String, String?>, block: () -> T): T {
        val before = properties.associate { it.first to System.getProperty(it.first) }
        fun set(values: Map<String, String?>) = values.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        set(properties.toMap())
        try {
            return block()
        } finally {
            set(before)
        }
    }

    @Test
    fun `the application knows how it was installed from what the launcher tells it`() {
        fun detect(os: Os, packageType: String?, arch: String = "amd64") = withProperties(
            "jpackage.app-version" to "1.3.2", "jpackage.app-path" to "launcher", "awsdbconnector.package" to packageType, "os.arch" to arch,
        ) { AppInstall.detect(os)!! }

        val msi = detect(Os.WINDOWS, "msi")
        assertEquals(listOf("1.3.2", Paths.get("launcher"), InstallKind.WINDOWS_INSTALLER, "windows-x64"), listOf(msi.version.toString(), msi.launcher, msi.kind, msi.platform))
        assertEquals(InstallKind.WINDOWS_INSTALLER, detect(Os.WINDOWS, "exe").kind, "the exe installer wraps the msi package")
        assertEquals(InstallKind.WINDOWS_PORTABLE, detect(Os.WINDOWS, "app-image").kind)
        assertEquals(InstallKind.WINDOWS_PORTABLE, detect(Os.WINDOWS, null).kind)
        assertEquals(InstallKind.MAC_DMG to "macos-arm64", detect(Os.MAC, "dmg", "aarch64").let { it.kind to it.platform })
        assertEquals(InstallKind.MAC_DMG to "macos-x64", detect(Os.MAC, "dmg", "x86_64").let { it.kind to it.platform })
        assertEquals(InstallKind.LINUX_DEB to "linux-arm64", detect(Os.LINUX, "deb", "arm64").let { it.kind to it.platform })
        assertEquals(InstallKind.LINUX_PORTABLE to "linux-x64", detect(Os.LINUX, "app-image").let { it.kind to it.platform })

        assertNull(AppInstall.detect(), "the tests are not a packaged application")
        assertNull(withProperties("jpackage.app-version" to "1.3.2", "jpackage.app-path" to null) { AppInstall.detect() })
        assertNull(withProperties("jpackage.app-version" to "not a version", "jpackage.app-path" to "launcher") { AppInstall.detect() })
    }

    @Test
    fun `portable packages are the ones that are unpacked by hand`() {
        assertEquals(
            mapOf("msi" to false, "zip" to true, "dmg" to false, "deb" to false, "tar.gz" to true),
            InstallKind.entries.associate { it.extension to it.isPortable },
        )
    }

    // ------------------------------------------------------------------ the update service

    private fun service(kind: InstallKind?, source: ReleaseSource, pause: (Duration) -> Unit = {}) = UpdateService(
        store, ActivityLog(), notifications, kind?.let { AppInstall(version("1.3.2"), Paths.get("app"), it, "windows-x64") }, source, pause,
    )

    @Test
    fun `updates are looked for after the start and once a day, sooner after a failure`() {
        TestEnv.requireIsolated()
        val leftover = Files.writeString(Files.createDirectories(Platform.cacheDir.resolve("updates")).resolve("previous.msi"), "installer")
        val newer = Release(version("v1.4.0"), "v1.4.0", "", "https://github.com/", emptyList())
        val pauses = CopyOnWriteArrayList<Duration>()
        val checks = CopyOnWriteArrayList<String>()
        val parked = CountDownLatch(1)

        val service = service(
            InstallKind.WINDOWS_INSTALLER,
            source = {
                checks += "check ${checks.size + 1}"
                if (checks.size == 1) throw IOException("no network") else newer
            },
            pause = { duration ->
                pauses += duration
                if (pauses.size == 3) store.updateSettings { it.copy(checkForUpdates = false) }
                if (pauses.size == 4) parked.await()
            },
        )
        service.start()

        await("four pauses") { pauses.size == 4 }
        assertEquals(
            listOf(Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofHours(24), Duration.ofHours(24)), pauses,
            "a while after the start, an hour after the failed check, a day after the good one and after the one that was switched off",
        )
        assertEquals(2, checks.size, "nothing is asked while the check is switched off")
        assertFalse(Files.exists(leftover), "the installer of the previous update is cleaned up")
        assertEquals("1.4.0", service.available?.release?.version.toString())
        assertEquals(1, notices.size, notices.toString())
    }

    @Test
    fun `a development run has nothing to update`() {
        val pauses = CopyOnWriteArrayList<Duration>()
        val service = service(kind = null, source = { throw AssertionError("must not be asked") }, pause = { pauses += it })
        service.start()
        assertEquals(UpdateState.Idle, service.check(manual = true))
        assertNull(service.install)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun `listeners follow a check from start to result`() {
        val service = service(InstallKind.WINDOWS_INSTALLER, source = { null })
        val states = mutableListOf<UpdateState>()
        service.addListener { states += service.state }
        service.check(manual = true)
        assertEquals(listOf(UpdateState.Checking, UpdateState.UpToDate), states)
    }

    @Test
    fun `installers are downloaded to the cache and portable packages to the download folder`() {
        TestEnv.requireIsolated()
        programs = FakePrograms { FakeProcess(exitCode = 1) } // Linux would ask xdg-user-dir for the download folder
        val content = "the package".toByteArray()
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        server.createContext("/package") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        fun update(name: String) = Asset(name, "$address/package", content.size.toLong(), sha).let { asset ->
            Update(Release(version("v1.4.0"), "v1.4.0", "", "https://github.com/", listOf(asset)), asset)
        }
        var progress = 0L to 0L

        val installer = service(InstallKind.WINDOWS_INSTALLER, { null }).download(update("AWSDBConnector-v1.4.0-windows-x64.msi")) { done, total -> progress = done to total }
        assertEquals(Platform.cacheDir.resolve("updates").resolve("AWSDBConnector-v1.4.0-windows-x64.msi"), installer)
        assertEquals("the package", Files.readString(installer))
        assertEquals(content.size.toLong() to content.size.toLong(), progress)

        val portable = service(InstallKind.LINUX_PORTABLE, { null }).download(update("AWSDBConnector-v1.4.0-linux-x64.tar.gz")) { _, _ -> }
        assertEquals(Platform.downloadsDir.resolve("AWSDBConnector-v1.4.0-linux-x64.tar.gz"), portable)
        Files.delete(portable)
        Files.delete(installer)
    }
}
