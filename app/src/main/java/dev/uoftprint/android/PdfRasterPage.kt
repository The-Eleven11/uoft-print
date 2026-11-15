package dev.uoftprint.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import dev.uoftprint.core.RasterPage
import kotlin.math.min

/**
 * Renders one PDF page onto a portrait sheet of [width] x [height] pixels, band by band, so
 * memory stays bounded at any resolution. The page is scaled to fit and centred; landscape
 * pages are rotated 90° counter-clockwise (the usual convention for printing landscape pages).
 */
class PdfRasterPage(
    private val page: PdfRenderer.Page,
    override val width: Int,
    override val height: Int,
) : RasterPage {
    private val base = Matrix()
    private var band: Bitmap? = null

    init {
        val pw = page.width.toFloat()
        val ph = page.height.toFloat()
        val rotate = (pw > ph) != (width > height)
        val contentW = if (rotate) ph else pw
        val contentH = if (rotate) pw else ph
        val scale = min(width / contentW, height / contentH)
        if (rotate) {
            base.setRotate(-90f)          // (x, y) -> (y, -x): page top edge goes to the left edge
            base.postTranslate(0f, pw)
        }
        base.postScale(scale, scale)
        base.postTranslate((width - contentW * scale) / 2f, (height - contentH * scale) / 2f)
    }

    override fun readRows(y: Int, count: Int, dst: IntArray) {
        val bmp = band?.takeIf { it.height >= count }
            ?: Bitmap.createBitmap(width, count, Bitmap.Config.ARGB_8888).also { band?.recycle(); band = it }
        bmp.eraseColor(Color.WHITE)
        val m = Matrix(base)
        m.postTranslate(0f, -y.toFloat())
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        bmp.getPixels(dst, 0, width, 0, 0, width, count)
    }

    override fun close() {
        band?.recycle()
        band = null
        page.close()
    }
}
