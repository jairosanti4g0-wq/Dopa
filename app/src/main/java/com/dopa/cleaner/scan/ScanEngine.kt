package com.dopa.cleaner.scan

import android.graphics.Bitmap
import com.dopa.cleaner.classifier.NsfwClassifier
import com.dopa.cleaner.core.AuthRequiredException
import com.dopa.cleaner.config.ScanOptions
import com.dopa.cleaner.config.ScanSpeed
import com.dopa.cleaner.core.RiskCategories
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

data class ScanConfig(
    val threshold: Float,
    val speed: ScanSpeed = ScanSpeed.BALANCED,
    val categories: RiskCategories = RiskCategories(),
    val options: ScanOptions = ScanOptions(),
    val useGpu: Boolean = false,
)

data class ScanState(
    val running: Boolean = false,
    val total: Int = 0,
    val totalKnown: Boolean = false,
    val startedAt: Long? = null,
    val usingGpu: Boolean = false,
    val scanned: Int = 0,
    val cached: Int = 0,
    val flagged: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val currentSource: String? = null,
    val sourceErrors: List<String> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val finishedAt: Long? = null,
) {
    val processed: Int get() = scanned + cached + skipped + errors
    val progress: Float? get() = if (totalKnown && total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else null
    val perSecond: Float
        get() {
            val start = startedAt ?: return 0f
            val end = finishedAt ?: System.currentTimeMillis()
            val secs = ((end - start) / 1000f).coerceAtLeast(1f)
            return processed / secs
        }
}

data class DeleteReport(
    val deleted: Int,
    val trashed: Int,
    val unsupported: List<String>,
    val failed: List<String>,
)

class ScanEngine(
    private val classifierProvider: suspend (Boolean) -> NsfwClassifier,
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
    @Volatile private var startedAt: Long? = null
    @Volatile private var usingGpu = false
    private val total = AtomicInteger()
    @Volatile private var totalKnown = false

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
    fun start(list: List<MediaSource>, config: ScanConfig): Boolean {
        val speed = config.speed
        if (job?.isActive == true) return false
        require(list.isNotEmpty()) { "Nenhuma fonte selecionada" }
        list.forEach { sources[it.id] = it }
        sourceErrors.clear()
        scanned.set(0); cached.set(0); skipped.set(0); errors.set(0)
        current = null
        finishedAt = null
        startedAt = System.currentTimeMillis()
        total.set(0)
        totalKnown = false
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
                val classifier = classifierProvider(config.useGpu)
                usingGpu = classifier.usingGpu
                val counts = list.map { runCatching { it.estimateCount() }.getOrNull() }
                total.set(counts.sumOf { it ?: 0 })
                totalKnown = counts.all { it != null }
                for (src in list) {
                    current = src.displayName
                    val errorsBefore = errors.get()
                    try {
                        scanSource(src, classifier, config, runId, request)
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
        config: ScanConfig,
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
                for (ref in channel) process(src, ref, classifier, config, runId, request)
            }
        }
    }

    private suspend fun process(
        src: MediaSource,
        ref: MediaRef,
        classifier: NsfwClassifier,
        config: ScanConfig,
        runId: Long,
        request: FrameRequest,
    ) {
        val threshold = config.threshold
        if (config.options.isExcluded(ref.itemId) || config.options.isExcluded(ref.location)) {
            skipped.incrementAndGet()
            return
        }
        val w = ref.width
        val h = ref.height
        if (!ref.isVideo && w != null && h != null && w > 0 && h > 0 && minOf(w, h) < config.options.minImagePx) {
            skipped.incrementAndGet()
            return
        }
        val hit = ref.version?.let { v -> runCatching { store.get(ref.key) }.getOrNull()?.takeIf { it.ref.version == v } }
        if (hit != null) {
            cached.incrementAndGet()
            val hitRisk = hit.scores.risk(config.categories)
            val flagged = hitRisk >= threshold
            runCatching { store.touch(ref.key, runId, flagged, src.displayName, src.canDelete) }
            if (!flagged || hit.dismissed) {
                findings.remove(ref.key)
                return
            }
            val preview = previews.existing(ref.key) ?: loadPreview(src, ref, request)
            findings[ref.key] = Finding(ref, src.displayName, src.canDelete, hit.scores, preview, hitRisk)
            return
        }

        val frames = loadFramesSafely(src, ref, request) ?: return
        if (frames.isEmpty()) {
            skipped.incrementAndGet()
            return
        }
        try {
            val scored = frames.map { it to classifier.classify(it) }
            val (bestFrame, best) = scored.maxBy { it.second.risk(config.categories) }
            scanned.incrementAndGet()
            val bestRisk = best.risk(config.categories)
            val flagged = bestRisk >= threshold
            runCatching { store.upsert(ref, src.displayName, src.canDelete, best, flagged, runId) }
            val dismissed = runCatching { store.get(ref.key)?.dismissed }.getOrNull() == true
            if (flagged && !dismissed) {
                val preview = previews.save(ref.key, bestFrame)
                findings[ref.key] = Finding(ref, src.displayName, src.canDelete, best, preview, bestRisk)
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
        val list = findings.values.sortedByDescending { it.risk }
        _state.value = ScanState(
            running = running,
            total = total.get(),
            totalKnown = totalKnown,
            startedAt = startedAt,
            usingGpu = usingGpu,
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
