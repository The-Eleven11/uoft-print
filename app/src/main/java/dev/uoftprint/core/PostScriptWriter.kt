package dev.uoftprint.core

import java.io.OutputStream

/**
 * Writes a DSC-3.0 conforming, LanguageLevel 2 PostScript job made of one full-page image per page.
 *
 * Choices made for compatibility with strict interpreters and print servers:
 *  - Pure 7-bit ASCII output (ASCII85 + RunLength), so no channel can corrupt binary bytes.
 *  - After each image, the procedure reads `currentfile` line by line until a marker line that
 *    follows the image data, so the interpreter resumes exactly after the data whatever state its
 *    filters are in. Interpreters differ here: once `image` has its samples, the ASCII85 filter may
 *    be mid-stream, already past `~>`, or even closed. (An earlier `flushfile` on the filter worked
 *    in Ghostscript but raised ioerror on a real printer when the filter was already at its end.)
 *  - Device features (page size, duplex) are requested inside `stopped` so a printer that lacks
 *    one ignores it instead of aborting the job.
 *
 * Black-and-white pages are sent as 1-bit images that are already dithered (normally at 600 dpi).
 * A 1-bit image is printed dot for dot, so the printer never runs its halftone screen over the
 * anti-aliased gray edges of text, which is what makes 8-bit gray rasters look fuzzy on paper.
 * Colour pages are 8-bit RGB.
 */
class PostScriptWriter(private val out: OutputStream, private val spec: JobSpec) : PageSink {
    private var pageNumber = 0
    private var expectedPages = 0
    private val a85 = Ascii85Writer(out)

    override fun begin(totalPageImages: Int) {
        expectedPages = totalPageImages
        val p = spec.paper
        val title = sanitizeAscii(spec.title, 120, "document") { it in ' '..'~' && it != '(' && it != ')' && it != '\\' }
        text(
            """
            |%!PS-Adobe-3.0
            |%%Creator: UofT Print
            |%%Title: ($title)
            |%%LanguageLevel: 2
            |%%DocumentData: Clean7Bit
            |%%BoundingBox: 0 0 ${p.widthPt} ${p.heightPt}
            |%%DocumentMedia: ${p.dscName} ${p.widthPt} ${p.heightPt} 0 () ()
            |%%Pages: $totalPageImages
            |%%PageOrder: Ascend
            |%%EndComments
            |%%BeginProlog
            |/uoftline 1024 string def
            |/uoftskip {
            |  { currentfile uoftline readline not { pop exit } if
            |    ($END_MARKER) eq { exit } if } loop
            |} bind def
            |/uoftimg {
            |  /uoftnc exch def /uoftbpc exch def /uoftih exch def /uoftiw exch def
            |  /uofta85 currentfile /ASCII85Decode filter def
            |  /uoftrle uofta85 /RunLengthDecode filter def
            |  uoftnc 1 eq { /DeviceGray } { /DeviceRGB } ifelse setcolorspace
            |  <<
            |    /ImageType 1
            |    /Width uoftiw
            |    /Height uoftih
            |    /BitsPerComponent uoftbpc
            |    /Decode uoftnc 1 eq { uoftbpc 1 eq { [1 0] } { [0 1] } ifelse } { [0 1 0 1 0 1] } ifelse
            |    /ImageMatrix [uoftiw 0 0 uoftih neg 0 uoftih]
            |    /DataSource uoftrle
            |  >> image
            |  uoftskip
            |} bind def
            |%%EndProlog
            |%%BeginSetup
            |[{
            |%%BeginFeature: *PageSize ${p.dscName}
            |<< /PageSize [${p.widthPt} ${p.heightPt}] >> setpagedevice
            |%%EndFeature
            |} stopped cleartomark
            |
            """.trimMargin()
        )
        val duplexFeature = when (spec.duplex) {
            Duplex.NONE -> "*Duplex None" to "<< /Duplex false >> setpagedevice"
            Duplex.LONG_EDGE -> "*Duplex DuplexNoTumble" to "<< /Duplex true /Tumble false >> setpagedevice"
            Duplex.SHORT_EDGE -> "*Duplex DuplexTumble" to "<< /Duplex true /Tumble true >> setpagedevice"
        }
        text("[{\n%%BeginFeature: ${duplexFeature.first}\n${duplexFeature.second}\n%%EndFeature\n} stopped cleartomark\n")
        text("%%EndSetup\n")
    }

    override fun page(page: RasterPage) {
        val w = page.width
        val color = spec.color
        beginPage()
        val imageArgs = if (color) "8 3" else "1 1" // bits per component, components
        text("gsave\n${spec.paper.widthPt} ${spec.paper.heightPt} scale\n$w ${page.height} $imageArgs uoftimg\n")

        val band = rowsPerBand(w)
        val argb = IntArray(w * band)
        val binarizer = if (color) null else Binarizer(w)
        val gray = ByteArray(w)
        val row = ByteArray(if (color) w * 3 else binarizer!!.bytesPerRow)
        val packed = ByteArray(PackBits.maxEncodedSize(row.size))
        var y = 0
        while (y < page.height) {
            val n = minOf(band, page.height - y)
            page.readRows(y, n, argb)
            for (r in 0 until n) {
                val base = r * w
                if (binarizer != null) {
                    for (x in 0 until w) gray[x] = luminance(argb[base + x]).toByte()
                    // 1 = black; the prolog decodes 1-bit samples with [1 0]. Output lags one row.
                    if (!binarizer.push(gray, row)) continue
                } else {
                    var o = 0
                    for (x in 0 until w) {
                        val c = argb[base + x]
                        row[o++] = (c shr 16).toByte()
                        row[o++] = (c shr 8).toByte()
                        row[o++] = c.toByte()
                    }
                }
                val len = PackBits.encode(row, 0, row.size, packed)
                a85.write(packed, 0, len)
            }
            y += n
        }
        if (binarizer != null && binarizer.flush(row)) {
            val len = PackBits.encode(row, 0, row.size, packed)
            a85.write(packed, 0, len)
        }
        a85.write(128) // RunLengthDecode end-of-data
        a85.finish()
        text("$END_MARKER\n") // uoftskip reads up to and including this line
        text("grestore\n")
        endPage()
    }

    override fun startNewSheet() {
        // A blank page fills the back of the current sheet.
        beginPage()
        endPage()
    }

    override fun end() {
        check(pageNumber == expectedPages) { "Wrote $pageNumber pages, announced $expectedPages" }
        text("%%Trailer\n%%EOF\n")
        out.flush()
    }

    private fun beginPage() {
        pageNumber++
        text("%%Page: $pageNumber $pageNumber\n%%BeginPageSetup\n/uoftpgsave save def\n%%EndPageSetup\n")
    }

    private fun endPage() {
        text("showpage\nuoftpgsave restore\n%%PageTrailer\n")
    }

    private fun text(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

    companion object {
        /**
         * Line that ends every image's data. ASCII85 data lines are 75-76 characters long except the
         * last, which ends in "~>", so no data line can ever equal it.
         */
        const val END_MARKER = "UoftEndOfImage"
    }
}
