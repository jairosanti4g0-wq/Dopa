package com.dopa.cleaner.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
sealed interface CustomSourceConfig {
    val id: String
    val name: String

    @Serializable
    @SerialName("webdav")
    data class WebDav(
        override val id: String,
        override val name: String,
        val baseUrl: String,
        val username: String,
        val encryptedPassword: String,
    ) : CustomSourceConfig

    @Serializable
    @SerialName("saf")
    data class SafFolder(
        override val id: String,
        override val name: String,
        val treeUri: String,
    ) : CustomSourceConfig

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

data class AppSettings(
    val threshold: Float,
    val localEnabled: Boolean,
    val driveEnabled: Boolean,
    val photosEnabled: Boolean,
    val disabledCustom: Set<String>,
    val custom: List<CustomSourceConfig>,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private object Keys {
        val THRESHOLD = floatPreferencesKey("threshold")
        val LOCAL = booleanPreferencesKey("local_enabled")
        val DRIVE = booleanPreferencesKey("drive_enabled")
        val PHOTOS = booleanPreferencesKey("photos_enabled")
        val CUSTOM = stringPreferencesKey("custom_sources")
        val DISABLED = stringPreferencesKey("disabled_custom")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            threshold = (p[Keys.THRESHOLD] ?: DEFAULT_THRESHOLD).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
            localEnabled = p[Keys.LOCAL] ?: true,
            driveEnabled = p[Keys.DRIVE] ?: false,
            photosEnabled = p[Keys.PHOTOS] ?: false,
            disabledCustom = decodeSet(p[Keys.DISABLED]),
            custom = decodeCustom(p[Keys.CUSTOM]),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThreshold(value: Float) = context.dataStore.edit {
        it[Keys.THRESHOLD] = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }

    suspend fun setLocal(enabled: Boolean) = context.dataStore.edit { it[Keys.LOCAL] = enabled }
    suspend fun setDrive(enabled: Boolean) = context.dataStore.edit { it[Keys.DRIVE] = enabled }
    suspend fun setPhotos(enabled: Boolean) = context.dataStore.edit { it[Keys.PHOTOS] = enabled }

    suspend fun setCustomEnabled(id: String, enabled: Boolean) = context.dataStore.edit { p ->
        val set = decodeSet(p[Keys.DISABLED]).toMutableSet()
        if (enabled) set.remove(id) else set.add(id)
        p[Keys.DISABLED] = json.encodeToString(set.toList())
    }

    suspend fun addCustom(config: CustomSourceConfig) = context.dataStore.edit { p ->
        val list = decodeCustom(p[Keys.CUSTOM]).filterNot { it.id == config.id } + config
        p[Keys.CUSTOM] = json.encodeToString(list)
    }

    suspend fun removeCustom(id: String) = context.dataStore.edit { p ->
        val list = decodeCustom(p[Keys.CUSTOM]).filterNot { it.id == id }
        p[Keys.CUSTOM] = json.encodeToString(list)
    }

    private fun decodeCustom(raw: String?): List<CustomSourceConfig> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<CustomSourceConfig>>(raw) }.getOrDefault(emptyList())

    private fun decodeSet(raw: String?): Set<String> =
        if (raw.isNullOrBlank()) emptySet()
        else runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())

    companion object {
        const val DEFAULT_THRESHOLD = 0.70f
        const val MIN_THRESHOLD = 0.30f
        const val MAX_THRESHOLD = 0.98f
    }
}
