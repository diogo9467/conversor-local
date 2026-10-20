// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.officelocal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import dev.diogo.conversorlocal.files.Documents
import dev.diogo.conversorlocal.files.InputDocument
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class EditingSession(val id: String, val name: String, val extension: String,
    val source: String?, val writable: Boolean, val shared: Boolean, var sourceHash: String,
    var updated: Long = System.currentTimeMillis(), var recovery: Boolean = true)

class Sessions(private val context: Context) {
    private val root = File(context.filesDir, "editor").apply { mkdirs() }
    fun directory(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Sessão inválida." }
        return File(root, id)
    }
    fun working(session: EditingSession) = File(directory(session.id), "documento.${session.extension}")
    fun save(session: EditingSession) {
        val json = JSONObject().put("id", session.id).put("name", session.name).put("extension", session.extension)
            .put("source", session.source ?: JSONObject.NULL).put("writable", session.writable)
            .put("shared", session.shared).put("sourceHash", session.sourceHash).put("updated", session.updated)
            .put("recovery", session.recovery)
        val atomic = AtomicFile(File(directory(session.id), "session.json"))
        val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) }
        catch (error: Throwable) { atomic.failWrite(out); throw error }
    }
    fun read(id: String): EditingSession {
        val json = JSONObject(AtomicFile(File(directory(id), "session.json")).openRead().bufferedReader().use { it.readText() })
        return EditingSession(id, json.getString("name"), json.getString("extension"),
            if (json.isNull("source")) null else json.getString("source"), json.getBoolean("writable"),
            json.getBoolean("shared"), json.getString("sourceHash"), json.getLong("updated"), json.optBoolean("recovery", true))
    }
    fun recent(): List<EditingSession> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull {
        runCatching { read(it.name) }.getOrNull()
    }.sortedByDescending { it.updated }.take(30)

    fun import(uri: Uri, flags: Int, shared: Boolean): EditingSession {
        require(uri.scheme == "content") { "Abra arquivos pelo seletor do Android." }
        Documents(context).persist(uri, flags)
        var name = "Documento"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val extension = name.substringAfterLast('.', "").lowercase().takeIf { it in MIME }
            ?: MIME.entries.firstOrNull { it.value == context.contentResolver.getType(uri) }?.key
            ?: error("Abra PDF, DOCX, ODT, XLSX, ODS, PPTX ou ODP.")
        if (!name.endsWith(".$extension", true)) name += ".$extension"
        val granted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        val canWrite = granted && (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 || context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission })
        val session = EditingSession(UUID.randomUUID().toString(), name, extension, uri.toString(), canWrite, shared, "")
        directory(session.id).mkdirs()
        val file = working(session)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(65536); var count = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    count += n; require(count <= 100L * 1024 * 1024) { "O limite é de 100 MB por documento." }
                    output.write(buffer, 0, n)
                }
            }
        } ?: error("O arquivo não está disponível. Confira o acesso pelo seletor do Android.")
        EditorValidation.check(file, extension)
        session.sourceHash = hash(file)
        file.copyTo(File(directory(session.id), "original.$extension"))
        save(session)
        return session
    }

    fun create(extension: String): EditingSession {
        require(extension in setOf("docx", "xlsx", "pptx"))
        val name = when (extension) { "docx" -> "Documento"; "xlsx" -> "Planilha"; else -> "Apresentação" }
        val session = EditingSession(UUID.randomUUID().toString(), "$name.$extension", extension, null, false, false, "")
        directory(session.id).mkdirs()
        context.assets.open("templates/untitled.$extension").use { input -> working(session).outputStream().use(input::copyTo) }
        save(session)
        return session
    }

    fun open(session: EditingSession) {
        session.updated = System.currentTimeMillis(); session.recovery = true; save(session)
        context.startActivity(Intent(context, EditorActivity::class.java).putExtra("session", session.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun workingUri(session: EditingSession): Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", working(session), session.name)
    fun input(session: EditingSession) = InputDocument(session.source?.let(Uri::parse) ?: workingUri(session), session.name, session.extension, working(session).length(), session.shared)

    /** Explicit Save only; verified readback, with a retained original and working copy. */
    fun publishOriginal(session: EditingSession) {
        require(session.writable && session.source != null && session.extension != "pdf") { "Somente leitura: use Salvar como para criar sua cópia." }
        val uri = Uri.parse(session.source)
        val current = context.contentResolver.openInputStream(uri)?.use { hash(it) } ?: error("Não foi possível conferir o original.")
        check(current == session.sourceHash) { "O original mudou desde a abertura. Use Salvar como para preservar as duas versões." }
        val expected = hash(working(session))
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                working(session).inputStream().use { it.copyTo(out) }; out.flush()
            } ?: error("Permissão de escrita indisponível.")
            val actual = context.contentResolver.openInputStream(uri)?.use { hash(it) }
            check(actual == expected) { "A conferência da gravação falhou." }
        } catch (error: Exception) {
            throw IllegalStateException("Não foi possível confirmar o original. Sua cópia de trabalho e a versão anterior estão preservadas no aplicativo. Use Salvar como. ${error.message}", error)
        }
        session.sourceHash = expected; session.recovery = false; session.updated = System.currentTimeMillis(); save(session)
    }
    companion object {
        val MIME = linkedMapOf("docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "odt" to "application/vnd.oasis.opendocument.text", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ods" to "application/vnd.oasis.opendocument.spreadsheet", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "odp" to "application/vnd.oasis.opendocument.presentation", "pdf" to "application/pdf")
        fun hash(file: File): String = file.inputStream().use { hash(it) }
        fun hash(input: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
