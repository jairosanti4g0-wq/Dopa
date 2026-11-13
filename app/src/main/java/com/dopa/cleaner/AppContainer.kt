package com.dopa.cleaner

import android.content.Context
import android.net.Uri
import com.dopa.cleaner.auth.GoogleAuthorizer
import com.dopa.cleaner.classifier.NsfwClassifier
import com.dopa.cleaner.config.AppSettings
import com.dopa.cleaner.config.CustomSourceConfig
import com.dopa.cleaner.config.SecretBox
import com.dopa.cleaner.config.SettingsRepository
import com.dopa.cleaner.core.MediaSource
import com.dopa.cleaner.scan.PreviewStore
import com.dopa.cleaner.scan.ScanEngine
import com.dopa.cleaner.scan.ScanStore
import com.dopa.cleaner.source.drive.GoogleDriveSource
import com.dopa.cleaner.source.local.LocalStorageSource
import com.dopa.cleaner.source.photos.GooglePhotosPickerClient
import com.dopa.cleaner.source.photos.GooglePhotosSource
import com.dopa.cleaner.source.photos.PickedMediaItem
import com.dopa.cleaner.source.saf.SafFolderSource
import com.dopa.cleaner.source.webdav.WebDavSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class PickedPhotos(val items: List<PickedMediaItem>, val pickedAt: Long) {
    val expired: Boolean get() = System.currentTimeMillis() - pickedAt > BASE_URL_TTL_MS

    companion object {
        private const val BASE_URL_TTL_MS = 55L * 60 * 1000
    }
}

class SourceBuildResult(val sources: List<MediaSource>, val warnings: List<String>)

class AppContainer(context: Context) {
    private val app = context.applicationContext

    val settings = SettingsRepository(app)
    val authorizer = GoogleAuthorizer(app)
    val photosClient = GooglePhotosPickerClient(authorizer)
    private val previews = PreviewStore(File(app.noBackupFilesDir, "previews"))
    private val store = ScanStore(app)

    private val classifierLock = Mutex()
    private var classifier: NsfwClassifier? = null

    private suspend fun classifier(): NsfwClassifier = classifierLock.withLock {
        classifier ?: withContext(Dispatchers.IO) { NsfwClassifier.create(app) }.also { classifier = it }
    }

    val engine = ScanEngine(
        classifierProvider = { classifier() },
        previews = previews,
        store = store,
        sourceResolver = { id -> buildSources(settings.current()).sources.firstOrNull { it.id == id } },
    )

    private val _picked = MutableStateFlow<PickedPhotos?>(null)
    val picked: StateFlow<PickedPhotos?> = _picked.asStateFlow()

    fun setPicked(items: List<PickedMediaItem>) {
        _picked.value = PickedPhotos(items, System.currentTimeMillis())
    }

    fun buildSources(s: AppSettings, interactive: Boolean = true): SourceBuildResult {
        val out = mutableListOf<MediaSource>()
        val warnings = mutableListOf<String>()
        if (s.localEnabled) out += LocalStorageSource(app, s.options)
        if (s.driveEnabled) out += GoogleDriveSource(authorizer)
        if (s.photosEnabled && interactive) {
            val p = _picked.value
            when {
                p == null || p.items.isEmpty() -> warnings += "Google Fotos: nenhuma mídia selecionada"
                p.expired -> warnings += "Google Fotos: seleção expirou (links valem 60 min). Selecione novamente"
                else -> out += GooglePhotosSource(photosClient, p.items)
            }
        }
        for (c in s.custom) {
            if (c.id in s.disabledCustom) continue
            when (c) {
                is CustomSourceConfig.WebDav -> {
                    val password = runCatching { SecretBox.decrypt(c.encryptedPassword) }.getOrNull()
                    if (password == null) {
                        warnings += "${c.name}: senha ilegível, recadastre a fonte"
                    } else {
                        out += runCatching { WebDavSource(c.id, c.name, c.baseUrl, c.username, password) }
                            .onFailure { warnings += "${c.name}: URL inválida" }
                            .getOrNull() ?: continue
                    }
                }
                is CustomSourceConfig.SafFolder -> out += SafFolderSource(app, c.id, c.name, Uri.parse(c.treeUri))
            }
        }
        return SourceBuildResult(out, warnings)
    }
}
