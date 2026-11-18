package com.dopa.cleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopa.cleaner.config.CustomSourceConfig
import com.dopa.cleaner.scan.ScanState

@Composable
fun DopaRoot(vm: MainViewModel, onRequestAllFiles: () -> Unit, onAddFolder: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("home") }
    val back: () -> Unit = {
        screen = if (screen.startsWith(VIEWER_PREFIX)) "results" else "home"
    }
    BackHandler(enabled = screen != "home", onBack = back)
    when {
        screen == "results" -> ResultsScreen(vm, onBack = back, onOpen = { key -> screen = VIEWER_PREFIX + key })
        screen == "settings" -> SettingsScreen(vm, onBack = back)
        screen.startsWith(VIEWER_PREFIX) -> ViewerScreen(vm, startKey = screen.removePrefix(VIEWER_PREFIX), onBack = back)
        else -> HomeScreen(
            vm, onRequestAllFiles, onAddFolder,
            onOpenResults = { screen = "results" },
            onOpenSettings = { screen = "settings" },
        )
    }
}

private const val VIEWER_PREFIX = "viewer:"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onRequestAllFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenResults: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val hasAllFiles by vm.hasAllFiles.collectAsStateWithLifecycle()
    val batteryExempt by vm.batteryExempt.collectAsStateWithLifecycle()
    val driveConnected by vm.driveConnected.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val photosPicking by vm.photosPicking.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showWebDav by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dopa Cleaner", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Configurações") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { pad ->
        val s = settings
        if (s == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScanDashboard(
                    scan = scan,
                    modeLabel = "Modo ${s.options.speed.label}${if (s.options.deepScan) " · profunda" else ""} · limite ${(s.threshold * 100).toInt()}%",
                    enabled = busy == null,
                    onStart = { vm.startScan() },
                    onCancel = vm::cancelScan,
                    onOpenResults = onOpenResults,
                )
            }
            if (!hasAllFiles) {
                item {
                    WarningCard(
                        title = "Acesso a todos os arquivos necessário",
                        body = "Sem isso o app não lê memória interna, cartão SD nem USB.",
                        action = "Conceder",
                        onAction = onRequestAllFiles,
                    )
                }
            }
            if (!batteryExempt) {
                item {
                    WarningCard(
                        title = "Economia de bateria ativa",
                        body = "O sistema pode pausar a varredura com a tela apagada. Libere em Configurações > Segundo plano.",
                        action = "Abrir",
                        onAction = onOpenSettings,
                    )
                }
            }
            scan.sourceErrors.takeIf { it.isNotEmpty() }?.let { errs ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            errs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            item { SectionTitle("Fontes") }
            item {
                SourceCard(Icons.Default.PhoneAndroid, "Aparelho", "Memória interna, SD e USB", s.localEnabled, vm::setLocal)
            }
            item {
                SourceCard(
                    Icons.Default.Cloud, "Google Drive",
                    if (driveConnected) "Conectado · exclusão vai para a lixeira" else "Não conectado — toque para conectar",
                    s.driveEnabled, vm::setDrive,
                )
            }
            item {
                val p = picked
                val sub = when {
                    p == null -> "Requer seleção manual das mídias"
                    p.expired -> "Seleção expirada — selecione de novo"
                    else -> "${p.items.size} itens selecionados · exclusão manual"
                }
                SourceCard(Icons.Default.Photo, "Google Fotos", sub, s.photosEnabled, vm::setPhotos) {
                    TextButton(onClick = vm::pickPhotos, enabled = busy == null) { Text("Selecionar") }
                }
            }
            items(s.custom, key = { it.id }) { c ->
                val icon = if (c is CustomSourceConfig.WebDav) Icons.Default.Storage else Icons.Default.Folder
                val sub = when (c) {
                    is CustomSourceConfig.WebDav -> "WebDAV · ${c.baseUrl}"
                    is CustomSourceConfig.SafFolder -> "Pasta do sistema/nuvem"
                }
                SourceCard(icon, c.name, sub, c.id !in s.disabledCustom, { vm.setCustomEnabled(c.id, it) }) {
                    IconButton(onClick = { vm.removeCustom(c.id) }) { Icon(Icons.Default.Delete, "Remover") }
                }
            }
            item {
                OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Adicionar nuvem ou pasta")
                }
            }
            busy?.let { msg ->
                item {
                    Card {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (photosPicking) TextButton(onClick = vm::cancelPhotos) { Text("Parar") }
                        }
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
                    Text("Pasta: qualquer app que apareça no seletor do Android (OneDrive, Dropbox, Nextcloud, MEGA…).", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showAdd = false; showWebDav = true }) { Text("WebDAV") } },
            dismissButton = { TextButton(onClick = { showAdd = false; onAddFolder() }) { Text("Pasta") } },
        )
    }
    if (showWebDav) WebDavDialog(vm, onClose = { showWebDav = false })
}

@Composable
private fun ScanDashboard(
    scan: ScanState,
    modeLabel: String,
    enabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenResults: () -> Unit,
) {
    val gradient = Brush.linearGradient(listOf(Color(0xFF4A148C), Color(0xFF7B1FA2), Color(0xFFAB47BC)))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                    val progress = scan.progress
                    when {
                        scan.running && progress != null -> CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(96.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f),
                            strokeWidth = 8.dp,
                        )
                        scan.running -> CircularProgressIndicator(Modifier.size(96.dp), color = Color.White, strokeWidth = 8.dp)
                        else -> Box(
                            Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                    }
                    if (scan.running) {
                        Text(
                            scan.progress?.let { "${(it * 100).toInt()}%" } ?: "${scan.processed}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            scan.running -> "Varrendo ${scan.currentSource ?: "…"}"
                            scan.finishedAt != null -> "Última varredura concluída"
                            else -> "Pronto para varrer"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(modeLabel, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    if (scan.running || scan.finishedAt != null) {
                        Text(
                            "${"%.1f".format(scan.perSecond)} itens/s${if (scan.usingGpu) " · GPU" else ""}",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (scan.running) Text("Pode sair do app: continua em segundo plano", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Stat("Analisados", scan.scanned)
                Stat("Sem mudança", scan.cached)
                Stat("Sinalizados", scan.flagged)
                Stat("Erros", scan.errors)
            }
            if (scan.running && scan.progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (scan.running) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF4A148C)),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Text(" Parar")
                    }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF4A148C)),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(" Escanear", fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onOpenResults,
                    enabled = scan.flagged > 0,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Resultados (${scan.flagged})") }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WarningCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
            Switch(checked = checked, onCheckedChange = onChecked)
        }
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
