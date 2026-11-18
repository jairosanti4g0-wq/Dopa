package com.dopa.cleaner.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dopa.cleaner.core.RiskCategories
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

enum class ScanSchedule { OFF, DAILY, WEEKLY }

enum class ScanSpeed(val label: String, val videoFrames: Int, val useExifThumb: Boolean) {
    FAST("Rápido", 1, true),
    BALANCED("Equilibrado", 2, true),
    PRECISE("Preciso", 3, false),
}

data class ScanOptions(
    val speed: ScanSpeed = ScanSpeed.BALANCED,
    val skipThumbnailDirs: Boolean = true,
    val skipHiddenDirs: Boolean = false,
    val minFileKb: Int = 8,
    val minImagePx: Int = 160,
    val deepScan: Boolean = false,
    val excludedPaths: List<String> = DEFAULT_EXCLUDED,
) {
    fun isExcluded(path: String): Boolean =
        excludedPaths.any { it.isNotBlank() && path.contains(it.trim(), ignoreCase = true) }

    companion object {
        val DEFAULT_EXCLUDED = listOf("/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers")
    }
}

data class AppSettings(
    val threshold: Float,
    val schedule: ScanSchedule,
    val options: ScanOptions,
    val blurPreviews: Boolean,
    val categories: RiskCategories,
    val useGpu: Boolean,
    val appLock: Boolean,
    val notifyOnFinish: Boolean,
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
        val SCHEDULE = stringPreferencesKey("schedule")
        val SPEED = stringPreferencesKey("speed")
        val SKIP_THUMBS = booleanPreferencesKey("skip_thumbs")
        val SKIP_HIDDEN = booleanPreferencesKey("skip_hidden")
        val MIN_KB = intPreferencesKey("min_kb")
        val BLUR = booleanPreferencesKey("blur")
        val MIN_PX = intPreferencesKey("min_px")
        val DEEP = booleanPreferencesKey("deep_scan")
        val EXCLUDED = stringPreferencesKey("excluded_paths")
        val CAT_PORN = booleanPreferencesKey("cat_porn")
        val CAT_HENTAI = booleanPreferencesKey("cat_hentai")
        val CAT_SEXY = booleanPreferencesKey("cat_sexy")
        val GPU = booleanPreferencesKey("use_gpu")
        val LOCK = booleanPreferencesKey("app_lock")
        val NOTIFY = booleanPreferencesKey("notify_finish")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            threshold = (p[Keys.THRESHOLD] ?: DEFAULT_THRESHOLD).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
            schedule = p[Keys.SCHEDULE]?.let { raw -> ScanSchedule.entries.firstOrNull { it.name == raw } } ?: ScanSchedule.OFF,
            options = ScanOptions(
                speed = p[Keys.SPEED]?.let { raw -> ScanSpeed.entries.firstOrNull { it.name == raw } } ?: ScanSpeed.BALANCED,
                skipThumbnailDirs = p[Keys.SKIP_THUMBS] ?: true,
                skipHiddenDirs = p[Keys.SKIP_HIDDEN] ?: false,
                minFileKb = (p[Keys.MIN_KB] ?: 8).coerceIn(0, 1024),
                minImagePx = (p[Keys.MIN_PX] ?: 160).coerceIn(0, 2048),
                deepScan = p[Keys.DEEP] ?: false,
                excludedPaths = p[Keys.EXCLUDED]?.let { raw ->
                    runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
                } ?: ScanOptions.DEFAULT_EXCLUDED,
            ),
            blurPreviews = p[Keys.BLUR] ?: true,
            categories = RiskCategories(
                porn = p[Keys.CAT_PORN] ?: true,
                hentai = p[Keys.CAT_HENTAI] ?: true,
                sexy = p[Keys.CAT_SEXY] ?: true,
            ),
            useGpu = p[Keys.GPU] ?: false,
            appLock = p[Keys.LOCK] ?: false,
            notifyOnFinish = p[Keys.NOTIFY] ?: true,
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

    suspend fun setSchedule(value: ScanSchedule) = context.dataStore.edit { it[Keys.SCHEDULE] = value.name }

    suspend fun setSpeed(value: ScanSpeed) = context.dataStore.edit { it[Keys.SPEED] = value.name }
    suspend fun setSkipThumbs(value: Boolean) = context.dataStore.edit { it[Keys.SKIP_THUMBS] = value }
    suspend fun setSkipHidden(value: Boolean) = context.dataStore.edit { it[Keys.SKIP_HIDDEN] = value }
    suspend fun setMinKb(value: Int) = context.dataStore.edit { it[Keys.MIN_KB] = value.coerceIn(0, 1024) }
    suspend fun setBlur(value: Boolean) = context.dataStore.edit { it[Keys.BLUR] = value }
    suspend fun setMinPx(value: Int) = context.dataStore.edit { it[Keys.MIN_PX] = value.coerceIn(0, 2048) }
    suspend fun setDeepScan(value: Boolean) = context.dataStore.edit { it[Keys.DEEP] = value }
    suspend fun setGpu(value: Boolean) = context.dataStore.edit { it[Keys.GPU] = value }
    suspend fun setAppLock(value: Boolean) = context.dataStore.edit { it[Keys.LOCK] = value }
    suspend fun setNotify(value: Boolean) = context.dataStore.edit { it[Keys.NOTIFY] = value }

    suspend fun setCategories(value: RiskCategories) = context.dataStore.edit {
        it[Keys.CAT_PORN] = value.porn
        it[Keys.CAT_HENTAI] = value.hentai
        it[Keys.CAT_SEXY] = value.sexy
    }

    suspend fun addExcluded(path: String) = context.dataStore.edit { p ->
        val current = p[Keys.EXCLUDED]?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: ScanOptions.DEFAULT_EXCLUDED
        val clean = path.trim()
        if (clean.isNotEmpty() && clean !in current) p[Keys.EXCLUDED] = json.encodeToString(current + clean)
    }

    suspend fun removeExcluded(path: String) = context.dataStore.edit { p ->
        val current = p[Keys.EXCLUDED]?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: ScanOptions.DEFAULT_EXCLUDED
        p[Keys.EXCLUDED] = json.encodeToString(current - path)
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
