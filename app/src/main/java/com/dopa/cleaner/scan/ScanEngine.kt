package com.dopa.cleaner.scan

import android.graphics.Bitmap
import com.dopa.cleaner.classifier.NsfwClassifier
import com.dopa.cleaner.core.AuthRequiredException
import com.dopa.cleaner.config.ScanSpeed
import com.dopa.cleaner.core.DeleteResult
import com.dopa.cleaner.core.FrameRequest
import com.dopa.cleaner.core.ViewTarget
import com.dopa.cleaner.core.Finding
import com.dopa.cleaner.core.MediaRef
import com.dopa.cleaner.core.MediaSource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ScanState(
    val running: Boolean = false,
    val scanned: Int = 0,
    val cached: Int = 0,
    val flagged: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val currentSource: String? = null,
    val sourceErrors: List<String> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val finishedAt: Long? = null,
)

data class DeleteReport(
    val deleted: Int,
    val trashed: Int,
    val unsupported: List<String>,
    val failed: List<String>,
)

class ScanEngine(
    private val classifierProvider: suspend () -> NsfwClassifier,
    private val previews: PreviewStore,
    private val store: ScanStore,
    private val sourceResolver: suspend (String) -> MediaSource?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var job: Job? = null
    private val sources = ConcurrentHashMap<String, MediaSource>()
    private val findings = ConcurrentHashMap<String, Finding>()
    private val sourceErrors: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scanned = AtomicInteger()
    private val cached = AtomicInteger()
    private val skipped = AtomicInteger()
    private val errors = AtomicInteger()
    @Volatile private var current: String? = null
    @Volatile private var running = false
    @Volatile private var finishedAt: Long? = null

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                store.findings().forEach { s ->
                    findings[s.ref.key] = Finding(s.ref, s.sourceName, s.canDelete, s.scores, previews.existing(s.ref.key))
                }
            }
            publish()
        }
    }

    @Synchronized
    fun start(list: List<MediaSource>, threshold: Float, speed: ScanSpeed = ScanSpeed.BALANCED): Boolean {
        if (job?.isActive == true) return false
        require(list.isNotEmpty()) { "Nenhuma fonte selecionada" }
        list.forEach { sources[it.id] = it }
        sourceErrors.clear()
        scanned.set(0); cached.set(0); skipped.set(0); errors.set(0)
        current = null
        finishedAt = null
        running = true
        publish()
        val runId = System.currentTimeMillis()
        val request = FrameRequest(
            targetPx = if (speed == ScanSpeed.FAST) 224 else 256,
            videoFrames = speed.videoFrames,
            allowEmbeddedThumb = speed.useExifThumb,
        )
        job = scope.launch {
            val ticker = launch {
                while (isActive) {
                    publish()
                    delay(PUBLISH_MS)
                }
            }
            try {
                val classifier = classifierProvider()
                for (src in list) {
                    current = src.displayName
                    val errorsBefore = errors.get()
                    try {
                        scanSource(src, classifier, threshold, runId, request)
                        if (errors.get() == errorsBefore) {
                            store.prune(src.id, runId).forEach { dropLocal(it) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        sourceErrors += "${src.displayName}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            } catch (e: CancellationException) {
                sourceErrors += "Varredura cancelada"
            } catch (e: Throwable) {
                sourceErrors += "Falha geral: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                ticker.cancel()
                running = false
                current = null
                finishedAt = System.currentTimeMillis()
                publish()
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    suspend fun awaitIdle(): ScanState = state.first { !it.running }

    private suspend fun scanSource(
        src: MediaSource,
        classifier: NsfwClassifier,
        threshold: Float,
        runId: Long,
        request: FrameRequest,
    ) = coroutineScope {
        val channel = Channel<MediaRef>(capacity = 256)
        launch {
            try {
                src.enumerate().collect { channel.send(it) }
            } finally {
                channel.close()
            }
        }
        val workers = (Runtime.getRuntime().availableProcessors() + classifier.parallelism).coerceIn(4, 12)
        repeat(workers) {
            launch(Dispatchers.IO) {
                for (ref in channel) process(src, ref, classifier, threshold, runId, request)
            }
        }
    }

    private suspend fun process(
        src: MediaSource,
        ref: MediaRef,
        classifier: NsfwClassifier,
        threshold: Float,
        runId: Long,
        request: FrameRequest,
    ) {
        val hit = ref.version?.let { v -> runCatching { store.get(ref.key) }.getOrNull()?.takeIf { it.ref.version == v } }
        if (hit != null) {
            cached.incrementAndGet()
            val flagged = hit.scores.unsafe >= threshold
            runCatching { store.touch(ref.key, runId, flagged, src.displayName, src.canDelete) }
            if (!flagged || hit.dismissed) {
                findings.remove(ref.key)
                return
            }
            val preview = previews.existing(ref.key) ?: loadPreview(src, ref, request)
            findings[ref.key] = Finding(ref, src.displayName, src.canDelete, hit.scores, preview)
            return
        }

        val frames = loadFramesSafely(src, ref, request) ?: return
        if (frames.isEmpty()) {
            skipped.incrementAndGet()
            return
        }
        try {
            val scored = frames.map { it to classifier.classify(it) }
            val (bestFrame, best) = scored.maxBy { it.second.unsafe }
            scanned.incrementAndGet()
            val flagged = best.unsafe >= threshold
            runCatching { store.upsert(ref, src.displayName, src.canDelete, best, flagged, runId) }
            val dismissed = runCatching { store.get(ref.key)?.dismissed }.getOrNull() == true
            if (flagged && !dismissed) {
                val preview = previews.save(ref.key, bestFrame)
                findings[ref.key] = Finding(ref, src.displayName, src.canDelete, best, preview)
            } else {
                findings.remove(ref.key)
                previews.remove(ref.key)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errors.incrementAndGet()
        } finally {
            frames.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private suspend fun loadFramesSafely(src: MediaSource, ref: MediaRef, request: FrameRequest): List<Bitmap>? =
        try {
            src.loadFrames(ref, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRequiredException) {
            throw e
        } catch (e: OutOfMemoryError) {
            errors.incrementAndGet()
            null
        } catch (e: Exception) {
            errors.incrementAndGet()
            null
        }

    private suspend fun loadPreview(src: MediaSource, ref: MediaRef, request: FrameRequest): java.io.File? {
        val frames = loadFramesSafely(src, ref, request.copy(videoFrames = 1)) ?: return null
        return try {
            frames.firstOrNull()?.let { previews.save(ref.key, it) }
        } finally {
            frames.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    suspend fun delete(keys: Collection<String>): DeleteReport {
        var deleted = 0
        var trashed = 0
        val unsupported = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (key in keys) {
            val finding = findings[key] ?: continue
            val src = sources[finding.ref.sourceId] ?: sourceResolver(finding.ref.sourceId)?.also { sources[it.id] = it }
            if (src == null) {
                failed += "${finding.ref.name}: fonte desativada ou removida"
                continue
            }
            val result = try {
                src.delete(finding.ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeleteResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            when (result) {
                DeleteResult.Deleted -> { deleted++; forget(key) }
                DeleteResult.Trashed -> { trashed++; forget(key) }
                is DeleteResult.Unsupported -> unsupported += result.reason
                is DeleteResult.Failed -> failed += "${finding.ref.name}: ${result.message}"
            }
        }
        publish()
        return DeleteReport(deleted, trashed, unsupported, failed)
    }

    suspend fun viewTarget(key: String, videoStream: Boolean): ViewTarget {
        val finding = findings[key] ?: throw IllegalStateException("Item não está mais na lista")
        val src = sources[finding.ref.sourceId] ?: sourceResolver(finding.ref.sourceId)?.also { sources[it.id] = it }
            ?: throw IllegalStateException("Fonte \"${finding.sourceName}\" desativada ou removida")
        return src.viewTarget(finding.ref, videoStream)
    }

    fun dismiss(keys: Collection<String>) {
        runCatching { store.dismiss(keys) }
        keys.forEach { dropLocal(it) }
        publish()
    }

    fun resetCache() {
        if (running) return
        runCatching { store.clear() }
        previews.clear()
        findings.clear()
        publish()
    }

    private fun forget(key: String) {
        runCatching { store.remove(listOf(key)) }
        dropLocal(key)
    }

    private fun dropLocal(key: String) {
        findings.remove(key)
        previews.remove(key)
    }

    private fun publish() {
        val list = findings.values.sortedByDescending { it.scores.unsafe }
        _state.value = ScanState(
            running = running,
            scanned = scanned.get(),
            cached = cached.get(),
            flagged = list.size,
            skipped = skipped.get(),
            errors = errors.get(),
            currentSource = current,
            sourceErrors = synchronized(sourceErrors) { sourceErrors.toList() },
            findings = list,
            finishedAt = finishedAt,
        )
    }

    companion object {
        private const val PUBLISH_MS = 400L
    }
}
