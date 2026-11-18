package com.dopa.cleaner.ui

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopa.cleaner.core.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val visible by vm.visibleFindings.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var reveal by remember(settings?.blurPreviews) { mutableStateOf(settings?.blurPreviews == false) }
    var confirm by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(filter.query.isNotEmpty()) }
    val sourceNames = remember(scan.findings) { scan.findings.map { it.sourceName }.distinct().sorted() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("${visible.size} de ${scan.findings.size}") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Default.Search, "Buscar") }
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Revelar")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortOrder.entries.forEach { o ->
                                    DropdownMenuItem(
                                        text = { Text(if (filter.sort == o) "✓ ${o.label}" else o.label) },
                                        onClick = { vm.updateFilter { it.copy(sort = o) }; sortMenu = false },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = vm::selectVisible) { Icon(Icons.Default.SelectAll, "Selecionar visíveis") }
                        IconButton(onClick = vm::selectNone) { Icon(Icons.Outlined.Deselect, "Limpar seleção") }
                    },
                )
                if (showSearch) {
                    OutlinedTextField(
                        value = filter.query,
                        onValueChange = { q -> vm.updateFilter { it.copy(query = q) } },
                        placeholder = { Text("Nome do arquivo ou pasta") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (filter.query.isNotEmpty()) {
                                IconButton(onClick = { vm.updateFilter { it.copy(query = "") } }) { Icon(Icons.Default.Clear, "Limpar") }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MediaFilter.entries.forEach { m ->
                        FilterChip(selected = filter.media == m, onClick = { vm.updateFilter { it.copy(media = m) } }, label = { Text(m.label) })
                    }
                    if (sourceNames.size > 1) {
                        FilterChip(selected = filter.source == null, onClick = { vm.updateFilter { it.copy(source = null) } }, label = { Text("Todas as fontes") })
                        sourceNames.forEach { name ->
                            FilterChip(
                                selected = filter.source == name,
                                onClick = { vm.updateFilter { it.copy(source = if (it.source == name) null else name) } },
                                label = { Text(name.take(18)) },
                            )
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Risco mín. ${(filter.minRisk * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = filter.minRisk,
                        onValueChange = { v -> vm.updateFilter { it.copy(minRisk = v) } },
                        valueRange = 0f..0.99f,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }
        },
        bottomBar = {
            BottomAppBar {
                Text("${selected.size} selecionados", Modifier.padding(start = 16.dp).weight(1f))
                OutlinedButton(onClick = vm::dismissSelected, enabled = selected.isNotEmpty() && busy == null) { Text("Ignorar") }
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
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nada com esses filtros", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { it.ref.key }) { f ->
                        FindingTile(
                            finding = f,
                            selected = f.ref.key in selected,
                            reveal = reveal,
                            onClick = { if (selected.isNotEmpty()) vm.toggle(f.ref.key) else onOpen(f.ref.key) },
                            onLongClick = { vm.toggle(f.ref.key) },
                            onToggle = { vm.toggle(f.ref.key) },
                        )
                    }
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
    onToggle: () -> Unit,
) {
    val bitmap = rememberPreview(finding.preview)
    val shape = RoundedCornerShape(12.dp)
    val riskColor = when {
        finding.risk >= 0.9f -> Color(0xFFD32F2F)
        finding.risk >= 0.75f -> Color(0xFFF57C00)
        else -> Color(0xFF7B1FA2)
    }
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
        Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(riskColor).padding(horizontal = 6.dp, vertical = 2.dp),
        ) { Text(pct(finding.risk), color = Color.White, style = MaterialTheme.typography.labelSmall) }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (finding.ref.isVideo) Icon(Icons.Default.Movie, null, tint = Color.White, modifier = Modifier.padding(end = 2.dp))
            Text(
                if (finding.canDelete) finding.sourceName.take(14) else "manual",
                color = if (finding.canDelete) Color.White else Color(0xFFFFB4AB),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        IconButton(onClick = onToggle, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                "Selecionar",
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
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
