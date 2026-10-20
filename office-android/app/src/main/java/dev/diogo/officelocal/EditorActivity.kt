// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.officelocal

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.view.KeyEvent
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.*
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.diogo.conversorlocal.files.Documents
import kotlinx.coroutines.*
import org.libreoffice.androidlib.LOActivity
import java.io.File
import java.util.UUID

/** Thin SAF and recovery boundary around the unchanged Collabora document UI. */
class EditorActivity : LOActivity() {
    private lateinit var store: Sessions
    private lateinit var session: EditingSession
    private val main = Handler(Looper.getMainLooper())
    private var afterSave: (() -> Unit)? = null
    private var recoverySave = false
    private var queuedFlush: (() -> Unit)? = null
    private var operation = false
    private var exportFile: File? = null
    private var exportFormat = ""
    private var label: TextView? = null
    private var fileBar: LinearLayout? = null
    private var fileButton: Button? = null
    private var closing = false
    private val timeout = Runnable {
        afterSave = null; queuedFlush = null; recoverySave = false; operation = false
        problem("O editor não confirmou o salvamento. O original não foi atualizado. Aguarde o carregamento e tente novamente.")
    }
    private val autosave = object : Runnable {
        override fun run() {
            saveWorkingCopy()
            main.postDelayed(this, 20000)
        }
    }

    override fun onCreate(state: Bundle?) {
        store = Sessions(this)
        session = store.read(intent.getStringExtra("session") ?: error("Sessão ausente."))
        intent.setDataAndType(store.workingUri(session), Sessions.MIME[session.extension])
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        super.onCreate(state)
        addFileMenu()
        main.postDelayed(autosave, 20000)
    }

