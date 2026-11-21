package com.angelmilanov.awsdbconnector.update

import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.util.ActivityLog
import com.angelmilanov.awsdbconnector.util.NoticeLevel
import com.angelmilanov.awsdbconnector.util.Notifications
import com.angelmilanov.awsdbconnector.util.Os
import com.angelmilanov.awsdbconnector.util.Platform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList

/** The GitHub repository whose releases are offered as updates. */
const val REPOSITORY = "angel-milanov/AWS-DB-Connector"
const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"

private const val USER_AGENT = "AWSDBConnector-updater"
private val http: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
}

class AppVersion private constructor(private val parts: List<Int>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString() = parts.joinToString(".")

    companion object {
        private val pattern = Regex("""v?(\d{1,9}(?:\.\d{1,9}){0,3})(?:[-+].*)?""")

        /** Accepts versions (1.2.3) and release tags (v1.2.3, v1.3.0-rc.1; the suffix is ignored, as it is by the build). */
        fun parse(text: String): AppVersion? =
            pattern.matchEntire(text.trim())?.let { m -> AppVersion(m.groupValues[1].split('.').map(String::toInt)) }
    }
}

/** How this copy of the application was installed, which decides which package updates it and how. */
enum class InstallKind(val extension: String) {
    WINDOWS_INSTALLER("msi"), WINDOWS_PORTABLE("zip"), MAC_DMG("dmg"), LINUX_DEB("deb"), LINUX_PORTABLE("tar.gz");

    /** Unpacked by hand: the update is downloaded for the user instead of being installed. */
    val isPortable get() = this == WINDOWS_PORTABLE || this == LINUX_PORTABLE
}

/**
 * The running, packaged application.
 * @param launcher the native launcher (AWSDBConnector.exe, bin/AWSDBConnector, AWSDBConnector.app/Contents/MacOS/AWSDBConnector)
 * @param platform as used in the file names of a release, e.g. windows-x64
 */
data class AppInstall(val version: AppVersion, val launcher: Path, val kind: InstallKind, val platform: String) {
    companion object {
        /** Null for development runs (gradlew run): only a packaged application knows its version and location. */
        fun detect(os: Os = Platform.os): AppInstall? {
            val version = System.getProperty("jpackage.app-version")?.let(AppVersion::parse) ?: return null
            val launcher = System.getProperty("jpackage.app-path")?.let { Paths.get(it) } ?: return null
            // Set by the jpackageImage task in build.gradle.kts.
            val packageType = System.getProperty("awsdbconnector.package", "app-image")
            val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
            val (name, kind) = when (os) {
                // The exe installer is a wrapper around the msi package: both are updated with the msi.
                Os.WINDOWS -> "windows" to
                    if (packageType == "msi" || packageType == "exe") InstallKind.WINDOWS_INSTALLER else InstallKind.WINDOWS_PORTABLE
                Os.MAC -> "macos" to InstallKind.MAC_DMG
                Os.LINUX -> "linux" to if (packageType == "deb") InstallKind.LINUX_DEB else InstallKind.LINUX_PORTABLE
            }
            return AppInstall(version, launcher, kind, "$name-$arch")
        }
    }
}

/** @param sha256 lower case hex, or null if the release does not state it */
data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

data class Release(val version: AppVersion, val tag: String, val notes: String, val pageUrl: String, val assets: List<Asset>)

/** A release newer than the running application, with the package that fits this installation (if the release has one). */
data class Update(val release: Release, val asset: Asset?)

fun interface ReleaseSource {
    /** The latest published release, or null if there is none. Throws if it cannot be determined. */
    fun latest(): Release?
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    @SerialName("html_url") val pageUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
    val digest: String? = null,
)

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
private val safeFileName = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
private val sha256Hex = Regex("[0-9a-f]{64}")

/**
 * Reads the answer of GitHub's "latest release" API. Null if it is not a release the application can update to.
 * Everything that does not point into [REPOSITORY] is dropped, so that a manipulated answer cannot send the
 * download or the browser somewhere else.
 */
fun parseRelease(text: String): Release? {
    val release = json.decodeFromString<GitHubRelease>(text)
    if (release.draft || release.prerelease) return null
    val version = AppVersion.parse(release.tag) ?: return null
    val assets = release.assets
        .filter { it.url.startsWith("https://github.com/$REPOSITORY/releases/download/", ignoreCase = true) }
        .filter { safeFileName.matches(it.name) }
        .map { Asset(it.name, it.url, it.size, it.digest?.removePrefix("sha256:")?.lowercase()?.takeIf(sha256Hex::matches)) }
    val page = release.pageUrl?.takeIf { it.startsWith("https://github.com/$REPOSITORY/", ignoreCase = true) } ?: RELEASES_PAGE
    return Release(version, release.tag, release.body.orEmpty().trim(), page, assets)
}

