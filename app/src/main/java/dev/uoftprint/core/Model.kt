package dev.uoftprint.core

import kotlin.math.roundToInt

/** Page description language the printer (or print server queue) expects. */
enum class Language { POSTSCRIPT, PCL }

enum class Duplex { NONE, LONG_EDGE, SHORT_EDGE }

/**
 * Paper sizes in PostScript points (1/72 in).
 * [pclCode] is the PCL page-size code; [pclLeftOffset300] is the PCL logical-page left offset in 300 dpi dots.
 */
enum class Paper(val widthPt: Int, val heightPt: Int, val pclCode: Int, val pclLeftOffset300: Int, val dscName: String) {
    LETTER(612, 792, 2, 75, "Letter"),
    LEGAL(612, 1008, 3, 75, "Legal"),
    A4(595, 842, 26, 71, "A4");

    fun widthPx(dpi: Int) = (widthPt * dpi / 72.0).roundToInt()
    fun heightPx(dpi: Int) = (heightPt * dpi / 72.0).roundToInt()
}

/** Everything a writer needs to know about one print job. */
data class JobSpec(
    val language: Language,
    val paper: Paper = Paper.LETTER,
    val dpi: Int = 300,
    val color: Boolean = false,
    val duplex: Duplex = Duplex.NONE,
    val copies: Int = 1,
    val title: String = "document",
) {
    init {
        require(dpi in setOf(150, 300, 600)) { "Unsupported resolution $dpi" }
        require(copies in 1..999) { "Copies must be 1..999" }
    }
}

/**
 * One portrait page, rendered on demand in horizontal bands of opaque ARGB pixels.
 * Its size always equals the paper size at the job resolution.
 */
interface RasterPage : AutoCloseable {
    val width: Int
    val height: Int

    /** Fills [dst] with rows [y, y + count) as 0xAARRGGBB, row-major with stride [width]. */
    fun readRows(y: Int, count: Int, dst: IntArray)

    override fun close() {}
}

/** Receives the pages of a job in print order. */
interface PageSink {
    /** [totalSheetsSides] is the exact number of page images that will be emitted, including padding pages. */
    fun begin(totalPageImages: Int)
    fun page(page: RasterPage)

    /** Make the next page start on the front of a new sheet (duplex copies of an odd-length document). */
    fun startNewSheet()
    fun end()
}

/** Text that is safe inside DSC comments, PJL strings and LPD control files: printable ASCII only. */
fun sanitizeAscii(s: String, maxLen: Int, fallback: String, allowed: (Char) -> Boolean = { it in ' '..'~' }): String {
    val cleaned = buildString {
        for (c in s) {
            if (length >= maxLen) break
            append(if (allowed(c)) c else '_')
        }
    }.trim()
    return cleaned.ifEmpty { fallback }
}

internal fun rowsPerBand(width: Int): Int = (2_000_000 / width).coerceIn(16, 1024)

internal fun luminance(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 299 + g * 587 + b * 114 + 500) / 1000
}
