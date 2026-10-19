package dev.diogo.conversorlocal

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diogo.conversorlocal.conversion.Conversion

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!model.initialIntentHandled) { model.initialIntentHandled = true; receive(intent) }
        setContent { ConverterScreen(model) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        val all = uris.ifEmpty { intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty() }
        model.select(all, shared = true, flags = intent.flags)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ConverterScreen(model: MainViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var licenses by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { intent ->
            val uris = intent.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(intent.data)
            model.select(uris, shared = false, flags = intent.flags)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.folder(result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }, result.data?.flags ?: 0)
    }
    LaunchedEffect(state.needsFolder) {
        if (state.needsFolder) {
            model.folderRequestShown()
            folder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            })
        }
    }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8DD5CD)) else lightColorScheme(primary = Color(0xFF14665E))
    MaterialTheme(colorScheme = colors) {
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Conversor Local", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("Seus documentos, no seu aparelho.", style = MaterialTheme.typography.titleMedium)
                        Text("Offline · Sem conta · Originais preservados", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Section("1. Selecione os arquivos") {
                        Text("PDF, DOCX ou PPTX · até 20 arquivos, 100 MB por arquivo", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !state.busy, onClick = {
                            pick.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            })
                        }) { Text("Selecionar arquivos") }
                        if (state.inputs.isEmpty()) Text("Você também pode usar Compartilhar → Conversor Local em outro aplicativo.", style = MaterialTheme.typography.bodyMedium)
                        state.inputs.forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
                    }
                    Section("2. Escolha o formato") {
                        val targets = Conversion.targets(state.inputs.map { it.extension })
                        if (state.inputs.isEmpty()) Text("DOCX e PPTX → PDF\nPDF → DOCX ou PPTX", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            targets.forEach { target ->
                                Row(Modifier.selectable(selected = target == state.target, enabled = !state.busy, role = Role.RadioButton, onClick = { model.target(target) }), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = target == state.target, onClick = null, enabled = !state.busy)
                                    Text(target.uppercase())
                                }
                            }
                        }
                        if (state.target == "docx") Text("O PDF precisa ter texto selecionável. O texto do DOCX será editável; layout, tabelas e imagens podem variar. OCR não está incluído nesta versão.", style = MaterialTheme.typography.bodySmall)
                        if (state.target == "pptx") Text("Cada página vira um slide com imagem de alta qualidade. Páginas com proporções diferentes recebem margens, sem cortes ou deformações.", style = MaterialTheme.typography.bodySmall)
                    }
                    Section("3. Destino") {
                        Text(state.folderName?.let { "Pasta escolhida: $it" } ?: "Mesma pasta do original, quando já autorizada. Caso contrário, o Android pedirá uma pasta.")
                        Text("Resultados existentes recebem (1), (2)… Nenhum original será substituído.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !state.busy, onClick = { model.requestFolder() }) { Text("Escolher pasta") }
                        if (state.folder != null) TextButton(enabled = !state.busy, onClick = { model.originalFolder() }) { Text("Usar pasta original autorizada") }
                    }
                    if (state.message != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(state.message!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.status)
                        OutlinedButton(onClick = model::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
                    } else {
                        Button(onClick = model::convert, enabled = state.inputs.isNotEmpty() && state.target.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Converter") }
                        if (state.status.isNotEmpty()) Text(state.status, style = MaterialTheme.typography.titleMedium)
                    }
                    for (result in state.results) OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(result.source, fontWeight = FontWeight.SemiBold)
                            val saved = result.saved
                            if (saved != null) {
                                Text("Salvo: ${saved.name}", color = MaterialTheme.colorScheme.primary)
                                Text(saved.destination, style = MaterialTheme.typography.bodySmall)
                                Text(saved.uri.toString(), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                    catch (_: ActivityNotFoundException) { Toast.makeText(context, "Nenhum aplicativo disponível para abrir esse formato.", Toast.LENGTH_LONG).show() }
                                    catch (_: SecurityException) { Toast.makeText(context, "O Android não concedeu acesso ao resultado.", Toast.LENGTH_LONG).show() }
                                }) { Text("Abrir resultado") }
                            } else Text(result.error ?: "Falha na conversão.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text("Mantenha o aplicativo aberto durante a conversão. Nenhum arquivo é enviado pela internet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { licenses = true }) { Text("Licenças e fontes") }
                }
            }
        }
        if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Licenças e fontes") },
            text = { Text(remember { context.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("Fechar") } })
    }
}
