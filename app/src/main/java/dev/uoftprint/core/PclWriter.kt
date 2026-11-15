package dev.uoftprint.core

import java.io.OutputStream

/**
 * Writes a monochrome PCL 5 raster job, the format produced by the classic LaserJet drivers and
 * understood by every PCL 5e / PCL 6 printer. Pages are 1-bit rows (see [Binarizer]) with
 * TIFF PackBits compression (mode 2), framed by PJL so the printer switches to PCL explicitly.
 */
class PclWriter(private val out: OutputStream, private val spec: JobSpec) : PageSink {
    private val esc = 0x1B.toChar()
    private var newSheetPending = false
    private val name = sanitizeAscii(spec.title, 80, "document") { it in ' '..'~' && it != '"' }

    override fun begin(totalPageImages: Int) {
        val duplexCode = when (spec.duplex) {
            Duplex.NONE -> 0
            Duplex.LONG_EDGE -> 1
            Duplex.SHORT_EDGE -> 2
        }
        text("$esc%-12345X@PJL JOB NAME=\"$name\"\r\n@PJL ENTER LANGUAGE=PCL\r\n")
        text("${esc}E")                       // reset
        text("$esc&l1X")                      // one copy (copies are sent as repeated pages)
        text("$esc&l${spec.paper.pclCode}A")  // page size
        text("$esc&l0O")                      // portrait
        text("$esc&l${duplexCode}S")          // simplex / duplex long / duplex short
        text("$esc&l0E")                      // no top margin
        text("$esc*t${spec.dpi}R")            // raster resolution
    }

    override fun page(page: RasterPage) {
        if (newSheetPending) {
            text("$esc&a1G")                  // next page on the front of a new sheet
            newSheetPending = false
        }
        // The PCL logical page starts ~1/4" in from the left paper edge; skip those columns so
        // the image keeps its true position on paper.
        val skip = spec.paper.pclLeftOffset300 * spec.dpi / 300
        val w = page.width
        val outWidth = w - 2 * skip
        val binarizer = Binarizer(outWidth)
        val bits = ByteArray(binarizer.bytesPerRow)
        val packed = ByteArray(PackBits.maxEncodedSize(bits.size))
        val gray = ByteArray(outWidth)

        text("$esc*p0x0Y")                    // cursor to top-left of logical page
        text("$esc*r${outWidth}S")            // raster width
        text("$esc*r1A")                      // start raster at cursor
        text("$esc*b2M")                      // compression mode 2

        val band = rowsPerBand(w)
        val argb = IntArray(w * band)
        var y = 0
        while (y < page.height) {
            val n = minOf(band, page.height - y)
            page.readRows(y, n, argb)
            for (r in 0 until n) {
                val base = r * w + skip
                for (x in 0 until outWidth) gray[x] = luminance(argb[base + x]).toByte()
                if (binarizer.push(gray, bits)) writeRow(bits, packed)
            }
            y += n
        }
        if (binarizer.flush(bits)) writeRow(bits, packed)
        text("$esc*rB")                       // end raster
        text("\u000C")                        // form feed: eject page
    }

    private fun writeRow(bits: ByteArray, packed: ByteArray) {
        var used = bits.size
        while (used > 0 && bits[used - 1].toInt() == 0) used--
        val len = if (used == 0) 0 else PackBits.encode(bits, 0, used, packed)
        text("$esc*b${len}W")
        out.write(packed, 0, len)
    }

    override fun startNewSheet() {
        newSheetPending = true
    }

    override fun end() {
        text("${esc}E")
        text("$esc%-12345X@PJL EOJ NAME=\"$name\"\r\n$esc%-12345X")
        out.flush()
    }

    private fun text(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
}

/** Renders a whole job into [out]: collated copies, with sheet breaks between duplex copies. */
object JobWriter {
    fun write(out: OutputStream, spec: JobSpec, pageCount: Int, openPage: (Int) -> RasterPage) {
        require(pageCount > 0) { "Document has no pages" }
        val sheetBreaks = spec.duplex != Duplex.NONE && pageCount % 2 == 1 && spec.copies > 1
        val sink: PageSink = when (spec.language) {
            Language.POSTSCRIPT -> PostScriptWriter(out, spec)
            Language.PCL -> PclWriter(out, spec)
        }
        val padding = if (sheetBreaks && spec.language == Language.POSTSCRIPT) spec.copies - 1 else 0
        sink.begin(pageCount * spec.copies + padding)
        for (copy in 0 until spec.copies) {
            if (copy > 0 && sheetBreaks) sink.startNewSheet()
            for (i in 0 until pageCount) {
                openPage(i).use { page ->
                    check(page.width == spec.paper.widthPx(spec.dpi) && page.height == spec.paper.heightPx(spec.dpi)) {
                        "Page raster ${page.width}x${page.height} does not match paper"
                    }
                    sink.page(page)
                }
            }
        }
        sink.end()
    }
}
