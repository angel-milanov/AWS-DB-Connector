@file:ExcludeFromJacocoGeneratedReport("The entry point of the process: it ends in exitProcess, which would end the tests as well")

package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.ui.AppIcon
import com.angelmilanov.awsdbconnector.update.AppInstall
import com.angelmilanov.awsdbconnector.update.GitHubReleases
import com.angelmilanov.awsdbconnector.util.ExcludeFromJacocoGeneratedReport
import com.angelmilanov.awsdbconnector.util.Platform
import com.angelmilanov.awsdbconnector.util.systemTray
import kotlin.system.exitProcess

fun main() {
    System.setProperty("apple.awt.UIElement", "true") // tray-only on macOS: no dock icon
    System.setProperty("awt.useSystemAAFontSettings", "on")
    App(
        tray = { systemTray(AppIcon.tray(null), "AWS DB Connector") },
        terminate = { exitProcess(0) },
        configDir = Platform.configDir,
        install = AppInstall.detect(),
        releases = GitHubReleases(),
    ).start()
}
