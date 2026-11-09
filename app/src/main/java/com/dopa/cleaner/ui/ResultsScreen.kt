package com.dopa.cleaner.ui

import android.graphics.BitmapFactory
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopa.cleaner.core.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var reveal by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Finding?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${scan.findings.size} sinalizados") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                actions = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Revelar")
                    }
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Default.SelectAll, "Selecionar tudo") }
                    IconButton(onClick = vm::selectNone) { Icon(Icons.Outlined.Deselect, "Limpar seleção") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Text("${selected.size} selecionados", Modifier.padding(start = 16.dp).weight(1f))
                OutlinedButton(onClick = vm::dismissSelected, enabled = selected.isNotEmpty() && busy == null) {
                    Text("Ignorar")
                }
                Button(
                    onClick = { confirm = true },
                    enabled = selected.isNotEmpty() && busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.Delete, null)
                    Text("Excluir")
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (scan.running || busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            busy?.let { Text(it, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(scan.findings, key = { it.ref.key }) { f ->
                    FindingTile(
                        finding = f,
                        selected = f.ref.key in selected,
                        reveal = reveal,
                        onClick = { vm.toggle(f.ref.key) },
                        onLongClick = { details = f },
                    )
                }
            }
        }
    }

    if (confirm) {
        val chosen = scan.findings.filter { it.ref.key in selected }
        val manual = chosen.count { !it.canDelete }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Excluir ${chosen.size} itens?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Arquivos locais, WebDAV e pastas são apagados permanentemente.")
                    Text("Google Drive: movidos para a lixeira (30 dias).")
                    if (manual > 0) Text("$manual do Google Fotos não podem ser apagados por API: exclua no app Fotos.")
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.deleteSelected() }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }

    details?.let { f ->
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(f.ref.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Fonte: ${f.sourceName}")
                    Text("Local: ${f.ref.location}")
                    f.ref.sizeBytes?.let { Text("Tamanho: ${Formatter.formatShortFileSize(ctx, it)}") }
                    Text("Tipo: ${f.ref.mimeType}")
                    Text("Risco: ${pct(f.scores.unsafe)}")
                    Text(
                        "porn ${pct(f.scores.porn)} · hentai ${pct(f.scores.hentai)} · sexy ${pct(f.scores.sexy)} · " +
                            "neutral ${pct(f.scores.neutral)} · drawings ${pct(f.scores.drawings)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!f.canDelete) Text("Exclusão manual necessária.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Fechar") } },
        )
    }
}

private fun pct(v: Float) = "${(v * 100).toInt()}%"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FindingTile(
    finding: Finding,
    selected: Boolean,
    reveal: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bitmap = rememberPreview(finding.preview)
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (bitmap != null && (reveal || canBlur)) {
            Image(
                bitmap = bitmap,
                contentDescription = finding.ref.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (reveal) Modifier else Modifier.blur(24.dp)),
            )
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (finding.ref.isVideo) Icon(Icons.Default.Movie, null, tint = Color.White, modifier = Modifier.padding(end = 2.dp))
            Text(pct(finding.scores.unsafe), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(
                if (finding.canDelete) finding.sourceName.take(10) else "manual",
                color = if (finding.canDelete) Color.White else Color(0xFFFFB4AB),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
            null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )
    }
}

@Composable
private fun rememberPreview(file: File?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
        }
    }
    return state.value
}