/** The latest release on GitHub; pre-releases and drafts are never returned by this API. */
class GitHubReleases(private val api: String = "https://api.github.com") : ReleaseSource {
    override fun latest(): Release? {
        val request = HttpRequest.newBuilder(URI("$api/repos/$REPOSITORY/releases/latest"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> parseRelease(response.body())
            404 -> null // nothing released yet
            403, 429 -> throw IOException("GitHub is limiting requests from this network at the moment (HTTP ${response.statusCode()})")
            else -> throw IOException("GitHub answered with HTTP ${response.statusCode()}")
        }
    }
}

/**
 * Downloads [asset] into [dir] and returns the file once its SHA-256 checksum has been confirmed;
 * nothing is left behind otherwise. Interrupt the thread to cancel.
 */
fun download(asset: Asset, dir: Path, progress: (done: Long, total: Long) -> Unit): Path {
    val expected = asset.sha256 ?: throw IOException("The release does not state a checksum for ${asset.name}.")
    Files.createDirectories(dir)
    val part = dir.resolve(asset.name + ".part")
    try {
        val request = HttpRequest.newBuilder(URI(asset.url)).header("User-Agent", USER_AGENT).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            if (response.statusCode() != 200) throw IOException("The download failed with HTTP ${response.statusCode()}.")
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(part).use { out ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    done += n
                    if (done > asset.size) throw IOException("The download is larger than the release states.")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    progress(done, asset.size)
                }
            }
            if (HexFormat.of().formatHex(digest.digest()) != expected) {
                throw IOException("The checksum of ${asset.name} does not match the one of the release. The file was discarded.")
            }
        }
        return Files.move(part, dir.resolve(asset.name), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(part)
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: Update) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Looks for a newer release shortly after the start and once a day from then on (the application usually runs
 * for days in the tray). It only ever announces an update: downloading and installing is the user's decision.
 */
class UpdateService(
    private val store: ConfigStore,
    private val log: ActivityLog,
    private val notifier: Notifications,
    /** Null in development runs, in which there is nothing to update. */
    val install: AppInstall?,
    private val source: ReleaseSource,
    /** Waits between two checks. */
    private val pause: (Duration) -> Unit = Thread::sleep,
) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var announced: String? = null

    @Volatile
    var state: UpdateState = UpdateState.Idle
        private set

    val available: Update? get() = (state as? UpdateState.Available)?.update

    /** Where downloaded installers are kept until they have been installed. */
    private val installerDir: Path get() = Platform.cacheDir.resolve("updates")

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    private fun set(next: UpdateState) {
        state = next
        listeners.forEach { it() }
    }

    fun start() {
        if (install == null) return
        Thread.ofVirtual().name("update-check").start {
            runCatching { installerDir.toFile().deleteRecursively() } // the installer of the previous update
            pause(Duration.ofSeconds(30))
            while (true) {
                val failed = store.settings.checkForUpdates && check(manual = false) is UpdateState.Failed
                pause(if (failed) Duration.ofHours(1) else Duration.ofHours(24))
            }
        }
    }

    /**
     * @param manual the user asked for it: a version that was skipped earlier is offered again and failures are
     * reported, whereas a failed automatic check never hides an update that is already known.
     */
    @Synchronized
    fun check(manual: Boolean): UpdateState {
        val install = install ?: return state
        val before = state
        set(UpdateState.Checking)
        val next = try {
            val release = source.latest()
            when {
                release == null || release.version <= install.version -> UpdateState.UpToDate
                !manual && release.tag == store.settings.skippedUpdate -> UpdateState.UpToDate
                else -> {
                    val suffix = "-${install.platform}.${install.kind.extension}"
                    UpdateState.Available(Update(release, release.assets.firstOrNull { it.name.endsWith(suffix) }))
                }
            }
        } catch (e: Exception) {
            log.log("update", "The check for updates failed: ${e.message ?: e.javaClass.simpleName}")
            if (!manual && before is UpdateState.Available) before else UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (next is UpdateState.Available && next.update.release.tag != announced) {
            announced = next.update.release.tag
            val version = next.update.release.version
            log.log("update", "Version $version is available (installed: ${install.version})")
            if (!manual) notifier.show(
                "Update available",
                "AWS DB Connector $version is ready to install. Open the application or its tray menu to update.",
                NoticeLevel.INFO,
            )
        }
        set(next)
        return next
    }

    /** Never offer this release again in automatic checks. */
    fun skip(update: Update) {
        store.updateSettings { it.copy(skippedUpdate = update.release.tag) }
        set(UpdateState.UpToDate)
    }

    /** Downloads and verifies the package of [update]. Interrupt the thread to cancel. */
    fun download(update: Update, progress: (done: Long, total: Long) -> Unit): Path {
        val install = checkNotNull(install)
        val asset = checkNotNull(update.asset)
        return download(asset, if (install.kind.isPortable) Platform.downloadsDir else installerDir, progress)
    }
}
