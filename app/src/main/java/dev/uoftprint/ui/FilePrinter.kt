package dev.uoftprint.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.provider.OpenableColumns
import android.widget.Toast
import dev.uoftprint.R
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Opens the system print dialog for a PDF or image the user picked or shared. */
object FilePrinter {
    fun print(activity: Activity, uri: Uri, onFinish: () -> Unit = {}) {
        val resolver = activity.contentResolver
        val type = resolver.getType(uri) ?: ""
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "document"
        val adapter = if (type.startsWith("image/")) ImageAdapter(activity, uri, name, onFinish) else PdfAdapter(activity, uri, name, onFinish)
        try {
            activity.getSystemService(PrintManager::class.java).print(name, adapter, null)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.error_open_file, Toast.LENGTH_LONG).show()
            onFinish()
        }
    }

    private class PdfAdapter(val activity: Activity, val uri: Uri, val name: String, val done: () -> Unit) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel.isCanceled) { cb.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            cb.onLayoutFinished(info, old == null)
        }

        override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal, cb: WriteResultCallback) {
            try {
                activity.contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(dest.fileDescriptor).use { input.copyTo(it) }
                }
                if (cancel.isCanceled) cb.onWriteCancelled() else cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(activity.getString(R.string.error_open_file))
            }
        }

        override fun onFinish() = done()
    }

    /** Lays an image out on one page, fitted inside the printable area and rotated to match. */
    private class ImageAdapter(val activity: Activity, val uri: Uri, val name: String, val done: () -> Unit) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel.isCanceled) { cb.onLayoutCancelled(); return }
            attributes = new
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                .setPageCount(1)
                .build()
            cb.onLayoutFinished(info, new != old)
        }

        override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal, cb: WriteResultCallback) {
            val attrs = attributes ?: run { cb.onWriteFailed(null); return }
            val doc = PrintedPdfDocument(activity, attrs)
            try {
                val bitmap = decode()
                val page = doc.startPage(0)
                val content = RectF(page.info.contentRect)
                val rotate = (bitmap.width > bitmap.height) != (content.width() > content.height())
                val bw = if (rotate) bitmap.height.toFloat() else bitmap.width.toFloat()
                val bh = if (rotate) bitmap.width.toFloat() else bitmap.height.toFloat()
                val scale = min(content.width() / bw, content.height() / bh)
                val m = Matrix()
                if (rotate) { m.postRotate(90f); m.postTranslate(bitmap.height.toFloat(), 0f) }
                m.postScale(scale, scale)
                m.postTranslate(content.left + (content.width() - bw * scale) / 2, content.top + (content.height() - bh * scale) / 2)
                page.canvas.drawBitmap(bitmap, m, null)
                doc.finishPage(page)
                if (cancel.isCanceled) { cb.onWriteCancelled(); return }
                FileOutputStream(dest.fileDescriptor).use { doc.writeTo(it) }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                cb.onWriteFailed(activity.getString(R.string.error_open_file))
            } finally {
                doc.close()
            }
        }

        private fun decode(): Bitmap {
            val source = ImageDecoder.createSource(activity.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > 3000) {
                    val s = 3000f / longest
                    decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                }
            }
        }

        override fun onFinish() = done()
    }
}

/** Target of "Share" / "Open with": shows the print dialog, then disappears. */
class PrintFileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri == null) {
            Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        FilePrinter.print(this, uri) { finish() }
    }
}
