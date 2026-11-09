package com.dopa.cleaner.scan

import android.graphics.Bitmap
import com.dopa.cleaner.classifier.NsfwClassifier
import com.dopa.cleaner.core.AuthRequiredException
import com.dopa.cleaner.core.DeleteResult
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ScanState(
    val running: Boolean = false,
    val scanned: Int = 0,
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var job: Job? = null
    private val sources = ConcurrentHashMap<String, MediaSource>()
    private val findings = ConcurrentHashMap<String, Finding>()
    private val sourceErrors: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scanned = AtomicInteger()
    private val skipped = AtomicInteger()
    private val errors = AtomicInteger()
    @Volatile private var current: String? = null
    @Volatile private var running = false
    @Volatile private var finishedAt: Long? = null

    @Synchronized
    fun start(list: List<MediaSource>, threshold: Float): Boolean {
        if (job?.isActive == true) return false
        require(list.isNotEmpty()) { "Nenhuma fonte selecionada" }
        findings.clear()
        previews.clear()
        sources.clear()
        list.forEach { sources[it.id] = it }
        sourceErrors.clear()
        scanned.set(0); skipped.set(0); errors.set(0)
        current = null
        finishedAt = null
        running = true
        publish()
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
                    try {
                        scanSource(src, classifier, threshold)
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

    private suspend fun scanSource(src: MediaSource, classifier: NsfwClassifier, threshold: Float) = coroutineScope {
        val channel = Channel<MediaRef>(capacity = 64)
        launch {
            try {
                src.enumerate().collect { channel.send(it) }
            } finally {
                channel.close()
            }
        }
        repeat(WORKERS) {
            launch(Dispatchers.IO) {
                for (ref in channel) process(src, ref, classifier, threshold)
            }
        }
    }

    private suspend fun process(src: MediaSource, ref: MediaRef, classifier: NsfwClassifier, threshold: Float) {
        val frames: List<Bitmap> = try {
            src.loadFrames(ref, FRAME_PX)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRequiredException) {
            throw e
        } catch (e: OutOfMemoryError) {
            errors.incrementAndGet()
            return
        } catch (e: Exception) {
            errors.incrementAndGet()
            return
        }
        if (frames.isEmpty()) {
            skipped.incrementAndGet()
            return
        }
        try {
            val scored = frames.map { it to classifier.classify(it) }
            val (bestFrame, best) = scored.maxBy { it.second.unsafe }
            scanned.incrementAndGet()
            if (best.unsafe >= threshold) {
                val preview = previews.save(ref.key, bestFrame)
                findings[ref.key] = Finding(ref, src.displayName, src.canDelete, best, preview)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errors.incrementAndGet()
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
            val src = sources[finding.ref.sourceId]
            if (src == null) {
                failed += "${finding.ref.name}: fonte indisponível"
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
                DeleteResult.Deleted -> { deleted++; drop(key) }
                DeleteResult.Trashed -> { trashed++; drop(key) }
                is DeleteResult.Unsupported -> unsupported += result.reason
                is DeleteResult.Failed -> failed += "${finding.ref.name}: ${result.message}"
            }
        }
        publish()
        return DeleteReport(deleted, trashed, unsupported, failed)
    }

    fun dismiss(keys: Collection<String>) {
        keys.forEach { drop(it) }
        publish()
    }

    private fun drop(key: String) {
        findings.remove(key)
        previews.remove(key)
    }

    private fun publish() {
        val list = findings.values.sortedByDescending { it.scores.unsafe }
        _state.value = ScanState(
            running = running,
            scanned = scanned.get(),
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
        private const val WORKERS = 4
        private const val FRAME_PX = 256
        private const val PUBLISH_MS = 400L
    }
}
