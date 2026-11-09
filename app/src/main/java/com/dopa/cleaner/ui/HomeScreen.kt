package com.dopa.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.FilterChip
import com.dopa.cleaner.config.CustomSourceConfig
import com.dopa.cleaner.config.ScanSchedule

@Composable
fun DopaRoot(vm: MainViewModel, onRequestAllFiles: () -> Unit, onAddFolder: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("home") }
    BackHandler(enabled = screen != "home") { screen = "home" }
    when (screen) {
        "results" -> ResultsScreen(vm, onBack = { screen = "home" })
        else -> HomeScreen(vm, onRequestAllFiles, onAddFolder, onOpenResults = { screen = "results" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onRequestAllFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenResults: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val hasAllFiles by vm.hasAllFiles.collectAsStateWithLifecycle()
    val driveConnected by vm.driveConnected.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val photosPicking by vm.photosPicking.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showWebDav by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Dopa Cleaner") }) }) { pad ->
        val s = settings
        if (s == null) {
            Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasAllFiles) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Acesso a todos os arquivos necessário", style = MaterialTheme.typography.titleSmall)
                            }
                            Text("Sem isso o app não lê memória interna, cartão SD nem USB.", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onRequestAllFiles) { Text("Conceder") }
                        }
                    }
                }
            }

            item { SectionTitle("Fontes") }
            item {
                SourceRow(Icons.Default.PhoneAndroid, "Aparelho (todas as unidades)", "Interna, SD, USB OTG", s.localEnabled, vm::setLocal)
            }
            item {
                SourceRow(
                    Icons.Default.Cloud, "Google Drive",
                    if (driveConnected) "Conectado · exclusão vai para a lixeira do Drive" else "Não conectado",
                    s.driveEnabled, vm::setDrive,
                )
            }
            item {
                val p = picked
                val sub = when {
                    p == null -> "Seleção manual obrigatória (limite da API do Google)"
                    p.expired -> "Seleção expirada — selecione novamente"
                    else -> "${p.items.size} itens selecionados · exclusão manual"
                }
                Column {
                    SourceRow(Icons.Default.Photo, "Google Fotos", sub, s.photosEnabled, vm::setPhotos)
                    Row(Modifier.padding(start = 40.dp)) {
                        OutlinedButton(onClick = vm::pickPhotos, enabled = busy == null) { Text("Selecionar mídias") }
                    }
                }
            }
            items(s.custom, key = { it.id }) { c ->
                val icon = if (c is CustomSourceConfig.WebDav) Icons.Default.Storage else Icons.Default.Folder
                val sub = when (c) {
                    is CustomSourceConfig.WebDav -> "WebDAV · ${c.baseUrl}"
                    is CustomSourceConfig.SafFolder -> "Pasta do sistema/nuvem"
                }
                SourceRow(icon, c.name, sub, c.id !in s.disabledCustom, { vm.setCustomEnabled(c.id, it) }) {
                    IconButton(onClick = { vm.removeCustom(c.id) }) { Icon(Icons.Default.Delete, "Remover") }
                }
            }
            item {
                OutlinedButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Adicionar fonte na nuvem")
                }
            }

            item { SectionTitle("Sensibilidade") }
            item { ThresholdSlider(s.threshold, vm::setThreshold) }

            item { SectionTitle("Agendamento") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ScanSchedule.OFF to "Desligado", ScanSchedule.DAILY to "Diário", ScanSchedule.WEEKLY to "Semanal").forEach { (v, label) ->
                            FilterChip(selected = s.schedule == v, onClick = { vm.setSchedule(v) }, label = { Text(label) })
                        }
                    }
                    Text("Roda carregando e com bateria ok. Itens sem alteração são pulados.", style = MaterialTheme.typography.bodySmall)
                }
            }

            item { SectionTitle("Varredura") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scan.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Fonte: ${scan.currentSource ?: "…"}")
                    }
                    Text("${scan.scanned} analisados · ${scan.cached} sem alteração · ${scan.flagged} sinalizados · ${scan.skipped} ignorados · ${scan.errors} erros")
                    scan.sourceErrors.forEach {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (scan.running) {
                            Button(onClick = vm::cancelScan) { Text("Cancelar") }
                        } else {
                            Button(onClick = { vm.startScan() }, enabled = busy == null) { Text("Escanear") }
                        }
                        OutlinedButton(onClick = onOpenResults, enabled = scan.flagged > 0) {
                            Text("Resultados (${scan.flagged})")
                        }
                    }
                    TextButton(onClick = vm::resetCache, enabled = !scan.running) { Text("Limpar cache e resultados") }
                }
            }
            busy?.let { msg ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (photosPicking) TextButton(onClick = vm::cancelPhotos) { Text("Parar") }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Adicionar fonte") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WebDAV: Nextcloud, ownCloud, Koofr, pCloud, Yandex, 4shared, NAS etc.", style = MaterialTheme.typography.bodySmall)
                    Text("Pasta: qualquer app que exponha armazenamento ao seletor do Android (OneDrive, Dropbox, Nextcloud, MEGA…).", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdd = false; showWebDav = true }) { Text("WebDAV") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; onAddFolder() }) { Text("Pasta") }
            },
        )
    }
    if (showWebDav) WebDavDialog(vm, onClose = { showWebDav = false })
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ThresholdSlider(value: Float, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("Limite: ${(local * 100).toInt()}% — menor = mais sensível (mais falsos positivos)", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = 0.30f..0.98f,
        )
    }
}

@Composable
private fun WebDavDialog(vm: MainViewModel, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("https://") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!testing) onClose() },
        title = { Text("Fonte WebDAV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(
                    url, { url = it }, label = { Text("URL") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(user, { user = it }, label = { Text("Usuário") }, singleLine = true)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Senha / app password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing && url.length > 8,
                onClick = {
                    testing = true
                    vm.addWebDav(name, url, user, pass) { ok ->
                        testing = false
                        if (ok) onClose()
                    }
                },
            ) { Text(if (testing) "Testando…" else "Testar e salvar") }
        },
        dismissButton = { TextButton(enabled = !testing, onClick = onClose) { Text("Cancelar") } },
    )
}
