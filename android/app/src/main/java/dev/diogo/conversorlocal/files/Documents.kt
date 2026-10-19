package dev.diogo.conversorlocal.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as DC
import android.provider.OpenableColumns
import dev.diogo.conversorlocal.conversion.Conversion
import dev.diogo.conversorlocal.conversion.Validation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

data class InputDocument(val uri: Uri, val name: String, val extension: String, val size: Long?, val shared: Boolean)
data class SavedDocument(val uri: Uri, val name: String, val destination: String, val mime: String)
class CleanupFailure(message: String, val partial: Uri, cause: Throwable) : Exception(message, cause)

class Documents(private val context: Context) {
    private val resolver = context.contentResolver
    fun persist(uri: Uri, flags: Int) {
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) runCatching {
            resolver.takePersistableUriPermission(uri, flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
    }

    fun describe(uri: Uri, shared: Boolean): InputDocument {
        require(uri.scheme == "content") { "Selecione documentos pelo seletor do Android ou por Compartilhar." }
        var name = "Documento"; var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
            if (row.moveToFirst()) {
                name = row.getString(0) ?: name
                if (!row.isNull(1)) size = row.getLong(1)
            }
        }
        val extension = name.substringAfterLast('.', "").lowercase().takeIf { it in listOf("pdf", "docx", "pptx") }
            ?: when (resolver.getType(uri)) {
                "application/pdf" -> "pdf"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                else -> error("$name: esta versão recebe somente PDF, DOCX e PPTX.")
            }
        require(size == null || size!! <= Validation.MAX_INPUT) { "$name: o limite é de 100 MB." }
        return InputDocument(uri, name, extension, size, shared)
    }

    /** Opaque document IDs from the provider, never content:// path manipulation. */
    fun originalParent(input: InputDocument): Uri? {
        if (input.shared || !DC.isDocumentUri(context, input.uri)) return null
        val id = runCatching { DC.getDocumentId(input.uri) }.getOrNull() ?: return null
        for (grant in resolver.persistedUriPermissions) {
            val tree = grant.uri
            if (!grant.isWritePermission || !grant.isReadPermission || !DC.isTreeUri(tree) || tree.authority != input.uri.authority) continue
            val parent = runCatching {
                val root = DC.getTreeDocumentId(tree)
                val scoped = DC.buildDocumentUriUsingTree(tree, id)
                val path = DC.findDocumentPath(resolver, scoped)?.path ?: return@runCatching null
                val rootIndex = path.indexOf(root)
                if (rootIndex < 0 || path.lastOrNull() != id || path.lastIndex <= rootIndex) return@runCatching null
                DC.buildDocumentUriUsingTree(tree, path[path.lastIndex - 1]).takeIf(::canCreate)
            }.getOrNull()
            if (parent != null) return parent
            // Providers without findDocumentPath: only a directly enumerated child is proven.
            val rootUri = DC.buildDocumentUriUsingTree(tree, DC.getTreeDocumentId(tree))
            if (runCatching { children(rootUri).any { it.first == id } && canCreate(rootUri) }.getOrDefault(false)) return rootUri
        }
        return null
    }

    fun folder(tree: Uri): Uri {
        require(DC.isTreeUri(tree)) { "A seleção não concedeu acesso a uma pasta." }
        val folder = DC.buildDocumentUriUsingTree(tree, DC.getTreeDocumentId(tree))
        require(canCreate(folder)) { "A pasta não permite criar arquivos. Escolha outra pasta ou conceda acesso de escrita." }
        return folder
    }

    fun name(uri: Uri): String = resolver.query(uri, arrayOf(DC.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.toString()

    private fun canCreate(uri: Uri): Boolean = resolver.query(uri, arrayOf(DC.Document.COLUMN_MIME_TYPE, DC.Document.COLUMN_FLAGS), null, null, null)?.use {
        it.moveToFirst() && it.getString(0) == DC.Document.MIME_TYPE_DIR && (it.getInt(1) and DC.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
    } == true

    private fun children(folder: Uri): List<Pair<String, String>> {
        val children = DC.buildChildDocumentsUriUsingTree(folder, DC.getDocumentId(folder))
        val entries = mutableListOf<Pair<String, String>>()
        resolver.query(children, arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) {
                require(entries.size < 10000) { "A pasta tem arquivos demais. Escolha uma subpasta." }
                entries += it.getString(0) to it.getString(1)
            }
        } ?: error("Não foi possível listar os arquivos da pasta.")
        return entries
    }

    suspend fun snapshot(input: InputDocument, destination: File) {
        resolver.openInputStream(input.uri)?.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= Validation.MAX_INPUT) { "O limite é de 100 MB por arquivo." }
                    output.write(buffer, 0, count)
                }
                require(size > 0) { "O documento está vazio." }
            }
        } ?: error("Não foi possível ler o arquivo. Verifique a permissão e se ele está disponível offline.")
    }

    suspend fun publish(output: File, input: InputDocument, conversion: Conversion, folder: Uri): SavedDocument {
        require(canCreate(folder)) { "A pasta perdeu a permissão de escrita. Escolha o destino novamente." }
        val before = children(folder)
        val desired = availableName(input.name, conversion.target, before.map { it.second }.toSet())
        val created = DC.createDocument(resolver, folder, conversion.mime, desired) ?: error("O Android não conseguiu criar o arquivo no destino.")
        // A non-conforming provider must never trick us into opening an existing file for writing.
        require(created != input.uri && DC.getDocumentId(created) !in before.map { it.first }) { "O provedor não criou um arquivo novo. Nada foi sobrescrito." }
        try {
            val expected = MessageDigest.getInstance("SHA-256")
            resolver.openOutputStream(created, "w")?.use { target ->
                output.inputStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        expected.update(buffer, 0, count); target.write(buffer, 0, count)
                    }
                }
                target.flush()
            } ?: error("Não foi possível gravar na pasta escolhida.")
            val actual = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(created)?.use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    actual.update(buffer, 0, count)
                }
            } ?: error("Não foi possível conferir o arquivo gravado.")
            check(MessageDigest.isEqual(expected.digest(), actual.digest())) { "A gravação ficou incompleta. O resultado não foi confirmado." }
            currentCoroutineContext().ensureActive()
            return SavedDocument(created, name(created), "${name(folder)} · $folder", conversion.mime)
        } catch (error: Throwable) {
            if (!runCatching { DC.deleteDocument(resolver, created) }.getOrDefault(false)) {
                throw CleanupFailure("Falha na conversão/gravação e o Android não removeu a saída parcial. Remova manualmente: $created", created, error)
            }
            throw error
        }
    }

    companion object {
        fun availableName(original: String, target: String, existing: Set<String>): String {
            val base = original.substringBeforeLast('.', original).replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(160).ifBlank { "Documento" }
            for (i in 0..10000) {
                val candidate = base + (if (i == 0) "" else " ($i)") + ".$target"
                if (existing.none { it.equals(candidate, ignoreCase = true) }) return candidate
            }
            error("Há resultados demais com esse nome na pasta.")
        }
    }
}
