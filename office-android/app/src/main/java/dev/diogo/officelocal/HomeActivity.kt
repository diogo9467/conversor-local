// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.officelocal

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class HomeActivity : ComponentActivity() {
    private var recent by mutableStateOf<List<EditingSession>>(emptyList())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) it.data?.data?.let { uri -> open(uri, it.data!!.flags, false) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { Home() }
        if (state == null) receive(intent)
    }
    override fun onResume() { super.onResume(); recent = Sessions(this).recent() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent) {
        val next = intent.getStringExtra("afterClose")
        if (next != null) {
            val closingPid = intent.getIntExtra("closingPid", -1)
            intent.removeExtra("afterClose")
            busy = true; message = null
            lifecycleScope.launch {
                try {
                    // Collabora exits its process after BYE. A fixed delay races
                    // that shutdown and can deliver the next file to the dying editor.
                    withTimeout(60000) {
                        val manager = getSystemService(ActivityManager::class.java)
                        while (true) {
                            val processes = manager.runningAppProcesses
                            if (processes != null && processes.none { it.pid == closingPid && it.processName == "$packageName:editor" }) break
                            delay(200)
                        }
                    }
                    Sessions(this@HomeActivity).open(Sessions(this@HomeActivity).read(next))
                } catch (error: Exception) {
                    message = "O documento anterior ainda está fechando. Sua cópia está em Recentes; tente abri-la novamente."
                } finally { busy = false; recent = Sessions(this@HomeActivity).recent() }
            }
            return
        }
        val uri = if (intent.action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: intent.clipData?.getItemAt(0)?.uri else intent.data
        if (uri != null) open(uri, intent.flags, true)
    }
    private fun open(uri: Uri, flags: Int, shared: Boolean) = launchWork { Sessions(this).import(uri, flags, shared) }
    private fun launchWork(work: () -> EditingSession) {
        if (busy) return
        busy = true; message = null
        lifecycleScope.launch {
            try { val session = withContext(Dispatchers.IO) { work() }; Sessions(this@HomeActivity).open(session) }
            catch (error: Exception) { message = error.message ?: "Não foi possível abrir o documento." }
            finally { busy = false; recent = Sessions(this@HomeActivity).recent() }
        }
    }
    private fun select() {
        pick.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_MIME_TYPES, Sessions.MIME.values.toTypedArray()).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }
    @Composable private fun Home() {
        val dark = isSystemInDarkTheme()
        SideEffect { androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark }
        val colors = if (dark) darkColorScheme(primary = Color(0xFF9DDBCA), surface = Color(0xFF142522), background = Color(0xFF10201D), secondaryContainer = Color(0xFF23453D))
            else lightColorScheme(primary = Color(0xFF216A5F), surface = Color(0xFFFDFBF7), background = Color(0xFFF4F1E9), secondaryContainer = Color(0xFFE3EBE2))
        var licenses by remember { mutableStateOf(false) }
        MaterialTheme(colorScheme = colors) {
            Scaffold(containerColor = colors.background) { insets ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(insets).padding(horizontal = 28.dp)) {
                    val wide = maxWidth >= 800.dp
                    Column(Modifier.widthIn(max = 1240.dp).fillMaxSize().align(Alignment.TopCenter).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("OFFICE LOCAL", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.primary)
                            Text("NO SEU APARELHO", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Espaço para suas ideias.", style = if (wide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                            Text("Escreva, organize e apresente. Seus arquivos continuam com você.", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = !busy, onClick = ::select, modifier = Modifier.heightIn(min = 52.dp)) { Text("Abrir arquivo") }
                            OutlinedButton(onClick = { startActivity(Intent(this@HomeActivity, dev.diogo.conversorlocal.MainActivity::class.java)) }, modifier = Modifier.heightIn(min = 52.dp)) { Text("Converter arquivos") }
                        }
                        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparando sua cópia de trabalho…") }
                        message?.let { Text(it, color = colors.error) }
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            NewCard("01", "Documento", "Textos, imagens e tabelas", "docx", Modifier.weight(1f))
                            NewCard("02", "Planilha", "Cálculos, fórmulas e abas", "xlsx", Modifier.weight(1f))
                            NewCard("03", "Apresentação", "Slides para suas ideias", "pptx", Modifier.weight(1f))
                        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            NewCard("01", "Documento", "Textos, imagens e tabelas", "docx", Modifier.fillMaxWidth())
                            NewCard("02", "Planilha", "Cálculos, fórmulas e abas", "xlsx", Modifier.fillMaxWidth())
                            NewCard("03", "Apresentação", "Slides para suas ideias", "pptx", Modifier.fillMaxWidth())
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Continue de onde parou", style = MaterialTheme.typography.headlineSmall)
                            Text("Cópias de trabalho dos arquivos usados aqui. Salvar atualiza o original autorizado; Salvar como cria um novo arquivo.", color = colors.onSurfaceVariant)
                            if (recent.isEmpty()) OutlinedCard(Modifier.fillMaxWidth()) { Text("Seus documentos aparecerão aqui quando você abrir ou criar um arquivo.", Modifier.padding(24.dp)) }
                            recent.forEach { session ->
                                Card(onClick = { runCatching { Sessions(this@HomeActivity).open(session) }.onFailure { message = it.message } }, modifier = Modifier.fillMaxWidth(), enabled = !busy, colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
                                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        Text(session.extension.uppercase(), fontWeight = FontWeight.Bold, color = colors.primary, modifier = Modifier.width(55.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(session.name, style = MaterialTheme.typography.titleMedium)
                                            Text(if (session.recovery) "Cópia local para continuar" else "Original salvo · cópia local disponível", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        }
                                        Text("Abrir →", color = colors.primary)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Offline · Sem conta", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            TextButton(onClick = { licenses = true }) { Text("Sobre e licenças") }
                        }
                    }
                }
            }
            if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Office Local") }, text = {
                Text(remember { assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }, Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()))
            }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Fechar") } })
        }
    }
    @Composable private fun NewCard(number: String, title: String, subtitle: String, extension: String, modifier: Modifier) {
        Card(onClick = { launchWork { Sessions(this@HomeActivity).create(extension) } }, modifier = modifier, enabled = !busy, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(number + " / NOVO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Criar +", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
