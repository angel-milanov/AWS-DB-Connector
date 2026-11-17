package com.angelmilanov.awsdbconnector.model

import com.angelmilanov.awsdbconnector.util.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark");

    override fun toString() = label
}

/**
 * One tunnel definition. Deliberately contains no credentials, database users or passwords:
 * only the AWS profile name, the host names / ids and the port mapping.
 */
@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val awsProfile: String = "",
    /** Blank means: use the region configured for the AWS profile. */
    val region: String = "",
    /** Identifier of the RDS instance / cluster (informational). */
    val rdsInstanceId: String = "",
    val dbHost: String = "",
    val dbPort: Int = 5432,
    /** EC2 instance id of the bastion host. */
    val bastionInstanceId: String = "",
    val localPort: Int = 5432,
    /** Start listening / connect when the application starts. */
    val autoConnect: Boolean = true,
    /** true: re-create a dropped session immediately. false: only when a client connects. */
    val keepAlive: Boolean = true,
)

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val notifications: Boolean = true,
    val autoSsoLogin: Boolean = true,
    val showWindowOnStart: Boolean = true,
    /** Interval of the remote session check; 0 disables it (process liveness is always monitored). */
    val healthCheckSeconds: Int = 30,
    val awsCliPath: String = "",
    val pluginPath: String = "",
)

@Serializable
data class AppConfig(
    val settings: AppSettings = AppSettings(),
    val profiles: List<ConnectionProfile> = emptyList(),
)

class ConfigStore(private val file: Path = Platform.configDir.resolve("config.json")) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val listeners = CopyOnWriteArrayList<(AppConfig) -> Unit>()

    @Volatile
    var config: AppConfig = load()
        private set

    val settings: AppSettings get() = config.settings
    val location: Path get() = file

    private fun load(): AppConfig = try {
        if (Files.exists(file)) json.decodeFromString<AppConfig>(Files.readString(file)) else AppConfig()
    } catch (e: Exception) {
        // Keep the unreadable file around instead of silently overwriting it.
        runCatching {
            Files.move(file, file.resolveSibling("config.broken.json"), StandardCopyOption.REPLACE_EXISTING)
        }
        AppConfig()
    }

    fun addListener(l: (AppConfig) -> Unit) {
        listeners += l
    }

    @Synchronized
    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(config)
        if (next == config) return
        config = next
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.createDirectories(file.parent)
        Files.writeString(tmp, json.encodeToString(AppConfig.serializer(), next))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        listeners.forEach { it(next) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = update { it.copy(settings = transform(it.settings)) }

    fun saveProfile(profile: ConnectionProfile) = update { cfg ->
        if (cfg.profiles.any { it.id == profile.id })
            cfg.copy(profiles = cfg.profiles.map { if (it.id == profile.id) profile else it })
        else cfg.copy(profiles = cfg.profiles + profile)
    }

    fun deleteProfile(id: String) = update { cfg -> cfg.copy(profiles = cfg.profiles.filterNot { it.id == id }) }
}
