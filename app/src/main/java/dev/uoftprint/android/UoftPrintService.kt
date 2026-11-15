package dev.uoftprint.android

import android.app.PendingIntent
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.util.Log
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import dev.uoftprint.R
import dev.uoftprint.core.Duplex
import dev.uoftprint.core.Paper
import dev.uoftprint.data.PrinterConfig
import dev.uoftprint.data.PrinterRepository
import dev.uoftprint.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exposes the enabled printers to the Android print dialog and prints jobs sent to them. */
class UoftPrintService : PrintService() {
    private val scope = MainScope()
    private val running = HashMap<PrintJobId, Job>()
    private val repo by lazy { PrinterRepository.get(this) }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Discovery()

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!printJob.isQueued) return
        val info = printJob.info
        val printer = info.printerId?.let { repo.find(it.localId) }
        val document = printJob.document.data
        if (printer == null || document == null) {
            printJob.fail(getString(R.string.error_printer_missing))
            return
        }
        printJob.start()
        val jobId = printJob.id
        running[jobId] = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PrintJobRunner(applicationContext).run(
                        document = document,
                        printer = printer,
                        attributes = info.attributes,
                        copies = info.copies,
                        title = info.label,
                        releaseName = repo.releaseName.value,
                    ) { fraction, status ->
                        scope.launch {
                            if (printJob.isStarted) {
                                printJob.setProgress(fraction)
                                printJob.setStatus(status)
                            }
                        }
                    }
                }
                if (printJob.isStarted) printJob.complete()
            } catch (e: CancellationException) {
                if (printJob.isStarted || printJob.isQueued) printJob.cancel()
            } catch (e: PrintFailure) {
                Log.w(PrintJobRunner.TAG, "Job failed: ${e.message}")
                if (printJob.isStarted) printJob.fail(e.message)
            } catch (e: OutOfMemoryError) {
                Log.e(PrintJobRunner.TAG, "Out of memory while rendering", e)
                if (printJob.isStarted) printJob.fail(getString(R.string.error_memory))
            } catch (e: Exception) {
                Log.e(PrintJobRunner.TAG, "Unexpected failure", e)
                if (printJob.isStarted) printJob.fail(getString(R.string.error_unexpected, e.message ?: e.javaClass.simpleName))
            } finally {
                running.remove(jobId)
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val job = running.remove(printJob.id)
        job?.cancel()
        if (printJob.isStarted || printJob.isQueued) printJob.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun printerInfo(p: PrinterConfig): PrinterInfo {
        val id: PrinterId = generatePrinterId(p.id)
        val caps = PrinterCapabilitiesInfo.Builder(id).apply {
            for (paper in p.papers) addMediaSize(mediaSize(paper), paper == p.papers.first())
            val dpi = p.dpiFor(p.color)
            addResolution(PrintAttributes.Resolution("dpi$dpi", "$dpi dpi", dpi, dpi), true)
            if (p.color) {
                setColorModes(PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_COLOR)
            } else {
                setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            }
            if (p.duplexCapable) {
                val all = PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE or PrintAttributes.DUPLEX_MODE_SHORT_EDGE
                val default = when (p.defaultDuplex) {
                    Duplex.NONE -> PrintAttributes.DUPLEX_MODE_NONE
                    Duplex.LONG_EDGE -> PrintAttributes.DUPLEX_MODE_LONG_EDGE
                    Duplex.SHORT_EDGE -> PrintAttributes.DUPLEX_MODE_SHORT_EDGE
                }
                setDuplexModes(all, default)
            } else {
                setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_NONE)
            }
            // Laser printers can't print the outer ~4 mm; ask apps to keep content inside it.
            setMinMargins(PrintAttributes.Margins(170, 170, 170, 170))
        }.build()
        val settings = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PRINTER_ID, p.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, p.id.hashCode(), settings, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return PrinterInfo.Builder(id, p.name, PrinterInfo.STATUS_IDLE)
            .setDescription(if (p.builtIn) p.queue else "${p.queue} @ ${p.host}")
            .setIconResourceId(R.drawable.ic_printer)
            .setInfoIntent(pending)
            .setCapabilities(caps)
            .build()
    }

    private fun mediaSize(p: Paper) = when (p) {
        Paper.LETTER -> PrintAttributes.MediaSize.NA_LETTER
        Paper.LEGAL -> PrintAttributes.MediaSize.NA_LEGAL
        Paper.A4 -> PrintAttributes.MediaSize.ISO_A4
    }

    private inner class Discovery : PrinterDiscoverySession() {
        private var watcher: Job? = null

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            watcher?.cancel()
            watcher = scope.launch {
                repo.printers.collect { all -> publish(all.filter { it.enabled }) }
            }
        }

        private fun publish(enabled: List<PrinterConfig>) {
            if (isDestroyed) return
            val ids = enabled.map { generatePrinterId(it.id) }.toSet()
            val stale = printers.map { it.id }.filter { it !in ids }
            if (stale.isNotEmpty()) removePrinters(stale)
            addPrinters(enabled.map(::printerInfo))
        }

        override fun onStopPrinterDiscovery() {
            watcher?.cancel()
            watcher = null
        }

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            addPrinters(printerIds.mapNotNull { id -> repo.find(id.localId)?.takeIf { it.enabled }?.let(::printerInfo) })
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            val p = repo.find(printerId.localId)
            if (p != null && p.enabled) addPrinters(listOf(printerInfo(p))) else removePrinters(listOf(printerId))
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) {}

        override fun onDestroy() {
            watcher?.cancel()
        }
    }
}
