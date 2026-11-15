package dev.uoftprint.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.pdf.PdfRenderer
import android.util.Log
import android.os.Build
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import dev.uoftprint.R
import dev.uoftprint.core.Duplex
import dev.uoftprint.core.JobSpec
import dev.uoftprint.core.JobWriter
import dev.uoftprint.core.Language
import dev.uoftprint.core.LpdClient
import dev.uoftprint.core.LpdException
import dev.uoftprint.core.Paper
import dev.uoftprint.data.PrinterConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** A user-facing failure; [message] is already localized. */
class PrintFailure(message: String) : Exception(message)

/**
 * Turns the PDF the Android print framework hands us into PostScript or PCL and sends it over LPD.
 * Runs on a background thread; [progress] receives (fraction, localized status).
 */
class PrintJobRunner(private val context: Context) {

    suspend fun run(
        document: ParcelFileDescriptor,
        printer: PrinterConfig,
        attributes: PrintAttributes,
        copies: Int,
        title: String,
        releaseName: String,
        progress: (Float, String) -> Unit,
    ) {
        var lastFraction = -1f
        var lastStatus = ""
        val report: (Float, String) -> Unit = { f, s ->
            if (s != lastStatus || f - lastFraction >= 0.01f) {
                lastFraction = f
                lastStatus = s
                progress(f, s)
            }
        }
        val job = currentCoroutineContext()[Job]
        fun checkCancelled() { if (job?.isActive == false) throw CancellationException() }

        if (printer.builtIn && releaseName.isBlank()) throw PrintFailure(context.getString(R.string.error_no_release_name))

        val dir = File(context.cacheDir, "jobs").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 6 * 3600_000 }?.forEach { it.delete() }
        val id = UUID.randomUUID().toString()
        val pdf = File(dir, "$id.pdf")
        val out = File(dir, "$id." + if (printer.language == Language.POSTSCRIPT) "ps" else "pcl")
        try {
            report(0f, context.getString(R.string.status_preparing))
            document.use { pfd -> FileInputStream(pfd.fileDescriptor).use { src -> pdf.outputStream().use { src.copyTo(it) } } }
            checkCancelled()

            val color = printer.language == Language.POSTSCRIPT && printer.color &&
                attributes.colorMode == PrintAttributes.COLOR_MODE_COLOR
            val spec = JobSpec(
                language = printer.language,
                paper = paperFor(attributes, printer),
                dpi = printer.dpiFor(color),
                color = color,
                duplex = if (!printer.duplexCapable) Duplex.NONE else duplexFor(attributes),
                copies = copies.coerceIn(1, 99),
                title = title,
            )

            Log.i(TAG, "Job \"$title\" -> ${printer.queue}@${printer.host}:${printer.port}, $spec, pdf=${pdf.length()} B")
            val renderer = try {
                PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY))
            } catch (e: Exception) {
                Log.w(TAG, "PdfRenderer could not open the document", e)
                throw PrintFailure(context.getString(R.string.error_bad_document))
            }
            val started = System.currentTimeMillis()
            renderer.use { r ->
                val pages = r.pageCount
                if (pages == 0) throw PrintFailure(context.getString(R.string.error_bad_document))
                var rendered = 0
                val total = pages * spec.copies
                out.outputStream().buffered(256 * 1024).use { stream ->
                    JobWriter.write(stream, spec, pages) { index ->
                        checkCancelled()
                        report(0.6f * rendered / total, context.getString(R.string.status_rendering, rendered + 1, total))
                        rendered++
                        PdfRasterPage(r.openPage(index), spec.paper.widthPx(spec.dpi), spec.paper.heightPx(spec.dpi))
                    }
                }
            }
            checkCancelled()
            Log.i(TAG, "Rendered ${out.name}: ${out.length()} B in ${System.currentTimeMillis() - started} ms")
            keepDebugCopy(out)

            report(0.6f, context.getString(R.string.status_sending, printer.name))
            try {
                LpdClient(printer.host, printer.port).print(
                    queue = printer.queue,
                    user = releaseName.ifBlank { "android" },
                    jobName = title,
                    dataFile = out,
                    clientHost = Build.MODEL ?: "android",
                    isCancelled = { job?.isActive == false },
                    onProgress = { sent, size -> report(0.6f + 0.4f * sent / size, context.getString(R.string.status_sending, printer.name)) },
                )
                Log.i(TAG, "Sent to ${printer.queue}: server accepted the job")
            } catch (e: LpdException) {
                if (e.reason == LpdException.Reason.CANCELLED) throw CancellationException()
                Log.w(TAG, "LPD failed: ${e.reason}", e)
                throw PrintFailure(describe(e, printer))
            }
        } finally {
            pdf.delete()
            out.delete()
        }
    }

    /**
     * Debug builds keep the last generated job at
     * /sdcard/Android/data/dev.uoftprint/files/last-job/ for inspection (Device Explorer or adb pull).
     */
    private fun keepDebugCopy(out: File) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            val dir = context.getExternalFilesDir("last-job") ?: return
            dir.listFiles()?.forEach { it.delete() }
            out.copyTo(File(dir, "last." + out.extension), overwrite = true)
            Log.d(TAG, "Debug copy: ${dir.absolutePath}/last.${out.extension}")
        }
    }

    private fun describe(e: LpdException, p: PrinterConfig): String = when (e.reason) {
        LpdException.Reason.HOST_NOT_FOUND, LpdException.Reason.CONNECT_FAILED, LpdException.Reason.TIMEOUT -> when {
            p.builtIn -> context.getString(R.string.error_connect_uoft)
            e.reason == LpdException.Reason.HOST_NOT_FOUND -> context.getString(R.string.error_host_not_found, p.host)
            else -> context.getString(R.string.error_connect, p.host)
        }
        LpdException.Reason.QUEUE_REJECTED -> context.getString(R.string.error_queue_rejected, p.queue)
        LpdException.Reason.JOB_REJECTED -> context.getString(R.string.error_job_rejected)
        LpdException.Reason.CONNECTION_LOST, LpdException.Reason.CANCELLED -> context.getString(R.string.error_connection_lost)
    }

    companion object {
        const val TAG = "UofTPrint"

        fun paperFor(attributes: PrintAttributes, printer: PrinterConfig): Paper {
            val id = attributes.mediaSize?.asPortrait()?.id
            val paper = when (id) {
                PrintAttributes.MediaSize.ISO_A4.id -> Paper.A4
                PrintAttributes.MediaSize.NA_LEGAL.id -> Paper.LEGAL
                else -> Paper.LETTER
            }
            return if (paper in printer.papers) paper else printer.papers.first()
        }

        fun duplexFor(attributes: PrintAttributes): Duplex = when (attributes.duplexMode) {
            PrintAttributes.DUPLEX_MODE_LONG_EDGE -> Duplex.LONG_EDGE
            PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> Duplex.SHORT_EDGE
            else -> Duplex.NONE
        }
    }
}
