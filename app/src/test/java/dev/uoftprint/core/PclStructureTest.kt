package dev.uoftprint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Strict byte-level check of the PCL stream, independent of any interpreter. A real PCL printer
 * prints every byte that is not part of a command as a character, so a single stray or miscounted
 * byte would put garbage on paper. This walks the whole job like a printer's parser and fails on
 * anything a strict printer could misread.
 */
class PclStructureTest {

    private class Parsed(val pages: Int, val rasterRowsPerPage: List<Int>, val commands: Set<String>)

    private fun parse(data: ByteArray, maxRowBytes: Int): Parsed {
        var i = 0
        fun fail(msg: String): Nothing {
            org.junit.Assert.fail("$msg at byte $i")
            throw IllegalStateException() // unreachable
        }
        fun expect(s: String) {
            val b = s.toByteArray(Charsets.US_ASCII)
            for (k in b.indices) if (i + k >= data.size || data[i + k] != b[k]) fail("expected \"${s.replace("\u001B", "ESC")}\"")
            i += b.size
        }
        fun pjlLine(): String {
            expect("@PJL ")
            val start = i
            while (i < data.size && data[i] != '\r'.code.toByte()) {
                val c = data[i].toInt()
                if (c !in 0x20..0x7E) fail("non-printable byte in PJL")
                i++
            }
            val line = String(data, start, i - start, Charsets.US_ASCII)
            expect("\r\n")
            return line
        }

        // Header: UEL, PJL lines, ENTER LANGUAGE=PCL.
        expect("\u001B%-12345X")
        assertTrue(pjlLine().startsWith("JOB NAME=\""))
        assertEquals("ENTER LANGUAGE=PCL", pjlLine())

        var pages = 0
        var rows = 0
        var inRaster = false
        val rowsPerPage = mutableListOf<Int>()
        val commands = mutableSetOf<String>()
        while (true) {
            if (i >= data.size) fail("job ended without UEL")
            val b = data[i].toInt() and 0xFF
            when {
                b == 0x0C -> { // form feed: page eject
                    if (inRaster) fail("form feed inside raster graphics")
                    pages++; rowsPerPage += rows; rows = 0; i++
                }
                b == 0x1B -> {
                    i++
                    val c = data[i].toInt() and 0xFF
                    if (c == 'E'.code) { commands += "ESC E"; i++; continue }
                    if (c == '%'.code) { // UEL ends the PCL part
                        i--
                        expect("\u001B%-12345X")
                        break
                    }
                    if (c !in 0x21..0x2F) fail("bad parameterized character")
                    i++
                    val group = data[i].toInt() and 0xFF
                    if (group !in 0x60..0x7E) fail("bad group character")
                    i++
                    // One or more "value + parameter" pairs; lowercase parameter continues, uppercase ends.
                    while (true) {
                        val vStart = i
                        while (i < data.size && (data[i] in '0'.code.toByte()..'9'.code.toByte() || data[i] == '-'.code.toByte() || data[i] == '+'.code.toByte() || data[i] == '.'.code.toByte())) i++
                        val value = String(data, vStart, i - vStart, Charsets.US_ASCII)
                        val p = data[i].toInt() and 0xFF
                        i++
                        val term = p in 0x40..0x5E
                        if (!term && p !in 0x60..0x7E) fail("bad parameter character")
                        val cmd = "ESC${c.toChar()}${group.toChar()}${p.toChar().uppercaseChar()}"
                        commands += cmd
                        when (cmd) {
                            "ESC*rA" -> { if (inRaster) fail("nested raster start"); inRaster = true }
                            "ESC*rB", "ESC*rC" -> inRaster = false
                            "ESC*bW" -> {
                                if (!inRaster) fail("raster row outside raster graphics")
                                val n = value.toInt()
                                if (n < 0 || i + n > data.size) fail("raster row byte count out of range")
                                val decoded = unpackBits(data, i, n)
                                if (decoded > maxRowBytes) fail("row decodes to $decoded bytes, wider than $maxRowBytes")
                                i += n
                                rows++
                            }
                        }
                        if (term) break
                    }
                }
                else -> fail("stray byte 0x%02x would be printed as text".format(b))
            }
        }
        // Trailer: PJL EOJ and a final UEL, nothing after.
        assertTrue(pjlLine().startsWith("EOJ"))
        expect("\u001B%-12345X")
        if (i != data.size) fail("${data.size - i} bytes after the final UEL")
        return Parsed(pages, rowsPerPage, commands)
    }

