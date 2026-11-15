package dev.uoftprint.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class EncodersTest {

    private fun unpack(data: ByteArray, len: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < len) {
            val n = data[i++].toInt() and 0xFF
            when {
                n < 128 -> { out.write(data, i, n + 1); i += n + 1 }
                n > 128 -> { repeat(257 - n) { out.write(data[i].toInt()) }; i++ }
                else -> error("128 must never be emitted")
            }
        }
        return out.toByteArray()
    }

    @Test
    fun packBitsRoundTrip() {
        val rnd = Random(1)
        val cases = mutableListOf(
            ByteArray(0), ByteArray(1) { 7 }, ByteArray(2) { 7 }, ByteArray(3) { 7 }, ByteArray(500) { 0 },
            ByteArray(129) { it.toByte() }, ByteArray(1000) { (it / 3).toByte() },
        )
        repeat(200) { cases += ByteArray(rnd.nextInt(1, 3000)) { if (rnd.nextInt(4) == 0) rnd.nextInt().toByte() else 0 } }
        repeat(50) { cases += ByteArray(rnd.nextInt(1, 3000)) { rnd.nextInt().toByte() } }
        for (src in cases) {
            val dst = ByteArray(PackBits.maxEncodedSize(src.size))
            val n = PackBits.encode(src, 0, src.size, dst)
            assertArrayEquals(src, unpack(dst, n))
        }
    }

    private fun decodeA85(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        val group = IntArray(5)
        var k = 0
        var i = 0
        val s = text.filter { !it.isWhitespace() }
        while (i < s.length) {
            val c = s[i++]
            if (c == '~') { assertEquals('>', s[i]); break }
            if (c == 'z') { repeat(4) { out.write(0) }; continue }
            group[k++] = c.code - 33
            if (k == 5) {
                var v = 0L
                for (d in group) v = v * 85 + d
                for (sh in 24 downTo 0 step 8) out.write(((v shr sh) and 0xFF).toInt())
                k = 0
            }
        }
        if (k > 0) {
            for (j in k until 5) group[j] = 84
            var v = 0L
            for (d in group) v = v * 85 + d
            for (j in 0 until k - 1) out.write(((v shr (24 - 8 * j)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    @Test
    fun ascii85RoundTripAndLineSafety() {
        val rnd = Random(2)
        for (len in listOf(0, 1, 2, 3, 4, 5, 8, 100, 10_000, 100_001)) {
            val src = ByteArray(len) { if (rnd.nextInt(5) == 0) 0 else rnd.nextInt().toByte() }
            val sink = ByteArrayOutputStream()
            val w = Ascii85Writer(sink)
            w.write(src, 0, src.size)
            w.finish()
            val text = sink.toString(Charsets.US_ASCII.name())
            assertArrayEquals("len=$len", src, decodeA85(text))
            for (line in text.lines()) {
                assertTrue("line too long", line.length <= 76)
                assertFalse("line starts with %", line.startsWith("%"))
            }
            assertTrue(text.trimEnd().endsWith("~>"))
            assertTrue(text.all { it == '\n' || it in ' '..'~' })
        }
    }

    @Test
    fun binarizerKeepsSolidAreasAndEmitsEveryRow() {
        val b = Binarizer(64)
        val out = ByteArray(b.bytesPerRow)
        val rows = mutableListOf<ByteArray>()
        val input = listOf(ByteArray(64) { 0 }, ByteArray(64) { 0 }, ByteArray(64) { 0xFF.toByte() }, ByteArray(64) { 0xFF.toByte() })
        for (r in input) if (b.push(r, out)) rows += out.copyOf()
        if (b.flush(out)) rows += out.copyOf()
        assertEquals(4, rows.size)
        assertTrue(rows[0].all { it == 0xFF.toByte() } && rows[1].all { it == 0xFF.toByte() })
        assertTrue(rows[2].all { it == 0.toByte() } && rows[3].all { it == 0.toByte() })
    }

    /** One bit per pixel, 1 = black. */
    private fun bit(row: ByteArray, x: Int) = (row[x shr 3].toInt() shr (7 - (x and 7))) and 1

    @Test
    fun binarizerSnapsAntiAliasedEdgesInsteadOfDithering() {
        // A vertical black bar with a one-pixel anti-aliased border on each side (gray 100 and 160).
        val w = 32
        val line = ByteArray(w) { x ->
            when (x) { in 10..13 -> 0; 9 -> 160; 14 -> 100; else -> 255 }.toByte()
        }
        val b = Binarizer(w)
        val out = ByteArray(b.bytesPerRow)
        val rows = mutableListOf<ByteArray>()
        repeat(40) { if (b.push(line, out)) rows += out.copyOf() }
        if (b.flush(out)) rows += out.copyOf()
        // Every row must be identical: 100 -> black, 160 -> white, no dither noise along the edge.
        for (r in rows) for (x in 0 until w) {
            val expected = if (x in 10..14) 1 else 0
            assertEquals("x=$x", expected, bit(r, x))
        }
    }

    @Test
    fun binarizerPreservesToneOfGrayFills() {
        for (level in listOf(32, 128, 230)) {
            val w = 200
            val b = Binarizer(w)
            val out = ByteArray(b.bytesPerRow)
            var black = 0
            var total = 0
            val row = ByteArray(w) { level.toByte() }
            repeat(200) {
                if (b.push(row, out)) { for (x in 0 until w) black += bit(out, x); total += w }
            }
            val tone = 255.0 * (1 - black.toDouble() / total)
            assertTrue("level $level printed as $tone", kotlin.math.abs(tone - level) < 6)
        }
    }
}
