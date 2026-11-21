package com.angelmilanov.awsdbconnector.update

import com.angelmilanov.awsdbconnector.util.Platform
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

sealed interface InstallResult {
    /** A helper process finishes the update and starts the new version as soon as this process has ended: exit now. */
    data object Restart : InstallResult

    /** The remaining steps are the user's; [message] says which. */
    data class Manual(val message: String) : InstallResult
}

/**
 * Installs a downloaded and verified package the way its platform expects it:
 *
 *  - Windows (msi / exe installation): Windows Installer upgrades the installation in place. It asks for
 *    administrator rights itself (the package is installed for all users) and only shows a progress bar.
 *  - macOS: the application bundle is replaced by the one in the disk image. Where that is not possible
 *    (no write access, or started from the disk image) the image is opened to drag the application over.
 *  - Linux (deb): installed with apt, which asks for the password through polkit. apt replaces the files of the
 *    running application, as it does for every package, and the new version is started afterwards.
 *  - Portable archives (zip, tar.gz): there is nothing to install; the archive is shown in the file manager.
 *
 * An application cannot replace itself while it runs (Windows locks its files, a macOS bundle would be half
 * replaced), so on Windows and macOS a small script waits for this process to end, installs and starts the new version.
 */
object Installer {
    fun install(
        install: AppInstall,
        update: Update,
        file: Path,
        /** Asks for administrator rights on Linux. Null where it is not installed. */
        pkexec: String? = Platform.findExecutable("", "pkexec", listOf("/usr/bin/pkexec")),
    ): InstallResult = when (install.kind) {
        InstallKind.WINDOWS_INSTALLER -> windowsInstaller(install, file)
        InstallKind.MAC_DMG -> macDiskImage(install, file)
        InstallKind.LINUX_DEB -> debianPackage(install, file, pkexec)
        InstallKind.WINDOWS_PORTABLE, InstallKind.LINUX_PORTABLE -> {
            reveal(file)
            // <folder>\AWSDBConnector.exe on Windows, <folder>/bin/AWSDBConnector on Linux
            val folder = if (Platform.isWindows) install.launcher.parent else install.launcher.parent?.parent
            InstallResult.Manual(
                "Version ${update.release.version} was downloaded to\n$file\n\n" +
                    "To update, exit AWS DB Connector and replace the folder\n$folder\nwith the one in the archive.",
            )
        }
    }

    // ------------------------------------------------------------------ Windows

    /*
     * Values are passed in environment variables, so that no path has to be quoted for PowerShell.
     * A shortcut the user declined during the installation, or removed since, is not created again.
     * Windows Installer removes the old version before it installs the new one; should that fail half way
     * there is nothing left to start, which is why the launcher is looked for afterwards.
     */
    internal val WINDOWS_SCRIPT = listOf(
        $$"foreach ($id in $env:AWSDBC_WAIT -split ',') { Wait-Process -Id $id -Timeout 60 -ErrorAction SilentlyContinue }",
        $$"$q = [char]34",
        $$"$a = @('/i', ($q + $env:AWSDBC_MSI + $q), '/passive', '/norestart', '/log', ($q + $env:AWSDBC_LOG + $q))",
        $$"$a += 'INSTALLDIR=' + $q + $env:AWSDBC_DIR + $q",
        $$"$a += @($env:AWSDBC_NO_SHORTCUT -split ',' | Where-Object { $_ } | ForEach-Object { $_ + '=' + $q + $q })",
        $$"Start-Process -FilePath $env:AWSDBC_MSIEXEC -ArgumentList $a -Wait",
        $$"$exe = $env:AWSDBC_EXE, (Join-Path $env:ProgramFiles 'AWSDBConnector\\AWSDBConnector.exe') | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1",
        $$"if ($exe) { Start-Process -FilePath $exe }",
    ).joinToString("; ")

    internal fun windowsInstaller(
        install: AppInstall,
        msi: Path,
        msiexec: Path = windowsSystem("msiexec.exe"),
        env: (String) -> String? = System::getenv,
    ): InstallResult {
        val self = ProcessHandle.current()
        // The jpackage launcher runs the application in a child process of the same executable: wait for both.
        val launcher = self.parent().filter { parent ->
            parent.info().command().map { it.equals(install.launcher.toString(), ignoreCase = true) }.orElse(false)
        }
        val declinedShortcuts = buildList {
            val desktop = Paths.get(env("PUBLIC") ?: "C:\\Users\\Public", "Desktop")
            val menu = Paths.get(env("ProgramData") ?: "C:\\ProgramData", "Microsoft", "Windows", "Start Menu", "Programs")
            if (Files.isDirectory(desktop) && Files.notExists(desktop.resolve("AWSDBConnector.lnk"))) add("JP_INSTALL_DESKTOP_SHORTCUT")
            if (Files.isDirectory(menu) && Files.notExists(menu.resolve("AWS DB Connector").resolve("AWSDBConnector.lnk"))) {
                add("JP_INSTALL_STARTMENU_SHORTCUT")
            }
        }
        detached(
            // Not the installation folder, which Windows could not remove while it is someone's working directory.
            directory = msi.parent,
            environment = mapOf(
                "AWSDBC_WAIT" to listOfNotNull(self.pid(), launcher.map { it.pid() }.orElse(null)).joinToString(","),
                "AWSDBC_MSIEXEC" to msiexec.toString(),
                "AWSDBC_MSI" to msi.toString(),
                "AWSDBC_LOG" to msi.resolveSibling("install.log").toString(),
                "AWSDBC_DIR" to install.launcher.parent.toString(),
                "AWSDBC_EXE" to install.launcher.toString(),
                "AWSDBC_NO_SHORTCUT" to declinedShortcuts.joinToString(","),
            ),
            windowsSystem("WindowsPowerShell", "v1.0", "powershell.exe").toString(),
            "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", WINDOWS_SCRIPT,
        )
        return InstallResult.Restart
    }