    /** Decodes one PackBits row and returns its length; fails on a run crossing the row end. */
    private fun unpackBits(d: ByteArray, off: Int, len: Int): Int {
        var i = off
        var out = 0
        val end = off + len
        while (i < end) {
            val n = d[i++].toInt()
            when {
                n >= 0 -> { if (i + n + 1 > end) fail("literal run crosses row end"); i += n + 1; out += n + 1 }
                n != -128 -> { if (i >= end) fail("repeat run crosses row end"); i++; out += 1 - n }
            }
        }
        return out
    }

    @Test
    fun validatorCatchesCorruption() {
        val spec = JobSpec(Language.PCL, Paper.LETTER, 300)
        val buf = ByteArrayOutputStream()
        JobWriter.write(buf, spec, 1) { Patterns.quadrants(2550, 3300) }
        val good = buf.toByteArray()
        val maxRow = (2550 - 2 * 75 + 7) / 8
        parse(good, maxRow) // sanity: the untouched job passes
        // One extra byte between commands, as an LF -> CRLF conversion by some channel would add.
        val rowCmd = String(good, Charsets.ISO_8859_1).indexOf("\u001B*b")
        val corrupted = good.copyOfRange(0, rowCmd) + byteArrayOf('\r'.code.toByte()) + good.copyOfRange(rowCmd, good.size)
        try {
            parse(corrupted, maxRow)
            fail("corruption not detected")
        } catch (e: AssertionError) {
            assertTrue(e.message!!, e.message!!.contains("stray byte"))
        }
    }

    @Test
    fun everyJobIsAWellFormedPclStream() {
        val specs = listOf(
            JobSpec(Language.PCL, Paper.LETTER, 600),
            JobSpec(Language.PCL, Paper.LETTER, 300, duplex = Duplex.LONG_EDGE, copies = 2),
            JobSpec(Language.PCL, Paper.A4, 600, duplex = Duplex.SHORT_EDGE, copies = 3),
            JobSpec(Language.PCL, Paper.LEGAL, 300, title = "Weird \"name\" \u001B\u000C 作业"),
        )
        for (spec in specs) {
            val w = spec.paper.widthPx(spec.dpi); val h = spec.paper.heightPx(spec.dpi)
            val buf = ByteArrayOutputStream()
            JobWriter.write(buf, spec, 3) { i ->
                when (i) {
                    0 -> Patterns.noise(w, h, 11)                               // every byte value inside rows
                    1 -> SyntheticPage(w, h) { _, _ -> 0xFFFFFF }               // blank page
                    else -> SyntheticPage(w, h) { x, y -> if ((x + y) % 7 == 0) 0x0C0C0C else 0x1B1B1B } // dark page
                }
            }
            val skip = spec.paper.pclLeftOffset300 * spec.dpi / 300
            val maxRow = (w - 2 * skip + 7) / 8
            val parsed = parse(buf.toByteArray(), maxRow)
            assertEquals("pages for $spec", 3 * spec.copies, parsed.pages)
            assertTrue("rows per page for $spec", parsed.rasterRowsPerPage.all { it == h })
            // Only commands that PCL 5 defines and every PCL 5e / PCL 6 printer implements.
            val allowed = setOf("ESC E", "ESC&lX", "ESC&lA", "ESC&lO", "ESC&lS", "ESC&lE", "ESC*tR", "ESC&aG",
                "ESC*pX", "ESC*pY", "ESC*rS", "ESC*rA", "ESC*bM", "ESC*bW", "ESC*rB")
            assertTrue("unexpected commands ${parsed.commands - allowed}", (parsed.commands - allowed).isEmpty())
        }
    }
}
