package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.AppVersion
import com.angelmilanov.awsdbconnector.update.InstallKind
import com.angelmilanov.awsdbconnector.update.InstallResult
import com.angelmilanov.awsdbconnector.update.Installer
import com.angelmilanov.awsdbconnector.update.Release
import com.angelmilanov.awsdbconnector.update.Update
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** How a downloaded package is handed to the operating system. The programs that do the work are stand-ins. */
class InstallerTest {
    private val dir = TestEnv.tempDir("installer")
    private val programs = FakePrograms()
    private val desktop = FakeDesktop()
    private val pid = ProcessHandle.current().pid().toString()
    private val update = Update(Release(AppVersion.parse("1.4.0")!!, "v1.4.0", "", "https://github.com/", emptyList()), null)

    private fun installed(kind: InstallKind, launcher: Path) = AppInstall(AppVersion.parse("1.3.2")!!, launcher, kind, "any")

    private fun folder(vararg names: String): Path = Files.createDirectories(names.fold(dir) { path, name -> path.resolve(name) })

    @AfterTest
    fun cleanup() {
        programs.close()
        desktop.close()
    }

    // ------------------------------------------------------------------ Windows

    @Test
    fun `on Windows a script installs the package once the application has ended`() {
        val launcher = folder("Program Files", "AWSDBConnector").resolve("AWSDBConnector.exe")
        val msi = folder("updates").resolve("AWSDBConnector-v1.4.0-windows-x64.msi")
        val public = folder("Public", "Desktop").parent
        folder("ProgramData", "Microsoft", "Windows", "Start Menu", "Programs")
        val programData = dir.resolve("ProgramData")
        val env = mapOf("PUBLIC" to public.toString(), "ProgramData" to programData.toString())

        val result = Installer.windowsInstaller(installed(InstallKind.WINDOWS_INSTALLER, launcher), msi, Paths.get("msiexec.exe"), env::get)

        assertEquals(InstallResult.Restart, result)
        val script = programs.started.single()
        assertTrue(script.command().first().endsWith("powershell.exe"), script.command().first())
        assertEquals(listOf("-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", Installer.WINDOWS_SCRIPT), script.command().drop(1))
        assertEquals(msi.parent.toFile(), script.directory(), "not the installation folder, which could not be removed then")
        val passed = script.environment().filterKeys { it.startsWith("AWSDBC_") }
        assertEquals(
            mapOf(
                "AWSDBC_MSIEXEC" to "msiexec.exe",
                "AWSDBC_MSI" to msi.toString(),
                "AWSDBC_LOG" to msi.resolveSibling("install.log").toString(),
                "AWSDBC_DIR" to launcher.parent.toString(),
                "AWSDBC_EXE" to launcher.toString(),
                "AWSDBC_NO_SHORTCUT" to "JP_INSTALL_DESKTOP_SHORTCUT,JP_INSTALL_STARTMENU_SHORTCUT",
            ),
            passed - "AWSDBC_WAIT", "shortcuts the user does not have are not created by the update",
        )
        assertTrue(pid in passed.getValue("AWSDBC_WAIT").split(","), passed["AWSDBC_WAIT"])

        // With the shortcuts in place the installer keeps them.
        Files.writeString(public.resolve("Desktop").resolve("AWSDBConnector.lnk"), "")
        Files.writeString(folder("ProgramData", "Microsoft", "Windows", "Start Menu", "Programs", "AWS DB Connector").resolve("AWSDBConnector.lnk"), "")
        Installer.windowsInstaller(installed(InstallKind.WINDOWS_INSTALLER, launcher), msi, Paths.get("msiexec.exe"), env::get)
        assertEquals("", programs.started.last().environment()["AWSDBC_NO_SHORTCUT"])
    }

    @Test
    fun `on Windows the script also waits for the launcher that started the application`() {
        // The launcher of a packaged application is the parent process; here the parent is whatever runs the tests.
        val parent = ProcessHandle.current().parent().orElse(null)
        val command = parent?.info()?.command()?.orElse(null) ?: return
        val msi = folder("updates").resolve("update.msi")

        Installer.windowsInstaller(installed(InstallKind.WINDOWS_INSTALLER, Paths.get(command)), msi, Paths.get("msiexec.exe")) { null }

        assertEquals(listOf(pid, parent.pid().toString()), programs.started.single().environment().getValue("AWSDBC_WAIT").split(","))
    }

    // ------------------------------------------------------------------ macOS

    @Test
    fun `on macOS a script swaps the application bundle`() {
        val bundle = folder("Applications", "AWSDBConnector.app")
        val launcher = folder("Applications", "AWSDBConnector.app", "Contents", "MacOS").resolve("AWSDBConnector")
        val dmg = dir.resolve("AWSDBConnector-v1.4.0-macos-arm64.dmg")

        assertEquals(InstallResult.Restart, Installer.macDiskImage(installed(InstallKind.MAC_DMG, launcher), dmg))
        assertEquals(listOf("/bin/sh", "-c", Installer.MAC_SCRIPT, "sh", pid, dmg.toString(), bundle.toString()), programs.commands.single())
    }