    override fun copyFileToTemp(): Boolean {
        mTempFile = store.working(session)
        if (session.extension == "pdf") isDocEditable = false
        return mTempFile.isFile
    }
    override fun copyTempBackToIntent() { /* Native autosave writes only the private working file. */ }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (documentLoaded && event.isCtrlPressed && !event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_S) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.isShiftPressed) chooseFormat() else saveOriginal()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun saveWorkingCopy() {
        if (closing || !documentLoaded || operation || recoverySave || session.extension == "pdf") return
        recoverySave = true
        main.postDelayed(timeout, 45000)
        postMobileMessageNative("save dontTerminateEdit=1 dontSaveIfUnmodified=0")
    }

    override fun beforeMessageFromWebView(parts: Array<String>): Boolean {
        when (parts[0]) {
            "load" -> {
                val parameters = parts.getOrElse(1) { "" }
                    .replace(Regex("(?<!\\S)darkTheme=\\S+"), "darkTheme=false")
                    .replace(Regex("(?<!\\S)darkBackground=\\S+"), "darkBackground=false")
                postMobileMessageNative("load $parameters")
                return false
            }
            "uno" -> if (parts.getOrElse(1) { "" }.substringBefore(' ') in setOf(".uno:ChangeTheme", ".uno:InvertBackground")) {
                // Dark chrome is CSS. A dark core theme changes automatic table
                // text colors in exported Impress PDFs; keep document colors stable.
                val command = parts[1].substringBefore(' ')
                postMobileMessageNative("uno $command {\"NewTheme\":{\"type\":\"string\",\"value\":\"Light\"}}")
                return false
            }
            "save" -> { main.post { saveOriginal() }; return false }
            "SAVE" -> {
                main.post {
                    session.updated = System.currentTimeMillis(); session.recovery = true; store.save(session)
                    if (recoverySave) {
                        recoverySave = false; main.removeCallbacks(timeout)
                        val queued = queuedFlush; queuedFlush = null
                        if (queued != null) { operation = false; flush(queued) }
                        return@post
                    }
                    val action = afterSave
                    afterSave = null; main.removeCallbacks(timeout)
                    action?.invoke()
                }
                return false
            }
            "downloadas" -> {
                val format = parts.getOrElse(1) { "" }.split(' ').firstOrNull { it.startsWith("format=") }?.substringAfter('=')
                main.post {
                    if (format != null && format in Sessions.MIME) export(format)
                    else toast("Escolha um dos formatos disponíveis em Arquivo → Salvar como.")
                }
                return false
            }
            "exportfile" -> { main.post { export("pdf") }; return false }
            "BYE" -> { main.post { finishWithProgress() }; return false }
        }
        return super.beforeMessageFromWebView(parts)
    }

    /** A save acknowledgement is the ordering barrier between pending edits and export. */
    private fun flush(action: () -> Unit) {
        if (operation) { toast("Aguarde a operação atual."); return }
        if (!documentLoaded) { toast("Aguarde o documento carregar."); return }
        if (recoverySave) { queuedFlush = action; operation = true; return }
        operation = true
        if (session.extension == "pdf") { action(); return }
        afterSave = action
        main.postDelayed(timeout, 45000)
        findViewById<WebView>(org.libreoffice.androidlib.R.id.browser).evaluateJavascript(
            "(function(){ if(app.map.acceptPendingCellEdit) app.map.acceptPendingCellEdit(); app.map.focus(); return true; })();") {
            // Bypass the user-command interceptor: sending 'save' through the
            // Android fake socket here would recursively invoke Save again.
            postMobileMessageNative("save dontTerminateEdit=0 dontSaveIfUnmodified=0")
        }
    }

    private fun saveOriginal(closeAfter: Boolean = false) {
        if (!session.writable || session.source == null || session.extension == "pdf") {
            toast("O original é somente leitura. Escolha uma pasta para salvar sua cópia.")
            export(session.extension, chooseDestination = true); return
        }
        flush {
            work({ store.publishOriginal(session) }) {
                operation = false; label?.text = session.name
                toast("Salvo no original e conferido.")
                if (closeAfter) closeEditor()
            }
        }
    }

    private fun export(format: String, share: Boolean = false, chooseDestination: Boolean = false) {
        require(format in Sessions.MIME)
        flush {
            work({
                val folder = File(cacheDir, "shared/${UUID.randomUUID()}").apply { mkdirs() }
                val file = File(folder, Documents.availableName(session.name, format, emptySet()))
                if (session.extension == "pdf" && format == "pdf") store.working(session).copyTo(file)
                else OfficeBridge.exportCurrent(file.toURI().toString(), format)
                check(file.length() > 0) { "O editor não gerou a saída." }
                EditorValidation.check(file, format)
                file
            }) { file ->
                operation = false
                if (share) share(file, format)
                else {
                    exportFile = file; exportFormat = format
                    val parent = if (chooseDestination) null else Documents(this).originalParent(store.input(session))
                    if (parent != null) publish(parent)
                    else chooseFolder()
                }
            }
        }
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), 9001)
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (request != 9001) { super.onActivityResult(request, result, data); return }
        if (result != Activity.RESULT_OK || data?.data == null) { toast("Destino não escolhido. A edição continua preservada."); return }
        try {
            Documents(this).persist(data.data!!, data.flags)
            publish(Documents(this).folder(data.data!!))
        } catch (error: Exception) { problem(error.message ?: "Pasta indisponível.") }
    }
    private fun publish(folder: Uri) {
        val file = exportFile ?: return
        operation = true
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    Documents(this@EditorActivity).publishFile(file, store.input(session), exportFormat, Sessions.MIME.getValue(exportFormat), folder)
                }
                exportFile = null
                label?.text = session.name
                AlertDialog.Builder(this@EditorActivity).setTitle("Arquivo criado")
                    .setMessage("${saved.name}\n${saved.destination}")
                    .setPositiveButton("Compartilhar") { _, _ -> share(file, exportFormat) }
                    .setNegativeButton("Continuar editando", null).show()
            } catch (error: Exception) { problem(error.message ?: "A saída não foi confirmada.") }
            finally { operation = false }
        }
    }

    private fun share(file: File, format: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(Sessions.MIME.getValue(format))
            .putExtra(Intent.EXTRA_STREAM, uri).apply { clipData = ClipData.newUri(contentResolver, file.name, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar cópia atual"))
    }

    private fun convertPdf() {
        if (session.extension != "pdf") { export("pdf"); return }
        val uri = store.workingUri(session)
        startActivity(Intent(this, dev.diogo.conversorlocal.MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    override fun finishWithProgress() {
        if (closing || !documentLoaded) { super.finishWithProgress(); return }
        if (operation) { toast("Aguarde a operação atual antes de sair."); return }
        AlertDialog.Builder(this).setTitle("Sair do documento?")
            .setMessage("Você pode salvar o original ou manter a edição no aparelho para continuar em Recentes.")
            .setPositiveButton(if (session.writable && session.extension != "pdf") "Salvar e sair" else "Salvar como") { _, _ -> saveOriginal(true) }
            .setNeutralButton("Manter para continuar") { _, _ -> flush { operation = false; closeEditor() } }
            .setNegativeButton("Continuar editando", null).show()
    }
    private fun closeEditor() { closing = true; super.finishWithProgress() }

    override fun handleOfficeIntent(next: Intent): Boolean {
        val id = next.getStringExtra("session")
        if (id == null || id == session.id) return true
        AlertDialog.Builder(this).setTitle("Abrir outro documento?")
            .setMessage("A edição atual ficará preservada em Recentes.")
            .setPositiveButton("Continuar") { _, _ -> flush {
                operation = false
                closing = true
                startActivity(Intent(this, HomeActivity::class.java).putExtra("afterClose", id)
                    .putExtra("closingPid", android.os.Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                closeEditor()
            } }.setNegativeButton("Cancelar", null).show()
        return true
    }

    private fun addFileMenu() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val original = content.getChildAt(0); content.removeView(original)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 2, 16, 2); gravity = android.view.Gravity.CENTER_VERTICAL }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            view.setPadding(16, insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top, 16, 2)
            insets
        }
        label = TextView(this).apply { text = session.name; maxLines = 1; textSize = 13f }
        val menu = Button(this).apply { text = "Arquivo"; contentDescription = "Ações do Office Local"; isAllCaps = false }
        fileBar = bar; fileButton = menu; updateFileBarTheme()
        bar.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(menu)
        menu.setOnClickListener {
            val popup = PopupMenu(this, menu)
            val actions = linkedMapOf<String, () -> Unit>(
                "Salvar" to { saveOriginal() }, "Salvar como…" to { chooseFormat() },
                "Exportar PDF" to { export("pdf") }, "Compartilhar cópia atual" to { export(session.extension, true) },
                "Converter" to { convertPdf() }, "Página inicial" to { startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) },
                "Fechar documento" to { finishWithProgress() })
            actions.keys.forEach { popup.menu.add(it) }
            popup.setOnMenuItemClickListener { actions[it.title.toString()]?.invoke(); true }; popup.show()
        }
        container.addView(bar); container.addView(original, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(container)
    }
    private fun chooseFormat() {
        val formats = when (session.extension) { "docx", "odt" -> arrayOf("docx", "odt", "pdf"); "xlsx", "ods" -> arrayOf("xlsx", "ods", "pdf"); "pptx", "odp" -> arrayOf("pptx", "odp", "pdf"); else -> arrayOf("pdf") }
        AlertDialog.Builder(this).setTitle("Salvar uma nova cópia").setItems(formats.map { it.uppercase() }.toTypedArray()) { _, n -> export(formats[n], chooseDestination = true) }.show()
    }
    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        updateFileBarTheme()
    }
    private fun updateFileBarTheme() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        fileBar?.setBackgroundColor(Color.parseColor(if (dark) "#142522" else "#FDFBF7"))
        label?.setTextColor(Color.parseColor(if (dark) "#E3EBE2" else "#213A34"))
        fileButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (dark) "#23453D" else "#E3EBE2"))
        fileButton?.setTextColor(Color.parseColor(if (dark) "#9DDBCA" else "#216A5F"))
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
    }
    private fun <T> work(task: () -> T, done: (T) -> Unit) {
        lifecycleScope.launch {
            try { done(withContext(Dispatchers.IO) { task() }) }
            catch (error: Exception) { operation = false; problem(error.message ?: "Não foi possível concluir a operação.") }
        }
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun problem(text: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle("Operação não concluída").setMessage(text).setPositiveButton("Entendi", null).show() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
}
