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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import java.text.DateFormat
import java.util.Date
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
    private fun converter() { startActivity(Intent(this, dev.diogo.conversorlocal.MainActivity::class.java)) }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Home() {
        var licenses by remember { mutableStateOf(false) }
        OfficeTheme {
            val colors = MaterialTheme.colorScheme
            Scaffold(topBar = { OfficeAppBar() }, containerColor = colors.background) { insets ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
                    val wide = maxWidth >= 900.dp
                    Row(Modifier.fillMaxSize()) {
                        if (wide) Surface(color = colors.surface, modifier = Modifier.width(196.dp).fillMaxHeight()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Spacer(Modifier.height(16.dp))
                                NavigationItem("home", "Início", true) { }
                                NavigationItem("folder", "Abrir arquivo", enabled = !busy, action = ::select)
                                NavigationItem("convert", "Converter arquivos", action = ::converter)
                                Spacer(Modifier.weight(1f))
                                HorizontalDivider(color = colors.outlineVariant)
                                NavigationItem("info", "Sobre e licenças") { licenses = true }
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(horizontal = if (wide) 32.dp else 20.dp).padding(top = 26.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Início", style = MaterialTheme.typography.headlineLarge)
                                    Text("Tudo pronto para o próximo arquivo.", color = colors.onSurfaceVariant)
                                }
                                if (wide) OpenButton()
                            }
                            if (!wide) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OpenButton()
                                OutlinedButton(onClick = ::converter, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 46.dp)) {
                                    OfficeGlyph("convert"); Spacer(Modifier.width(8.dp)); Text("Converter arquivos")
                                }
                            }
                            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparando seu documento…", style = MaterialTheme.typography.bodySmall) }
                            message?.let { Surface(color = colors.errorContainer, shape = MaterialTheme.shapes.medium) {
                                Text(it, Modifier.padding(16.dp), color = colors.onErrorContainer)
                            } }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Criar novo", style = MaterialTheme.typography.titleLarge)
                                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    NewCard("Documento", "Textos, imagens e tabelas", "docx", true, Modifier.weight(1f))
                                    NewCard("Planilha", "Cálculos, fórmulas e abas", "xlsx", true, Modifier.weight(1f))
                                    NewCard("Apresentação", "Slides, imagens e ideias", "pptx", true, Modifier.weight(1f))
                                } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    NewCard("Documento", "Textos, imagens e tabelas", "docx", false, Modifier.fillMaxWidth())
                                    NewCard("Planilha", "Cálculos, fórmulas e abas", "xlsx", false, Modifier.fillMaxWidth())
                                    NewCard("Apresentação", "Slides, imagens e ideias", "pptx", false, Modifier.fillMaxWidth())
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OfficeGlyph("recent", Modifier.size(20.dp), colors.onSurfaceVariant)
                                    Text("Recentes", style = MaterialTheme.typography.titleLarge)
                                }
                                Text("Continue seus arquivos salvos neste aparelho.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                Surface(shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
                                    Column {
                                        if (recent.isEmpty()) Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                            OfficeGlyph("folder", Modifier.size(38.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text("Seu próximo projeto começa aqui", style = MaterialTheme.typography.titleMedium)
                                                Text("Abra um arquivo ou crie um novo. Ele aparecerá nesta lista.", color = colors.onSurfaceVariant)
                                            }
                                        } else {
                                            if (wide) Row(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh).padding(horizontal = 20.dp, vertical = 12.dp)) {
                                                Text("Nome", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                                                Text("Último acesso", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(145.dp))
                                            }
                                            recent.forEachIndexed { index, session ->
                                                if (index > 0) HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(horizontal = 18.dp))
                                                RecentRow(session, wide)
                                            }
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Seus arquivos ficam com você.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                if (!wide) TextButton(onClick = { licenses = true }) { Text("Sobre e licenças") }
                            }
                        }
                    }
                }
            }
            if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Office Local") }, text = {
                Text(remember { assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }, Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()))
            }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Fechar") } })
        }
    }

    @Composable private fun OpenButton() {
        Button(onClick = ::select, enabled = !busy, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 46.dp)) {
            OfficeGlyph("folder", color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp)); Text("Abrir arquivo")
        }
    }
    @Composable private fun NavigationItem(icon: String, title: String, selected: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Surface(onClick = action, enabled = enabled, shape = MaterialTheme.shapes.small,
            color = if (selected) colors.primaryContainer else colors.surface,
            contentColor = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OfficeGlyph(icon, Modifier.size(20.dp), LocalContentColor.current)
                Text(title, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    @Composable private fun RecentRow(session: EditingSession, wide: Boolean) {
        val colors = MaterialTheme.colorScheme
        val glyph = when (session.extension) { "xlsx", "ods" -> "sheet"; "pptx", "odp" -> "slides"; else -> "document" }
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button, onClickLabel = "Abrir ${session.name}") {
            runCatching { Sessions(this@HomeActivity).open(session) }.onFailure { message = it.message }
        }.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = colors.primaryContainer, shape = MaterialTheme.shapes.small) { OfficeGlyph(glyph, Modifier.padding(10.dp).size(24.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(session.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(session.extension.uppercase() + " · " + if (session.recovery) "Cópia local para continuar" else "Original salvo",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (wide) Text(remember(session.updated) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.updated)) },
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.width(115.dp))
            OfficeGlyph("arrow", Modifier.size(18.dp), colors.onSurfaceVariant)
        }
    }
    @Composable private fun NewCard(title: String, subtitle: String, extension: String, wide: Boolean, modifier: Modifier) {
        val colors = MaterialTheme.colorScheme
        OutlinedCard(onClick = { launchWork { Sessions(this@HomeActivity).create(extension) } }, modifier = modifier, enabled = !busy,
            colors = CardDefaults.outlinedCardColors(containerColor = colors.surface), border = BorderStroke(1.dp, colors.outlineVariant)) {
            if (wide) Column {
                FilePreview(extension, Modifier.fillMaxWidth().height(110.dp))
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("Criar novo  +", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            } else Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilePreview(extension, Modifier.size(94.dp, 80.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                OfficeGlyph("arrow", Modifier.size(20.dp))
            }
        }
    }
    @Composable private fun FilePreview(extension: String, modifier: Modifier) {
        val dark = isSystemInDarkTheme()
        val background = if (dark) Color(0xFF25364F) else Color(0xFFEBF1FB)
        Canvas(modifier.background(background, RoundedCornerShape(6.dp))) {
            val pageWidth = if (extension == "docx") 64.dp.toPx() else 84.dp.toPx()
            val pageHeight = 66.dp.toPx()
            val x = (size.width - pageWidth) / 2; val y = (size.height - pageHeight) / 2
            val unit = pageWidth / 100
            drawRoundRect(Color(0xFFCBD8ED), Offset(x + 3.dp.toPx(), y + 3.dp.toPx()), Size(pageWidth, pageHeight), CornerRadius(3.dp.toPx()))
            drawRoundRect(Color.White, Offset(x, y), Size(pageWidth, pageHeight), CornerRadius(3.dp.toPx()))
            if (extension == "docx") {
                drawRect(OfficeBlue, Offset(x + 14 * unit, y + 13 * unit), Size(50 * unit, 6 * unit))
                for (i in 0..4) drawRect(Color(0xFFB7C6DA), Offset(x + 14 * unit, y + (29 + 12 * i) * unit), Size((if (i == 4) 45 else 72) * unit, 3 * unit))
            } else if (extension == "xlsx") {
                drawRect(OfficeBlue, Offset(x + 10 * unit, y + 10 * unit), Size(80 * unit, 11 * unit))
                for (i in 0..4) drawLine(Color(0xFFCBD8E9), Offset(x + 10 * unit, y + (21 + 10 * i) * unit), Offset(x + 90 * unit, y + (21 + 10 * i) * unit), 1.dp.toPx())
                for (i in 0..4) drawLine(Color(0xFFCBD8E9), Offset(x + (10 + 20 * i) * unit, y + 21 * unit), Offset(x + (10 + 20 * i) * unit, y + 61 * unit), 1.dp.toPx())
                drawRect(Color(0xFFDCE9FF), Offset(x + 31 * unit, y + 32 * unit), Size(18 * unit, 8 * unit))
            } else {
                drawRect(OfficeBlue, Offset(x + 10 * unit, y + 10 * unit), Size(80 * unit, 17 * unit))
                drawRect(Color(0xFFB7C6DA), Offset(x + 10 * unit, y + 35 * unit), Size(36 * unit, 3 * unit))
                drawRect(Color(0xFFB7C6DA), Offset(x + 10 * unit, y + 44 * unit), Size(30 * unit, 3 * unit))
                drawRect(Color(0xFFB7C6DA), Offset(x + 10 * unit, y + 53 * unit), Size(34 * unit, 3 * unit))
                drawRect(Color(0xFFDCE9FF), Offset(x + 57 * unit, y + 35 * unit), Size(33 * unit, 24 * unit))
            }
        }
    }
}