    @Test
    fun `on macOS the disk image is opened where the bundle cannot be replaced`() {
        val dmg = dir.resolve("AWSDBConnector-v1.4.0-macos-arm64.dmg")
        val notABundle = folder("somewhere", "Contents", "MacOS").resolve("AWSDBConnector")
        val missing = dir.resolve("AppTranslocation").resolve("AWSDBConnector.app").resolve("Contents").resolve("MacOS").resolve("AWSDBConnector")

        for (launcher in listOf(notABundle, missing, Paths.get("AWSDBConnector"))) {
            val result = assertIs<InstallResult.Manual>(Installer.macDiskImage(installed(InstallKind.MAC_DMG, launcher), dmg))
            assertTrue("drag the new version into the Applications folder" in result.message)
        }
        assertEquals(List(3) { listOf("open", dmg.toString()) }, programs.commands)
    }

    // ------------------------------------------------------------------ Linux

    @Test
    fun `on Linux apt installs the package and the new version is started`() {
        val launcher = Paths.get("/opt/awsdbconnector/bin/AWSDBConnector")
        val deb = dir.resolve("AWSDBConnector-v1.4.0-linux-x64.deb")

        assertEquals(InstallResult.Restart, Installer.debianPackage(installed(InstallKind.LINUX_DEB, launcher), deb, "/usr/bin/pkexec"))
        assertEquals(
            listOf(
                listOf("/usr/bin/pkexec", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "--yes", deb.toString()),
                listOf("/bin/sh", "-c", Installer.LINUX_RESTART_SCRIPT, "sh", pid, launcher.toString()),
            ),
            programs.commands,
        )
    }

    @Test
    fun `on Linux a package that was not installed leaves the command to do it by hand`() {
        val install = installed(InstallKind.LINUX_DEB, Paths.get("/opt/awsdbconnector/bin/AWSDBConnector"))
        val deb = dir.resolve("update.deb")
        val byHand = "To install it yourself, run:\nsudo apt install \"$deb\""

        programs.respond = { FakeProcess(exitCode = 126) } // the password dialog was dismissed
        assertEquals("The package was not installed (exit code 126).\n$byHand", assertFailsWith<IOException> { Installer.debianPackage(install, deb, "pkexec") }.message)
        assertEquals(1, programs.commands.size, "the application is not restarted")

        assertEquals(
            "The package was downloaded, but pkexec is needed to install it.\n$byHand",
            assertFailsWith<IOException> { Installer.debianPackage(install, deb, pkexec = null) }.message,
        )

        val apt = FakeProcess(exitCode = null, interrupted = true) // the user cancelled while apt was running
        programs.respond = { apt }
        assertFailsWith<InterruptedException> { Installer.debianPackage(install, deb, "pkexec") }
        assertTrue(apt.destroyed)
    }

    // ------------------------------------------------------------------ portable archives and the choice between all of them

    @Test
    fun `a portable archive is shown in the file manager with what to do next`() {
        val archive = dir.resolve("AWSDBConnector-v1.4.0-windows-x64.zip")
        val launcher = folder("portable", "AWSDBConnector", "bin").resolve("AWSDBConnector")

        for (kind in listOf(InstallKind.WINDOWS_PORTABLE, InstallKind.LINUX_PORTABLE)) {
            val result = assertIs<InstallResult.Manual>(Installer.install(installed(kind, launcher), update, archive))
            assertTrue(result.message.startsWith("Version 1.4.0 was downloaded to\n$archive\n\nTo update, exit AWS DB Connector and replace the folder\n"), result.message)
            assertTrue(dir.resolve("portable").toString() in result.message, result.message)
        }
        assertEquals(listOf(archive.toString(), archive.toString()), desktop.opened)
        assertTrue(programs.commands.isEmpty(), "nothing is installed")

        // A desktop without a file manager: the message has to do.
        FakeDesktop(broken = true)
        assertIs<InstallResult.Manual>(Installer.install(installed(InstallKind.LINUX_PORTABLE, launcher), update, archive))
    }

    @Test
    fun `each kind of installation is updated in its own way`() {
        val launcher = folder("Applications", "AWSDBConnector.app", "Contents", "MacOS").resolve("AWSDBConnector")
        val file = dir.resolve("package")

        assertEquals(InstallResult.Restart, Installer.install(installed(InstallKind.WINDOWS_INSTALLER, launcher), update, file))
        assertTrue(programs.commands.last().first().endsWith("powershell.exe"))
        assertTrue(programs.started.last().environment().getValue("AWSDBC_MSIEXEC").endsWith("msiexec.exe"))

        assertEquals(InstallResult.Restart, Installer.install(installed(InstallKind.MAC_DMG, launcher), update, file))
        assertEquals("/bin/sh", programs.commands.last().first())

        assertEquals(InstallResult.Restart, Installer.install(installed(InstallKind.LINUX_DEB, launcher), update, file, pkexec = "/usr/bin/pkexec"))
        assertEquals(listOf("/usr/bin/pkexec", "/bin/sh"), programs.commands.takeLast(2).map { it.first() })
    }
}
