package dev.uoftprint.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Synthetic page whose pixels are computed by [pixel]. */
class SyntheticPage(override val width: Int, override val height: Int, private val pixel: (Int, Int) -> Int) : RasterPage {
    override fun readRows(y: Int, count: Int, dst: IntArray) {
        for (r in 0 until count) for (x in 0 until width) dst[r * width + x] = pixel(x, y + r) or (0xFF shl 24)
    }
}

object Patterns {
    const val WHITE = 0xFFFFFF
    const val BLACK = 0x000000
    const val RED = 0xFF0000

    /** Top-left black, top-right red, bottom-left white, bottom-right mid gray. */
    fun quadrants(w: Int, h: Int) = SyntheticPage(w, h) { x, y ->
        val left = x < w / 2
        val top = y < h / 2
        when {
            top && left -> BLACK
            top -> RED
            left -> WHITE
            else -> 0x808080
        }
    }

    /** Random pixels: worst case for compression, exercises every ASCII85 character. */
    fun noise(w: Int, h: Int, seed: Int): SyntheticPage {
        val rnd = Random(seed)
        val rows = HashMap<Int, IntArray>()
        return SyntheticPage(w, h) { x, y -> rows.getOrPut(y) { IntArray(w) { rnd.nextInt() and 0xFFFFFF } }[x].also { if (x == w - 1) rows.remove(y) } }
    }

    /** Thin text-like strokes on white. */
    fun lines(w: Int, h: Int) = SyntheticPage(w, h) { x, y -> if ((y / 6) % 4 == 0 && x % 37 < 30) BLACK else WHITE }
}

/** Runs a Ghostscript-family interpreter and returns rendered page files. */
object Interpreter {
    val gs: String? = System.getProperty("gs.path")
    val pcl: String? = System.getProperty("pcl.path")
    val outDir = File(System.getProperty("test.out") ?: "build/test-prints").apply { mkdirs() }

    class Result(val exitCode: Int, val output: String, val pages: List<File>)

    fun render(exe: String, input: File, device: String, dpi: Int, extra: List<String> = emptyList()): Result {
        val prefix = input.nameWithoutExtension
        outDir.listFiles { f -> f.name.startsWith("$prefix-p") }?.forEach { it.delete() }
        val pattern = File(outDir, "$prefix-p%03d.pnm").absolutePath
        val cmd = listOf(exe, "-dBATCH", "-dNOPAUSE", "-dSAFER", "-sDEVICE=$device", "-r$dpi", "-sOutputFile=$pattern") + extra + listOf(input.absolutePath)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor(300, TimeUnit.SECONDS)) { "interpreter timed out" }
        val pages = outDir.listFiles { f -> f.name.startsWith("$prefix-p") && f.extension == "pnm" }!!.sortedBy { it.name }
        return Result(proc.exitValue(), output, pages)
    }

    /** Mean luminance (0..255) of a square around the given fractional position of a rendered page. */
    fun meanGray(page: File, fx: Double, fy: Double, half: Int = 20): Double {
        val img = Pnm.read(page)
        val cx = (img.width * fx).toInt()
        val cy = (img.height * fy).toInt()
        var sum = 0.0
        var n = 0
        for (y in cy - half until cy + half) for (x in cx - half until cx + half) {
            sum += luminance(img.rgb(x, y))
            n++
        }
        return sum / n
    }

    fun meanRgb(page: File, fx: Double, fy: Double, half: Int = 10): Triple<Int, Int, Int> {
        val img = Pnm.read(page)
        val cx = (img.width * fx).toInt()
        val cy = (img.height * fy).toInt()
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in cy - half until cy + half) for (x in cx - half until cx + half) {
            val c = img.rgb(x, y)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        return Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}

/** Reader for binary PGM (P5) and PPM (P6) files as written by Ghostscript's pgmraw / ppmraw devices. */
class Pnm(val width: Int, val height: Int, private val channels: Int, private val data: ByteArray, private val offset: Int) {
    fun rgb(x: Int, y: Int): Int {
        val i = offset + (y * width + x) * channels
        return if (channels == 1) {
            val g = data[i].toInt() and 0xFF
            (g shl 16) or (g shl 8) or g
        } else {
            ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
        }
    }

    companion object {
        fun read(f: File): Pnm {
            val d = f.readBytes()
            var pos = 0
            fun token(): String {
                while (d[pos].toInt().toChar().isWhitespace() || d[pos] == '#'.code.toByte()) {
                    if (d[pos] == '#'.code.toByte()) while (d[pos] != 10.toByte()) pos++
                    pos++
                }
                val start = pos
                while (!d[pos].toInt().toChar().isWhitespace()) pos++
                return String(d, start, pos - start, Charsets.US_ASCII)
            }
            val magic = token()
            val w = token().toInt()
            val h = token().toInt()
            token() // maxval
            pos++   // single whitespace before raster
            return Pnm(w, h, if (magic == "P5") 1 else 3, d, pos)
        }
    }
}