    /** Full paths, so that nothing of the same name on the PATH is started instead. */
    private fun windowsSystem(vararg path: String): Path =
        Paths.get(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", *path)

    // ------------------------------------------------------------------ macOS

    /*
     * $1 process to wait for, $2 disk image, $3 installed application bundle.
     * The new bundle is copied next to the old one first and then swapped in, so the application is never half
     * replaced; if anything fails the old version is kept and the disk image is opened to install by hand.
     */
    internal val MAC_SCRIPT = $$"""
        pid=$1; dmg=$2; app=$3
        while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
        new="$app.new"; old="$app.old"; ok=0
        mnt=$(mktemp -d "${TMPDIR:-/tmp}/awsdbconnector.XXXXXX") || exit 1
        if hdiutil attach "$dmg" -nobrowse -readonly -noautoopen -mountpoint "$mnt" >/dev/null &&
           rm -rf "$new" "$old" && ditto "$mnt/$(basename "$app")" "$new" && mv "$app" "$old"; then
          if mv "$new" "$app"; then ok=1; rm -rf "$old"; else mv "$old" "$app"; fi
        fi
        rm -rf "$new"
        hdiutil detach "$mnt" -quiet
        rmdir "$mnt" 2>/dev/null
        [ "$ok" = 1 ] || open "$dmg"
        open "$app"
    """.trimIndent()

    internal fun macDiskImage(install: AppInstall, dmg: Path): InstallResult {
        // AWSDBConnector.app/Contents/MacOS/AWSDBConnector
        val bundle = install.launcher.parent?.parent?.parent
        val replaceable = bundle != null && bundle.toString().endsWith(".app") &&
            "/AppTranslocation/" !in bundle.toString() && // macOS runs a quarantined application from a read-only copy
            Files.isWritable(bundle) && Files.isWritable(bundle.parent)
        if (!replaceable) {
            detached(null, emptyMap(), "open", dmg.toString())
            return InstallResult.Manual(
                "The disk image with the new version has been opened.\n\n" +
                    "To update, exit AWS DB Connector, drag the new version into the Applications folder and start it again.",
            )
        }
        detached(null, emptyMap(), "/bin/sh", "-c", MAC_SCRIPT, "sh", ProcessHandle.current().pid().toString(), dmg.toString(), bundle.toString())
        return InstallResult.Restart
    }

    // ------------------------------------------------------------------ Linux

    /** $1 process to wait for, $2 launcher of the new version. */
    internal const val LINUX_RESTART_SCRIPT = $$"while kill -0 \"$1\" 2>/dev/null; do sleep 0.2; done; exec \"$2\""

    internal fun debianPackage(install: AppInstall, deb: Path, pkexec: String?): InstallResult {
        val byHand = "To install it yourself, run:\nsudo apt install \"$deb\""
        if (pkexec == null) throw IOException("The package was downloaded, but pkexec is needed to install it.\n$byHand")
        val apt = Platform.start(
            ProcessBuilder(pkexec, "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "--yes", deb.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD),
        )
        apt.outputStream.close()
        val code = try {
            apt.waitFor()
        } catch (e: InterruptedException) {
            apt.destroy()
            throw e
        }
        // 126: the password dialog was dismissed, 127: not authorised (or no polkit agent is running)
        if (code != 0) throw IOException("The package was not installed (exit code $code).\n$byHand")
        detached(null, emptyMap(), "/bin/sh", "-c", LINUX_RESTART_SCRIPT, "sh", ProcessHandle.current().pid().toString(), install.launcher.toString())
        return InstallResult.Restart
    }

    // ------------------------------------------------------------------ shared

    /** Starts a process that is meant to outlive this one. */
    private fun detached(directory: Path?, environment: Map<String, String>, vararg command: String) {
        val builder = ProcessBuilder(*command).apply {
            directory?.let { directory(it.toFile()) }
            environment().putAll(environment)
            redirectInput(ProcessBuilder.Redirect.from(File(if (Platform.isWindows) "NUL" else "/dev/null")))
            redirectOutput(ProcessBuilder.Redirect.DISCARD)
            redirectError(ProcessBuilder.Redirect.DISCARD)
        }
        Platform.start(builder)
    }

    /** Shows [file] in the file manager, where there is one. */
    private fun reveal(file: Path) {
        runCatching { Platform.desktop.reveal(file) }
    }
}
