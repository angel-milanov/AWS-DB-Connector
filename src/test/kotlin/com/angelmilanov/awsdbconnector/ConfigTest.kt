package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.model.AppConfig
import com.angelmilanov.awsdbconnector.model.ConfigStore
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.util.Platform
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {
    private val dir = TestEnv.tempDir("config")
    private val file = dir.resolve("nested").resolve("config.json")

    @Test
    fun `connections and settings survive a restart`() {
        val store = ConfigStore(file)
        assertEquals(AppConfig(), store.config, "nothing is configured at the first start")
        assertEquals(file, store.location)

        val orders = ConnectionProfile(name = "Orders", awsProfile = "prod", dbHost = "orders.example", bastionInstanceId = "i-0123456789abcdef0")
        val billing = ConnectionProfile(name = "Billing", awsProfile = "prod", dbHost = "billing.example", localPort = 5433)
        store.saveProfile(orders)
        store.saveProfile(billing)
        store.saveProfile(orders.copy(name = "Orders PROD"))
        store.updateSettings { it.copy(theme = ThemeMode.DARK, healthCheckSeconds = 0) }
        assertEquals(listOf("Orders PROD", "Billing"), store.config.profiles.map { it.name }, "an existing connection keeps its place")

        val restarted = ConfigStore(file)
        assertEquals(store.config, restarted.config)
        assertEquals(ThemeMode.DARK, restarted.settings.theme)
        assertEquals(listOf(file.fileName.toString()), Files.list(file.parent).use { files -> files.map { it.fileName.toString() }.toList() }, "no temporary file is left")

        restarted.deleteProfile(orders.id)
        assertEquals(listOf("Billing"), ConfigStore(file).config.profiles.map { it.name })
    }

    @Test
    fun `listeners hear about every change, and only about changes`() {
        val store = ConfigStore(file)
        val heard = mutableListOf<AppConfig>()
        store.addListener { heard += it }

        store.updateSettings { it.copy(notifications = false) }
        store.updateSettings { it.copy(notifications = false) }
        store.deleteProfile("no-such-connection")
        assertEquals(1, heard.size)
        assertFalse(heard.single().settings.notifications)
    }

    @Test
    fun `a file that cannot be read is put aside instead of being overwritten`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ this is not what the application wrote")

        val store = ConfigStore(file)
        assertEquals(AppConfig(), store.config)
        assertEquals("{ this is not what the application wrote", Files.readString(file.resolveSibling("config.broken.json")))
        assertFalse(Files.exists(file))

        // Settings from a newer version are ignored rather than treated as damage.
        Files.writeString(file, """{"settings": {"theme": "LIGHT", "introducedLater": true}, "profiles": []}""")
        assertEquals(ThemeMode.LIGHT, ConfigStore(file).settings.theme)
    }

    @Test
    fun `the configuration is kept in the configuration directory of the user`() {
        TestEnv.requireIsolated()
        assertEquals(Platform.configDir.resolve("config.json"), ConfigStore().location)
    }

    @Test
    fun `themes are listed by their name`() {
        assertEquals(listOf("Follow system", "Light", "Dark"), ThemeMode.entries.map { it.toString() })
        assertTrue(ThemeMode.entries.all { it.label == it.toString() })
    }
}
