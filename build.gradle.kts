plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "com.angelmilanov"
// Release builds pass the version explicitly (gradlew -PappVersion=1.2.3); in GitLab CI it is taken from the tag (v1.2.3).
version = (findProperty("appVersion") as String?
    ?: System.getenv("CI_COMMIT_TAG")?.removePrefix("v")?.takeIf { Regex("[0-9]+([.][0-9]+){0,2}").matches(it) }
    ?: "1.0.0")

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.59.0"))
    implementation("software.amazon.awssdk:ssm")
    implementation("software.amazon.awssdk:ec2")
    implementation("software.amazon.awssdk:rds")
    implementation("software.amazon.awssdk:sts")
    // Needed on the classpath so that SSO based profiles can be resolved by the SDK.
    implementation("software.amazon.awssdk:sso")
    implementation("software.amazon.awssdk:ssooidc")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.formdev:flatlaf:3.8")
    implementation("com.miglayout:miglayout-swing:11.5.2")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.angelmilanov.awsdbconnector.MainKt")
    applicationName = "AWSDBConnector"
    applicationDefaultJvmArgs = listOf("-Xmx256m", "-Dfile.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
}

/** Draws the application icon (ui/Theme.kt) into the file formats of the native packages: ico, icns and png. */
val packageIcons = tasks.register<JavaExec>("packageIcons") {
    group = "distribution"
    description = "Writes the icons used by the native packages into build/package-icons"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.angelmilanov.awsdbconnector.PackageIconsKt")
    val dir = layout.buildDirectory.dir("package-icons")
    outputs.dir(dir)
    systemProperty("java.awt.headless", "true") // build servers have no display
    args(dir.get().asFile.absolutePath)
}

/**
 * Builds a self-contained native application (no console window, bundled runtime) into build/jpackage.
 * Run with: gradlew jpackageImage                      -> app image (folder / .app bundle)
 *           gradlew jpackageImage -PjpackageType=dmg   -> installer; jpackage types: dmg, pkg, deb, rpm, msi, exe
 * jpackage cannot cross-compile: each operating system has to build its own package.
 * The Windows installers (msi, exe) need the WiX Toolset 3.x installed or on the PATH.
 */
tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Creates a native app image with jpackage"
    dependsOn("installDist", packageIcons)

    val launcher = javaToolchains.launcherFor(java.toolchain)
    val iconDir = layout.buildDirectory.dir("package-icons")
    val libDir = layout.buildDirectory.dir("install/AWSDBConnector/lib")
    val destDir = layout.buildDirectory.dir("jpackage")
    val mainJar = tasks.jar.flatMap { it.archiveFileName }

    doFirst {
        delete(destDir)
        val javaHome = launcher.get().metadata.installationPath.asFile
        val os = System.getProperty("os.name").lowercase()
        val exe = if (os.contains("win")) "jpackage.exe" else "jpackage"
        val icon = "AWSDBConnector." + if (os.contains("win")) "ico" else if (os.contains("mac")) "icns" else "png"
        val type = (findProperty("jpackageType") as String?) ?: "app-image"
        val jpackageArgs = mutableListOf(
            File(javaHome, "bin/$exe").absolutePath,
            "--type", type,
            "--name", "AWSDBConnector",
            "--app-version", project.version.toString(),
            // Launcher, shortcuts, installers and the macOS bundle show this instead of the default Java icon.
            "--icon", iconDir.get().file(icon).asFile.absolutePath,
            "--input", libDir.get().asFile.absolutePath,
            "--main-jar", mainJar.get(),
            "--main-class", application.mainClass.get(),
            "--dest", destDir.get().asFile.absolutePath,
            "--java-options", "-Xmx256m",
            "--java-options", "-Dfile.encoding=UTF-8",
            // Tells the application how it was installed, which decides how it updates itself (update/Installer.kt).
            "--java-options", "-Dawsdbconnector.package=$type",
            "--add-modules",
            "java.base,java.desktop,java.logging,java.naming,java.net.http,java.xml,java.management," +
                "java.security.jgss,java.security.sasl,java.sql,jdk.crypto.ec,jdk.crypto.cryptoki," +
                "jdk.unsupported,jdk.net,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.accessibility",
        )
        if (type == "msi" || type == "exe") {
            jpackageArgs += listOf(
                // Windows recognises a newer version as an upgrade of the installed one by this ID: never change it.
                "--win-upgrade-uuid", "072107da-b513-42c1-a298-e0afa353df1d",
                "--win-menu", "--win-menu-group", "AWS DB Connector",
                "--win-shortcut", "--win-shortcut-prompt",
                "--win-dir-chooser",
            )
        }
        if (type == "deb" || type == "rpm") {
            // Entry in the applications menu; without a group it would be filed under an "Unknown" category.
            jpackageArgs += listOf("--linux-shortcut", "--linux-menu-group", "Development;Database;")
        }
        commandLine(jpackageArgs)
    }
}

tasks.register<JavaExec>("uiPreview") {
    group = "verification"
    description = "Renders the UI off-screen into PNG files: gradlew uiPreview -Pout=<dir>"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.angelmilanov.awsdbconnector.UiPreviewKt")
    args((findProperty("out") ?: layout.buildDirectory.dir("ui-preview").get().asFile.absolutePath).toString())
}
