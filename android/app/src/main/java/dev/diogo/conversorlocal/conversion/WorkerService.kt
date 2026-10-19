package dev.diogo.conversorlocal.conversion

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val START = 1
private const val READY = 2
private const val PROGRESS = 3
private const val DONE = 4
private const val CANCEL = 5

/** A bound, non-exported process makes native conversion cancellable even inside a C call. */
class WorkerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == CANCEL) Process.killProcess(Process.myPid())
        if (message.what == START && !busy) {
            busy = true
            val reply = message.replyTo
            fun send(what: Int, body: Bundle = Bundle()) = runCatching {
                reply.send(Message.obtain(null, what).apply { data = body })
            }
            send(READY, Bundle().apply { putInt("pid", Process.myPid()) })
            val input = File(message.data.getString("input")!!)
            val output = File(message.data.getString("output")!!)
            val conversion = Conversion.valueOf(message.data.getString("conversion")!!)
            executor.execute {
                val result = Bundle()
                try {
                    val root = File(cacheDir, "jobs").canonicalFile
                    require(input.canonicalFile.toPath().startsWith(root.toPath()) && output.canonicalFile.toPath().startsWith(root.toPath()))
                    Validation.input(input, conversion)
                    val progress: (Int, Int) -> Unit = { page, count -> send(PROGRESS, Bundle().apply { putInt("page", page); putInt("count", count) }) }
                    when (conversion) {
                        Conversion.DOCX_PDF, Conversion.PPTX_PDF -> OfficeEngine.convert(this, input, output)
                        Conversion.PDF_DOCX -> PdfEngine.toDocx(input, output, progress)
                        Conversion.PDF_PPTX -> PptxWriter.convert(this, input, output, progress)
                    }
                    Validation.output(output, conversion)
                    result.putBoolean("success", true)
                } catch (error: Throwable) {
                    result.putString("error", when (error) {
                        is OutOfMemoryError -> "Memória insuficiente. Tente um documento menor."
                        is java.util.zip.ZipException -> "O documento Office está corrompido, protegido ou usa um formato não suportado."
                        else -> error.message ?: "Falha no motor local."
                    })
                }
                send(DONE, result)
            }
        }
        true
    }) }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { Process.killProcess(Process.myPid()); return false }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}

class WorkerClient(private val context: Context) {
    suspend fun convert(input: File, output: File, conversion: Conversion, progress: (Int, Int) -> Unit) = withTimeout(180_000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            var worker: Messenger? = null
            var pid = 0
            var bound = false
            lateinit var connection: ServiceConnection
            fun cleanup() {
                val running = pid
                pid = 0
                if (running > 0 && running != Process.myPid()) Process.killProcess(running)
                else runCatching { worker?.send(Message.obtain(null, CANCEL)) }
                if (bound) { bound = false; runCatching { context.unbindService(connection) } }
            }
            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (message.what == READY) pid = message.data.getInt("pid")
                if (message.what == PROGRESS && continuation.isActive) progress(message.data.getInt("page"), message.data.getInt("count"))
                if (message.what == DONE && continuation.isActive) {
                    cleanup()
                    if (message.data.getBoolean("success")) continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException(message.data.getString("error")))
                }
                true
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    worker = Messenger(binder)
                    if (!continuation.isActive) { cleanup(); return }
                    runCatching {
                        worker!!.send(Message.obtain(null, START).apply {
                            replyTo = reply
                            data = Bundle().apply { putString("input", input.absolutePath); putString("output", output.absolutePath); putString("conversion", conversion.name) }
                        })
                    }.onFailure { cleanup(); if (continuation.isActive) continuation.resumeWithException(it) }
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    pid = 0
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("O motor local encerrou inesperadamente. Tente um documento menor ou outro arquivo."))
                }
                override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            }
            continuation.invokeOnCancellation { cleanup() }
            bound = context.bindService(Intent(context, WorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("Não foi possível iniciar o motor local."))
        }
    }
}
