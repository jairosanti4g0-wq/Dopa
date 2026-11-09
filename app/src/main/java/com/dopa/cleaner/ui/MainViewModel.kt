package com.dopa.cleaner.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dopa.cleaner.DopaApp
import com.dopa.cleaner.PickedPhotos
import com.dopa.cleaner.auth.AuthOutcome
import com.dopa.cleaner.auth.GoogleScopes
import com.dopa.cleaner.config.AppSettings
import com.dopa.cleaner.config.CustomSourceConfig
import com.dopa.cleaner.config.SecretBox
import com.dopa.cleaner.scan.ScanState
import com.dopa.cleaner.service.ScanService
import com.dopa.cleaner.source.webdav.WebDavSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConsentPurpose { DRIVE, DRIVE_THEN_SCAN, PHOTOS }

data class PendingConsent(val purpose: ConsentPurpose, val intent: PendingIntent)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as DopaApp).container

    val settings: StateFlow<AppSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val scan: StateFlow<ScanState> = container.engine.state
    val picked: StateFlow<PickedPhotos?> = container.picked

    private val _hasAllFiles = MutableStateFlow(Environment.isExternalStorageManager())
    val hasAllFiles: StateFlow<Boolean> = _hasAllFiles.asStateFlow()

    private val _driveConnected = MutableStateFlow(false)
    val driveConnected: StateFlow<Boolean> = _driveConnected.asStateFlow()

    private val _consent = MutableStateFlow<PendingConsent?>(null)
    val consent: StateFlow<PendingConsent?> = _consent.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    private val urls = Channel<String>(Channel.BUFFERED)
    val urlFlow = urls.receiveAsFlow()

    private var photosJob: Job? = null

    private val _photosPicking = MutableStateFlow(false)
    val photosPicking: StateFlow<Boolean> = _photosPicking.asStateFlow()

    init {
        viewModelScope.launch {
            container.engine.state.collect { s ->
                val keys = s.findings.mapTo(HashSet()) { it.ref.key }
                _selected.update { sel -> sel.filterTo(HashSet()) { it in keys } }
            }
        }
        viewModelScope.launch {
            val s = container.settings.current()
            if (s.driveEnabled) silentDriveCheck()
        }
    }

    private fun toast(msg: String) {
        messages.trySend(msg)
    }

    fun refreshPermissions() {
        _hasAllFiles.value = Environment.isExternalStorageManager()
    }

    fun setThreshold(v: Float) = viewModelScope.launch { container.settings.setThreshold(v) }
    fun setLocal(v: Boolean) = viewModelScope.launch { container.settings.setLocal(v) }
    fun setPhotos(v: Boolean) = viewModelScope.launch { container.settings.setPhotos(v) }
    fun setCustomEnabled(id: String, v: Boolean) = viewModelScope.launch { container.settings.setCustomEnabled(id, v) }
    fun removeCustom(id: String) = viewModelScope.launch { container.settings.removeCustom(id) }

    fun setDrive(v: Boolean) = viewModelScope.launch {
        container.settings.setDrive(v)
        if (v) connectDrive(ConsentPurpose.DRIVE)
    }

    private suspend fun silentDriveCheck() {
        _driveConnected.value = runCatching {
            container.authorizer.authorize(listOf(GoogleScopes.DRIVE)) is AuthOutcome.Granted
        }.getOrDefault(false)
    }

    private suspend fun connectDrive(purpose: ConsentPurpose): Boolean {
        return try {
            when (val o = container.authorizer.authorize(listOf(GoogleScopes.DRIVE))) {
                is AuthOutcome.Granted -> { _driveConnected.value = true; true }
                is AuthOutcome.NeedsConsent -> { _consent.value = PendingConsent(purpose, o.pendingIntent); false }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _driveConnected.value = false
            toast("Google Drive: ${e.message ?: "falha na autorização"}. Verifique o OAuth client (pacote + SHA-1).")
            false
        }
    }

    fun onConsentResult(purpose: ConsentPurpose, ok: Boolean, data: Intent?) {
        _consent.value = null
        if (!ok) {
            toast("Autorização cancelada")
            return
        }
        val outcome = runCatching { container.authorizer.parseConsentResult(data) }.getOrElse {
            toast("Falha na autorização: ${it.message}")
            return
        }
        if (outcome !is AuthOutcome.Granted) {
            toast("Autorização não concluída")
            return
        }
        when (purpose) {
            ConsentPurpose.DRIVE -> _driveConnected.value = true
            ConsentPurpose.DRIVE_THEN_SCAN -> { _driveConnected.value = true; startScan() }
            ConsentPurpose.PHOTOS -> pickPhotos()
        }
    }

    fun pickPhotos() {
        if (photosJob?.isActive == true) return
        photosJob = viewModelScope.launch {
            _photosPicking.value = true
            try {
                when (val o = container.authorizer.authorize(listOf(GoogleScopes.PHOTOS_PICKER))) {
                    is AuthOutcome.NeedsConsent -> {
                        _consent.value = PendingConsent(ConsentPurpose.PHOTOS, o.pendingIntent)
                        return@launch
                    }
                    is AuthOutcome.Granted -> Unit
                }
                _busy.value = "Criando sessão do Google Fotos…"
                val session = container.photosClient.createSession()
                if (session.pickerUri.isBlank()) throw IllegalStateException("Sessão sem pickerUri")
                urls.trySend(session.pickerUri.trimEnd('/') + "/autoclose")
                _busy.value = "Selecione as mídias no Google Fotos e toque em Concluído…"
                container.photosClient.awaitSelection(session.id)
                _busy.value = "Baixando lista selecionada…"
                val items = container.photosClient.listItems(session.id)
                container.setPicked(items)
                container.photosClient.deleteSession(session.id)
                container.settings.setPhotos(true)
                toast("${items.size} itens do Google Fotos prontos para varredura")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Google Fotos: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.value = null
                _photosPicking.value = false
            }
        }
    }

    fun cancelPhotos() {
        photosJob?.cancel()
    }

    fun startScan() = viewModelScope.launch {
        if (scan.value.running) return@launch
        val s = container.settings.current()
        if (s.localEnabled && !Environment.isExternalStorageManager()) {
            toast("Conceda 'Acesso a todos os arquivos' para varrer o aparelho")
            return@launch
        }
        if (s.driveEnabled && !connectDrive(ConsentPurpose.DRIVE_THEN_SCAN)) return@launch
        val built = container.buildSources(s)
        built.warnings.forEach { toast(it) }
        if (built.sources.isEmpty()) {
            toast("Nenhuma fonte ativa")
            return@launch
        }
        _selected.value = emptySet()
        val started = runCatching { container.engine.start(built.sources, s.threshold) }.getOrElse {
            toast(it.message ?: "Falha ao iniciar")
            false
        }
        if (started) ScanService.start(getApplication())
    }

    fun cancelScan() = container.engine.cancel()

    fun toggle(key: String) = _selected.update { if (key in it) it - key else it + key }

    fun selectAll() = _selected.update { scan.value.findings.mapTo(HashSet()) { f -> f.ref.key } }

    fun selectNone() = _selected.update { emptySet() }

    fun dismissSelected() {
        container.engine.dismiss(_selected.value)
        _selected.value = emptySet()
    }

    fun deleteSelected() = viewModelScope.launch {
        val keys = _selected.value
        if (keys.isEmpty()) return@launch
        _busy.value = "Excluindo ${keys.size} itens…"
        try {
            val r = container.engine.delete(keys)
            val parts = buildList {
                if (r.deleted > 0) add("${r.deleted} apagados")
                if (r.trashed > 0) add("${r.trashed} na lixeira do Drive")
                if (r.unsupported.isNotEmpty()) add("${r.unsupported.size} exigem exclusão manual")
                if (r.failed.isNotEmpty()) add("${r.failed.size} falharam")
            }
            toast(parts.joinToString(" · ").ifEmpty { "Nada a excluir" })
            r.failed.take(3).forEach { toast(it) }
            r.unsupported.distinct().take(1).forEach { toast(it) }
        } finally {
            _busy.value = null
        }
    }

    fun addWebDav(name: String, url: String, user: String, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val trimmedUrl = url.trim()
            if (!trimmedUrl.startsWith("https://") && !trimmedUrl.startsWith("http://")) {
                toast("URL deve começar com https://")
                onDone(false)
                return@launch
            }
            if (trimmedUrl.startsWith("http://")) toast("Atenção: http sem TLS expõe a senha")
            _busy.value = "Testando conexão WebDAV…"
            try {
                val id = CustomSourceConfig.newId()
                val label = name.ifBlank { Uri.parse(trimmedUrl).host ?: "WebDAV" }
                WebDavSource(id, label, trimmedUrl, user, password).testConnection()
                container.settings.addCustom(
                    CustomSourceConfig.WebDav(id, label, trimmedUrl, user, SecretBox.encrypt(password)),
                )
                toast("Fonte \"$label\" adicionada")
                onDone(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("WebDAV: ${e.message ?: e.javaClass.simpleName}")
                onDone(false)
            } finally {
                _busy.value = null
            }
        }
    }

    fun addSafFolder(uri: Uri) = viewModelScope.launch {
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure {
                    toast("Provedor não concedeu acesso persistente")
                    return@launch
                }
            toast("Pasta adicionada somente leitura: exclusão pode falhar")
        }
        val label = (uri.lastPathSegment ?: "Pasta").substringAfterLast(':').ifBlank { "Pasta" }
        val authority = uri.authority.orEmpty()
        container.settings.addCustom(
            CustomSourceConfig.SafFolder(CustomSourceConfig.newId(), "$label ($authority)", uri.toString()),
        )
        toast("Pasta adicionada")
    }
}
