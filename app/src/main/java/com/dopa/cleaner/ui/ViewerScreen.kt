package com.dopa.cleaner.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.dopa.cleaner.core.BitmapLoader
import com.dopa.cleaner.core.Finding
import com.dopa.cleaner.core.Http
import com.dopa.cleaner.core.ViewTarget
import com.dopa.cleaner.core.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ok<T>(val value: T) : Loaded<T>
    data class Error(val message: String) : Loaded<Nothing>
}

@Composable
fun ViewerScreen(vm: MainViewModel, startKey: String, onBack: () -> Unit) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val findings = scan.findings
    if (findings.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val startIndex = remember(startKey) { findings.indexOfFirst { it.ref.key == startKey }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { findings.size }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = findings.getOrNull(pager.currentPage.coerceIn(0, findings.lastIndex))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { findings.getOrNull(it)?.ref?.key ?: it }, modifier = Modifier.fillMaxSize()) { page ->
            val f = findings.getOrNull(page) ?: return@HorizontalPager
            if (f.ref.isVideo) {
                VideoPage(vm, f, active = page == pager.currentPage)
            } else {
                ImagePage(vm, f)
            }
        }

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(current?.ref?.name.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${pager.currentPage + 1}/${findings.size} · risco ${pct(current?.scores?.unsafe ?: 0f)}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { showInfo = !showInfo }) { Icon(Icons.Default.Info, "Detalhes", tint = Color.White) }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).navigationBarsPadding()) {
            if (showInfo && current != null) InfoPanel(current)
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val key = current?.ref?.key
                val isSelected = key != null && key in selected
                TextButton(onClick = { key?.let(vm::dismissOne) }, enabled = key != null) {
                    Icon(Icons.Default.VisibilityOff, null, tint = Color.White)
                    Text("  Ignorar", color = Color.White)
                }
                TextButton(onClick = { key?.let(vm::toggle) }, enabled = key != null) {
                    Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle, null, tint = MaterialTheme.colorScheme.primary)
                    Text(if (isSelected) "  Selecionado" else "  Selecionar", color = Color.White)
                }
                TextButton(onClick = { confirmDelete = true }, enabled = key != null && current.canDelete) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Text("  Excluir", color = Color.White)
                }
            }
        }
    }

    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir \"${current.ref.name}\"?") },
            text = { Text(if (current.sourceName == "Google Drive") "Vai para a lixeira do Drive." else "Exclusão permanente.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteOne(current.ref.key) }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun InfoPanel(f: Finding) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Fonte: ${f.sourceName}", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Text("Local: ${f.ref.location}", color = Color.White, style = MaterialTheme.typography.bodySmall)
        f.ref.sizeBytes?.let { Text("Tamanho: ${Formatter.formatShortFileSize(ctx, it)}", color = Color.White, style = MaterialTheme.typography.bodySmall) }
        Text(
            "porn ${pct(f.scores.porn)} · hentai ${pct(f.scores.hentai)} · sexy ${pct(f.scores.sexy)} · neutral ${pct(f.scores.neutral)} · drawings ${pct(f.scores.drawings)}",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
        if (!f.canDelete) Text("Exclusão manual necessária no app de origem.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun pct(v: Float) = "${(v * 100).toInt()}%"

private suspend fun loadFullImage(context: Context, target: ViewTarget): Bitmap = withContext(Dispatchers.IO) {
    when (target) {
        is ViewTarget.Local -> BitmapLoader.decodeUri(context, target.uri, VIEW_PX)
        is ViewTarget.Remote -> {
            val req = Request.Builder().url(target.url).apply { target.headers.forEach { (k, v) -> header(k, v) } }.build()
            BitmapLoader.decodeBytes(Http.client.readBytes(req, limit = 60L * 1024 * 1024), VIEW_PX)
        }
    }
}

private const val VIEW_PX = 2048

@Composable
private fun ImagePage(vm: MainViewModel, f: Finding) {
    val ctx = LocalContext.current
    val state by produceState<Loaded<Bitmap>>(Loaded.Loading, f.ref.key) {
        value = vm.viewTarget(f.ref.key, videoStream = false).fold(
            onSuccess = { t ->
                runCatching { loadFullImage(ctx, t) }.fold(
                    onSuccess = { Loaded.Ok(it) },
                    onFailure = { Loaded.Error(it.message ?: it.javaClass.simpleName) },
                )
            },
            onFailure = { Loaded.Error(it.message ?: it.javaClass.simpleName) },
        )
    }
    when (val s = state) {
        Loaded.Loading -> Centered { CircularProgressIndicator() }
        is Loaded.Error -> Centered { Text("Não foi possível abrir: ${s.message}", color = Color.White, modifier = Modifier.padding(24.dp)) }
        is Loaded.Ok -> ZoomableImage(s.value)
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(vm: MainViewModel, f: Finding, active: Boolean) {
    val ctx = LocalContext.current
    val state by produceState<Loaded<ViewTarget>>(Loaded.Loading, f.ref.key) {
        value = vm.viewTarget(f.ref.key, videoStream = true).fold(
            onSuccess = { Loaded.Ok(it) },
            onFailure = { Loaded.Error(it.message ?: it.javaClass.simpleName) },
        )
    }
    when (val s = state) {
        Loaded.Loading -> Centered { CircularProgressIndicator() }
        is Loaded.Error -> Centered { Text("Não foi possível abrir: ${s.message}", color = Color.White, modifier = Modifier.padding(24.dp)) }
        is Loaded.Ok -> {
            val target = s.value
            val player = remember(target) {
                val dataSource: DataSource.Factory = when (target) {
                    is ViewTarget.Local -> DefaultDataSource.Factory(ctx)
                    is ViewTarget.Remote -> OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(target.headers)
                }
                val uri = when (target) {
                    is ViewTarget.Local -> target.uri
                    is ViewTarget.Remote -> android.net.Uri.parse(target.url)
                }
                ExoPlayer.Builder(ctx)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                    .build()
                    .apply {
                        setMediaItem(MediaItem.fromUri(uri))
                        prepare()
                    }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            LaunchedEffect(active) { player.playWhenReady = active }
            AndroidView(
                factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
