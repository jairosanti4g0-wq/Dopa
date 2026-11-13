package com.dopa.cleaner.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dopa.cleaner.config.ScanSchedule
import com.dopa.cleaner.config.ScanSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val batteryExempt by vm.batteryExempt.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { pad ->
        val s = settings ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Section("Velocidade da varredura") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScanSpeed.entries.forEach { v ->
                            FilterChip(selected = s.options.speed == v, onClick = { vm.setSpeed(v) }, label = { Text(v.label) })
                        }
                    }
                    Text(
                        when (s.options.speed) {
                            ScanSpeed.FAST -> "Usa a miniatura embutida das fotos e 1 quadro por vídeo. Muito mais rápido, um pouco menos preciso."
                            ScanSpeed.BALANCED -> "Miniatura embutida das fotos e 2 quadros por vídeo."
                            ScanSpeed.PRECISE -> "Decodifica cada foto e 3 quadros por vídeo. Mais lento."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                ToggleRow("Pular pastas de miniaturas (.thumbnails)", "Evita analisar cópias reduzidas da galeria", s.options.skipThumbnailDirs, vm::setSkipThumbs)
            }
            item {
                ToggleRow("Pular pastas ocultas", "Pastas que começam com ponto (cache de apps)", s.options.skipHiddenDirs, vm::setSkipHidden)
            }
            item { MinSizeSlider(s.options.minFileKb, vm::setMinKb) }

            item { HorizontalDivider() }
            item { Section("Sensibilidade") }
            item { ThresholdSlider(s.threshold, vm::setThreshold) }

            item { HorizontalDivider() }
            item { Section("Segundo plano") }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (batteryExempt) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryChargingFull, null)
                            Text(
                                if (batteryExempt) "  Sem restrição de bateria: a varredura continua com a tela apagada"
                                else "  Otimização de bateria ativa: o sistema pode pausar a varredura com a tela apagada",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (!batteryExempt) {
                            Button(onClick = { requestBatteryExemption(ctx) }) { Text("Liberar segundo plano") }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Agendamento automático", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ScanSchedule.OFF to "Desligado", ScanSchedule.DAILY to "Diário", ScanSchedule.WEEKLY to "Semanal").forEach { (v, label) ->
                            FilterChip(selected = s.schedule == v, onClick = { vm.setSchedule(v) }, label = { Text(label) })
                        }
                    }
                    Text("Roda carregando e com bateria ok. Itens sem alteração são pulados.", style = MaterialTheme.typography.bodySmall)
                }
            }

            item { HorizontalDivider() }
            item { Section("Privacidade") }
            item { ToggleRow("Borrar miniaturas", "Prévias aparecem borradas até você tocar", s.blurPreviews, vm::setBlur) }
            item {
                OutlinedButton(onClick = vm::resetCache, enabled = !scan.running) { Text("Limpar cache e resultados") }
            }

            item { HorizontalDivider() }
            item { Section("Conta Google (Drive / Fotos)") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "O Google só libera o login se existir um OAuth client Android no seu projeto do Google Cloud com exatamente estes dados:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CopyRow("Pacote", vm.packageName)
                    CopyRow("SHA-1", vm.signatureSha1)
                    Text(
                        "Google Cloud Console → APIs: ative Google Drive API e Google Photos Picker API → Tela de consentimento OAuth em modo Teste com seu e-mail como usuário de teste → Credenciais → Criar ID do cliente OAuth → Android.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { openUrl(ctx, "https://console.cloud.google.com/apis/credentials") }) {
                        Text("Abrir Google Cloud Console")
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
fun ThresholdSlider(value: Float, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("Limite: ${(local * 100).toInt()}% — menor = mais sensível (mais falsos positivos)", style = MaterialTheme.typography.bodySmall)
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onCommit(local) }, valueRange = 0.30f..0.98f)
    }
}

@Composable
private fun MinSizeSlider(valueKb: Int, onCommit: (Int) -> Unit) {
    var local by remember(valueKb) { mutableFloatStateOf(valueKb.toFloat()) }
    Column {
        Text("Ignorar arquivos menores que ${local.toInt()} KB (ícones, stickers)", style = MaterialTheme.typography.bodySmall)
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onCommit(local.toInt()) }, valueRange = 0f..200f)
    }
}

@Composable
private fun CopyRow(label: String, value: String) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(ctx, "$label copiado", Toast.LENGTH_SHORT).show()
        }) { Icon(Icons.Default.ContentCopy, "Copiar") }
    }
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(ctx: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(direct) }.onFailure {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { Toast.makeText(ctx, "Abra Configurações > Bateria > Dopa Cleaner > Sem restrições", Toast.LENGTH_LONG).show() }
    }
}

private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(ctx, "Nenhum navegador disponível", Toast.LENGTH_LONG).show() }
}
