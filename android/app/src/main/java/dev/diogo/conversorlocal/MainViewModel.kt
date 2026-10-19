package dev.diogo.conversorlocal

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.diogo.conversorlocal.conversion.Conversion
import dev.diogo.conversorlocal.conversion.WorkerClient
import dev.diogo.conversorlocal.files.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

data class FileResult(val source: String, val saved: SavedDocument? = null, val error: String? = null)
data class ScreenState(
    val inputs: List<InputDocument> = emptyList(), val target: String = "", val folder: Uri? = null,
    val folderName: String? = null, val busy: Boolean = false, val status: String = "",
    val message: String? = null, val results: List<FileResult> = emptyList(), val needsFolder: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val documents = Documents(application)
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    var initialIntentHandled = false
    private val jobs = File(application.cacheDir, "jobs")

    private val initialCleanup = viewModelScope.async(Dispatchers.IO) {
        if (jobs.listFiles().orEmpty().map { old -> old.deleteRecursively() }.any { !it }) notice("Não foi possível remover um temporário antigo. Limpe o cache do aplicativo nas configurações do Android.")
    }

    fun select(uris: List<Uri>, shared: Boolean, flags: Int) {
        if (mutable.value.busy) { notice("Aguarde ou cancele o lote atual antes de selecionar outros arquivos."); return }
        task = viewModelScope.launch {
            mutable.update { it.copy(busy = true, status = "Lendo seleção…", results = emptyList(), message = null) }
            try {
                require(uris.size in 1..20) { "Selecione de 1 a 20 arquivos por lote." }
                val inputs = withContext(Dispatchers.IO) { uris.distinct().map { documents.persist(it, flags); documents.describe(it, shared) } }
                val targets = Conversion.targets(inputs.map { it.extension })
                mutable.update { it.copy(inputs = inputs, target = targets.firstOrNull().orEmpty(), status = "", message = if (targets.isEmpty()) "Esses arquivos não têm um destino comum. Separe PDFs dos documentos Office." else null) }
            } catch (error: Exception) { notice(error.message ?: "Não foi possível ler a seleção.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun target(value: String) { if (!mutable.value.busy) mutable.update { it.copy(target = value) } }
    fun notice(value: String?) { mutable.update { it.copy(message = value) } }
    fun requestFolder() { if (!mutable.value.busy) mutable.update { it.copy(needsFolder = true) } }
    fun folderRequestShown() { mutable.update { it.copy(needsFolder = false) } }
    fun originalFolder() { if (!mutable.value.busy) mutable.update { it.copy(folder = null, folderName = null) } }

    fun folder(uri: Uri?, flags: Int) {
        if (uri == null) { notice("Nenhuma pasta foi autorizada. A conversão não foi iniciada."); return }
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, folder = null, folderName = null, status = "Verificando pasta…") }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { documents.persist(uri, flags); documents.folder(uri).let { it to documents.name(it) } }
                mutable.update { it.copy(folder = result.first, folderName = result.second, message = null) }
            } catch (error: Exception) { notice(error.message ?: "Não foi possível autorizar a pasta.") }
            finally { mutable.update { it.copy(busy = false, status = "") } }
        }
    }

    fun convert() {
        val selection = mutable.value
        if (selection.busy || selection.inputs.isEmpty() || selection.target.isEmpty()) return
        task = viewModelScope.launch {
            mutable.update { it.copy(busy = true, results = emptyList(), message = null, status = "Verificando o destino…") }
            try {
                initialCleanup.await()
                val destinations = withContext(Dispatchers.IO) { selection.inputs.map { selection.folder ?: documents.originalParent(it) } }
                if (destinations.any { it == null }) {
                    mutable.update { it.copy(needsFolder = true, message = "O acesso ao arquivo não autoriza sua pasta. Autorize a pasta original ou escolha uma pasta de saída; depois toque em Converter.") }
                    return@launch
                }
                for ((index, input) in selection.inputs.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val conversion = Conversion.forPair(input.extension, selection.target) ?: error("Conversão incompatível.")
                    val jobDir = File(jobs, UUID.randomUUID().toString())
                    check(jobDir.mkdirs()) { "Não foi possível preparar a cópia privada." }
                    try {
                        val snapshot = File(jobDir, "input.${input.extension}"); val output = File(jobDir, "output.${conversion.target}")
                        mutable.update { it.copy(status = "Arquivo ${index + 1} de ${selection.inputs.size} · Copiando ${input.name}…") }
                        withContext(Dispatchers.IO) { documents.snapshot(input, snapshot) }
                        mutable.update { it.copy(status = "Arquivo ${index + 1} de ${selection.inputs.size} · Convertendo ${input.name}…") }
                        WorkerClient(getApplication()).convert(snapshot, output, conversion) { page, count ->
                            mutable.update { it.copy(status = "Arquivo ${index + 1} de ${selection.inputs.size} · Página $page de $count") }
                        }
                        mutable.update { it.copy(status = "Gravando e conferindo ${input.name}…") }
                        withContext(Dispatchers.IO) {
                            val saved = documents.publish(output, input, conversion, destinations[index]!!)
                            // Record the completed commit before the dispatcher can deliver cancellation.
                            mutable.update { it.copy(results = it.results + FileResult(input.name, saved)) }
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        mutable.update { it.copy(results = it.results + FileResult(input.name, error = "O arquivo excedeu o limite de 3 minutos.")) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        mutable.update { it.copy(results = it.results + FileResult(input.name, error = error.message ?: "Falha na conversão.")) }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            if (!jobDir.deleteRecursively()) notice("Não foi possível limpar um temporário privado. Limpe o cache do aplicativo nas configurações do Android. Ele não foi publicado como resultado.")
                        }
                    }
                }
                mutable.update { it.copy(status = "${it.results.count { result -> result.saved != null }} de ${selection.inputs.size} arquivo(s) convertido(s)") }
            } catch (cancelled: TimeoutCancellationException) { mutable.update { it.copy(status = "O arquivo excedeu o limite de 3 minutos.") } }
            catch (cancelled: CancellationException) { mutable.update { it.copy(status = "Cancelado. Resultados já confirmados foram preservados.") } }
            catch (error: Exception) { notice(error.message ?: "Não foi possível converter.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun cancel() { task?.cancel() }
}
