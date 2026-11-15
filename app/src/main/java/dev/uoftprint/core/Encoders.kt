package dev.uoftprint.core

import java.io.OutputStream

/**
 * PackBits run-length encoding. The byte format is identical to the PostScript Level 2
 * RunLengthDecode filter and to PCL raster compression mode 2, so one encoder serves both.
 * It never emits the length byte 128 (EOD in PostScript, no-op in PCL).
 */
object PackBits {
    /** Encodes [len] bytes of [src] from [off] into [dst] starting at 0; returns the encoded length. */
    fun encode(src: ByteArray, off: Int, len: Int, dst: ByteArray): Int {
        var i = off
        val end = off + len
        var o = 0
        while (i < end) {
            var run = 1
            while (i + run < end && run < 128 && src[i + run] == src[i]) run++
            if (run >= 3) {
                dst[o++] = (257 - run).toByte()
                dst[o++] = src[i]
                i += run
            } else {
                val start = i
                var n = 0
                while (i < end && n < 128) {
                    if (i + 2 < end && src[i] == src[i + 1] && src[i] == src[i + 2]) break
                    i++
                    n++
                }
                dst[o++] = (n - 1).toByte()
                System.arraycopy(src, start, dst, o, n)
                o += n
            }
        }
        return o
    }

    /** Worst-case encoded size for [len] input bytes. */
    fun maxEncodedSize(len: Int) = len + (len + 127) / 128 + 1
}

/**
 * Streaming ASCII85 encoder for PostScript data. Output is 7-bit clean, uses lines of at most
 * 76 characters, and never starts a line with '%', so DSC-aware spoolers can't mistake image
 * data for comments. [finish] writes the "~>" end-of-data marker.
 */
class Ascii85Writer(private val out: OutputStream) {
    private var tuple = 0L
    private var count = 0
    private var column = 0
    private val chars = ByteArray(5)

    fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }

    fun write(b: Int) {
        tuple = (tuple shl 8) or (b and 0xFF).toLong()
        if (++count == 4) {
            if (tuple == 0L) emit('z'.code) else emitGroup(5)
            tuple = 0
            count = 0
        }
    }

    fun finish() {
        if (count > 0) {
            tuple = tuple shl (8 * (4 - count))
            emitGroup(count + 1)
            tuple = 0
            count = 0
        }
        if (column > 74) newline()
        emit('~'.code)
        emit('>'.code)
        newline()
    }

    private fun emitGroup(n: Int) {
        var t = tuple
        for (i in 4 downTo 0) {
            chars[i] = (t % 85 + 33).toByte()
            t /= 85
        }
        for (i in 0 until n) emit(chars[i].toInt())
    }

    private fun emit(c: Int) {
        if (column >= 75) newline()
        if (column == 0 && c == '%'.code) {
            out.write(' '.code)
            column++
        }
        out.write(c)
        column++
    }

    private fun newline() {
        out.write('\n'.code)
        column = 0
    }
}

/**
 * Converts 8-bit gray rows (0 = black, 255 = white) to packed 1-bit rows (1 = black) for a laser
 * printer, which can only place a dot or not.
 *
 * The renderer anti-aliases text, so glyph edges arrive as gray pixels. Dithering those makes
 * edges speckled, and leaving them gray makes the printer's halftone screen fray them. Instead,
 * pixels that sit on a sharp dark/light boundary are snapped to black or white at 50%, which is
 * exactly how a printer's own rasterizer fills vector text at the same resolution. Only real gray
 * areas (fills, shading, photos) are Floyd–Steinberg dithered, so they keep their tone.
 *
 * Classification needs the rows above and below, so output lags input by one row: call [push]
 * for every row and [flush] once at the end; together they emit exactly one row per input row.
 */
class Binarizer(private val width: Int) {
    val bytesPerRow = (width + 7) / 8
    private var prev: IntArray? = null
    private var cur: IntArray? = null
    private var next: IntArray? = null
    private val colMin = IntArray(width)
    private val colMax = IntArray(width)
    private var errCur = IntArray(width + 2)
    private var errNext = IntArray(width + 2)
    private var rowIndex = 0

    /** Adds the next input row; returns true when [out] was filled with an output row. */
    fun push(gray: ByteArray, out: ByteArray): Boolean {
        val row = (prev?.takeIf { it !== cur && it !== next } ?: IntArray(width))
        for (x in 0 until width) row[x] = gray[x].toInt() and 0xFF
        prev = cur
        cur = next
        next = row
        val c = cur ?: return false
        emit(prev ?: c, c, row, out)
        return true
    }

    /** Emits the last buffered row. */
    fun flush(out: ByteArray): Boolean {
        val n = next ?: return false
        emit(cur ?: n, n, n, out)
        prev = null; cur = null; next = null
        return true
    }

    private fun emit(above: IntArray, row: IntArray, below: IntArray, out: ByteArray) {
        for (x in 0 until width) {
            val a = above[x]; val b = row[x]; val c = below[x]
            colMin[x] = minOf(a, b, c)
            colMax[x] = maxOf(a, b, c)
        }
        java.util.Arrays.fill(out, 0, bytesPerRow, 0)
        java.util.Arrays.fill(errNext, 0)
        val leftToRight = rowIndex % 2 == 0
        var x = if (leftToRight) 0 else width - 1
        val step = if (leftToRight) 1 else -1
        repeat(width) {
            val v = row[x]
            val black: Boolean
            if (v <= SOLID_BLACK || v >= SOLID_WHITE) {
                black = v <= SOLID_BLACK
            } else {
                val l = maxOf(x - 1, 0)
                val r = minOf(x + 1, width - 1)
                val lo = minOf(colMin[l], colMin[x], colMin[r])
                val hi = maxOf(colMax[l], colMax[x], colMax[r])
                if (lo < EDGE_DARK && hi > EDGE_LIGHT) {
                    black = v < 128 // anti-aliased edge: snap, like a printer filling a glyph outline
                } else {
                    val value = v + errCur[x + 1] / 16
                    black = value < 128
                    val err = value - if (black) 0 else 255
                    errCur[x + 1 + step] += err * 7
                    errNext[x + 1 - step] += err * 3
                    errNext[x + 1] += err * 5
                    errNext[x + 1 + step] += err
                }
            }
            if (black) out[x shr 3] = (out[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
            x += step
        }
        val t = errCur
        errCur = errNext
        errNext = t
        rowIndex++
    }

    private companion object {
        const val SOLID_BLACK = 8
        const val SOLID_WHITE = 247
        const val EDGE_DARK = 64
        const val EDGE_LIGHT = 192
    }
}
