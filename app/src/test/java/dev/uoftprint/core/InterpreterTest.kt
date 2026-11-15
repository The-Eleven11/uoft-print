package dev.uoftprint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Renders generated jobs with real Ghostscript (PostScript) and GhostPCL (PCL) and checks that
 * the interpreter reports no error, produces the expected number of pages, and draws the
 * content where it belongs.
 */
class InterpreterTest {

    private fun writeJob(name: String, spec: JobSpec, pages: Int, page: (Int) -> RasterPage): File {
        val ext = if (spec.language == Language.POSTSCRIPT) "ps" else "pcl"
        val f = File(Interpreter.outDir, "$name.$ext")
        f.outputStream().buffered().use { JobWriter.write(it, spec, pages, page) }
        return f
    }

    private fun quadrants(spec: JobSpec) = Patterns.quadrants(spec.paper.widthPx(spec.dpi), spec.paper.heightPx(spec.dpi))

    private fun assertCleanRun(r: Interpreter.Result, expectedPages: Int) {
        assertEquals("interpreter output:\n${r.output}", 0, r.exitCode)
        assertFalse("interpreter reported an error:\n${r.output}", r.output.contains("Error", ignoreCase = true))
        assertEquals("page count; output:\n${r.output}", expectedPages, r.pages.size)
    }

    /** Checks that the PostScript file is 7-bit, has short lines and that only DSC lines start with '%'. */
    private fun assertWellFormedPostScript(f: File) {
        val text = f.readText(Charsets.ISO_8859_1)
        assertTrue("non-ASCII byte", text.all { it == '\n' || it in ' '..'~' })
        for (line in text.lineSequence()) {
            assertTrue("line too long: ${line.length}", line.length <= 255)
            if (line.startsWith("%")) assertTrue("unexpected comment line: $line", line.startsWith("%%") || line.startsWith("%!"))
        }
        assertTrue(text.startsWith("%!PS-Adobe-3.0\n"))
        assertTrue(text.endsWith("%%EOF\n"))
    }

    @Test
    fun postScriptGrayPlacesContentCorrectly() {
        val gs = Interpreter.gs; assumeTrue("Ghostscript not available", gs != null)
        val spec = JobSpec(Language.POSTSCRIPT, Paper.LETTER, 300, color = false, title = "Quadrants (gray)")
        val f = writeJob("ps-gray-quadrants", spec, 1) { quadrants(spec) }
        assertWellFormedPostScript(f)
        // B&W pages are 1-bit dithered: render at the job resolution and average a block of dots.
        val r = Interpreter.render(gs!!, f, "pgmraw", 300)
        assertCleanRun(r, 1)
        val p = r.pages[0]
        val black = Interpreter.meanGray(p, 0.25, 0.25, 60)
        val red = Interpreter.meanGray(p, 0.75, 0.25, 60)
        val white = Interpreter.meanGray(p, 0.25, 0.75, 60)
        val mid = Interpreter.meanGray(p, 0.75, 0.75, 60)
        val got = "black=$black red=$red white=$white mid=$mid"
        assertTrue(got, black < 1)                 // solid black stays solid
        assertTrue(got, red in 66.0..86.0)         // red as gray (~76)
        assertTrue(got, white > 254)               // white stays clean
        assertTrue(got, mid in 118.0..138.0)       // mid gray (~128)
        // The page must contain only pure black and white dots: no halftoning left to the printer.
        val img = Pnm.read(p)
        for (y in 0 until img.height step 7) for (x in 0 until img.width step 7) {
            val g = img.rgb(x, y) and 0xFF
            assertTrue("gray dot $g at $x,$y", g == 0 || g == 255)
        }
    }

    @Test
    fun postScriptBlackAndWhiteIsCompactAt600Dpi() {
        // A text-like 600 dpi page must stay small: 1-bit + RunLength should be far below raw size.
        val spec = JobSpec(Language.POSTSCRIPT, Paper.LETTER, 600)
        val f = writeJob("ps-bw-600-size", spec, 1) { Patterns.lines(5100, 6600) }
        assertTrue("size ${f.length()}", f.length() < 1_500_000)
        val gs = Interpreter.gs; assumeTrue("Ghostscript not available", gs != null)
        assertCleanRun(Interpreter.render(gs!!, f, "pgmraw", 50), 1)
    }

    @Test
    fun postScriptColorKeepsColor() {
        val gs = Interpreter.gs; assumeTrue("Ghostscript not available", gs != null)
        val spec = JobSpec(Language.POSTSCRIPT, Paper.LETTER, 150, color = true)
        val f = writeJob("ps-color-quadrants", spec, 1) { quadrants(spec) }
        assertWellFormedPostScript(f)
        val r = Interpreter.render(gs!!, f, "ppmraw", 50)
        assertCleanRun(r, 1)
        val (red, green, blue) = Interpreter.meanRgb(r.pages[0], 0.75, 0.25)
        assertTrue("got $red,$green,$blue", red > 240 && green < 15 && blue < 15)
    }

    @Test
    fun postScriptDuplexCopiesPadOddDocuments() {
        val gs = Interpreter.gs; assumeTrue("Ghostscript not available", gs != null)
        val spec = JobSpec(Language.POSTSCRIPT, Paper.LETTER, 150, color = true, duplex = Duplex.LONG_EDGE, copies = 2)
        val f = writeJob("ps-duplex-copies", spec, 3) { i -> if (i == 1) Patterns.lines(1275, 1650) else quadrants(spec) }
        assertWellFormedPostScript(f)
        val r = Interpreter.render(gs!!, f, "pgmraw", 30)
        assertCleanRun(r, 7) // 3 + blank back side + 3
        assertTrue(Interpreter.meanGray(r.pages[3], 0.5, 0.5) > 250) // the padding page is blank
    }

    @Test
    fun postScriptSurvivesIncompressibleData() {
        val gs = Interpreter.gs; assumeTrue("Ghostscript not available", gs != null)
        for ((i, paper) in listOf(Paper.A4, Paper.LEGAL).withIndex()) {
            val spec = JobSpec(Language.POSTSCRIPT, paper, 150, color = i == 1, duplex = Duplex.SHORT_EDGE)
            val f = writeJob("ps-noise-${paper.name}", spec, 2) { k -> Patterns.noise(paper.widthPx(150), paper.heightPx(150), k) }
            assertWellFormedPostScript(f)
            assertCleanRun(Interpreter.render(gs!!, f, "pgmraw", 20), 2)
        }
    }

    @Test
    fun postScriptUsesOnlyLanguageLevel2Operators() {
        // Every executable token outside image data must be a LanguageLevel 1/2 operator, a
        // procedure defined by the job itself, or a DSC comment.
        val level2 = setOf(
            "def", "exch", "currentfile", "filter", "eq", "ifelse", "setcolorspace", "image", "neg",
            "flushfile", "bind", "setpagedevice", "stopped", "cleartomark", "gsave", "grestore", "scale",
            "save", "restore", "showpage", "true", "false", "<<", ">>", "[", "]", "{", "}",
            "string", "readline", "not", "pop", "exit", "if", "loop", "eq",
        )
        val own = setOf("uoftimg", "uoftnc", "uoftbpc", "uoftline", "uoftskip", PostScriptWriter.END_MARKER, "uoftih", "uoftiw", "uofta85", "uoftrle", "uoftpgsave")
        for (color in listOf(false, true)) for (duplex in Duplex.entries) {
            val spec = JobSpec(Language.POSTSCRIPT, Paper.LETTER, 150, color = color, duplex = duplex, copies = 2)
            val f = writeJob("ps-level2", spec, 1) { quadrants(spec) }
            var inData = false
            for (line in f.readLines(Charsets.US_ASCII)) {
                if (inData) { if (line.trimEnd().endsWith("~>")) inData = false; continue }
                if (line.startsWith("%")) continue
                val tokens = Regex("<<|>>|[\\[\\]{}]|[^\\s\\[\\]{}<>/]+|/[^\\s\\[\\]{}<>/]+").findAll(line).map { it.value }
                for (t in tokens) {
                    if (t.startsWith("/") || t.toDoubleOrNull() != null || t.startsWith("(")) continue
                    assertTrue("non-Level-2 token '$t' in: $line", t in level2 || t in own)
                }
                if (line.endsWith(" uoftimg")) inData = true
            }
        }
        // The filters used must be the Level 2 ones.
        val text = File(Interpreter.outDir, "ps-level2.ps").readText()
        assertTrue(text.contains("/ASCII85Decode filter") && text.contains("/RunLengthDecode filter"))
        assertFalse(text.contains("FlateDecode") || text.contains("DCTDecode") || text.contains("LZWDecode"))
    }

    /**
     * Printers differ in what state the ASCII85 filter is in once `image` has all its samples.
     * Simulate each behaviour by wrapping `image`; every page must still render with no error and
     * nothing left over must be executed as PostScript.
     */
    @Test
    fun postScriptSurvivesEveryImageEndBehaviour() {
        val gs = Interpreter.gs; assumeTrue("Ghostscript not available", gs != null)
        val behaviours = mapOf(
            "normal" to emptyList(),
            // Filter already read through "~>" (what the real printer appears to do).
            "consumed-to-eod" to listOf("-c", "/image { systemdict /image get exec uofta85 flushfile } bind def", "-f"),
            // The real printer's failure: filter already at "~>", and flushfile on it raises an error.
            "strict-flushfile" to listOf(
                "-c",
                "/image { systemdict /image get exec uofta85 flushfile } bind def /flushfile { pop flushfile_raised_ioerror } def",
                "-f",
            ),
            // Interpreter closes the filter early, leaving unread data in the stream.
            "closed-early" to listOf("-c", "/image { systemdict /image get exec uofta85 closefile } bind def", "-f"),
        )
        val jobs = listOf(
            JobSpec(Language.POSTSCRIPT, Paper.LETTER, 300, color = false, duplex = Duplex.LONG_EDGE, copies = 2),
            JobSpec(Language.POSTSCRIPT, Paper.LETTER, 150, color = true),
        )
        for ((j, spec) in jobs.withIndex()) {
            val w = spec.paper.widthPx(spec.dpi); val h = spec.paper.heightPx(spec.dpi)
            val f = writeJob("ps-image-end-$j", spec, 3) { i ->
                when (i) { 0 -> Patterns.quadrants(w, h); 1 -> Patterns.noise(w, h, 7); else -> Patterns.lines(w, h) }
            }
            val expected = 3 * spec.copies + if (spec.duplex != Duplex.NONE && spec.copies > 1) spec.copies - 1 else 0
            for ((name, extra) in behaviours) {
                val r = Interpreter.render(gs!!, f, "pgmraw", 20, listOf("-dNOSAFER") + extra)
                assertEquals("[$name] output: ${r.output}", 0, r.exitCode)
                assertFalse("[$name] error: ${r.output}", r.output.contains("Error", ignoreCase = true))
                assertEquals("[$name] pages", expected, r.pages.size)
            }
        }
    }

    @Test
    fun pclPlacesContentCorrectly() {
        val pcl = Interpreter.pcl; assumeTrue("GhostPCL not available", pcl != null)
        for (dpi in listOf(300, 600)) {
            val spec = JobSpec(Language.PCL, Paper.LETTER, dpi, duplex = Duplex.LONG_EDGE, title = "Quadrants \"PCL\"")
            val f = writeJob("pcl-quadrants-$dpi", spec, 2) { quadrants(spec) }
            val r = Interpreter.render(pcl!!, f, "pgmraw", 100)
            assertCleanRun(r, 2)
            val p = r.pages[0]
            assertTrue(Interpreter.meanGray(p, 0.25, 0.25) < 10)
            assertTrue(Interpreter.meanGray(p, 0.25, 0.75) > 245)
            assertTrue(Interpreter.meanGray(p, 0.75, 0.75) in 105.0..150.0)  // dithered mid gray
        }
    }

    @Test
    fun pclAlignsWithPaperEdges() {
        val pcl = Interpreter.pcl; assumeTrue("GhostPCL not available", pcl != null)
        // A 1-inch black square whose left edge sits exactly 1 inch from the paper's left edge.
        val spec = JobSpec(Language.PCL, Paper.LETTER, 300)
        val f = writeJob("pcl-alignment", spec, 1) {
            SyntheticPage(2550, 3300) { x, y -> if (x in 300 until 600 && y in 300 until 600) 0 else 0xFFFFFF }
        }
        val r = Interpreter.render(pcl!!, f, "pgmraw", 300)
        assertCleanRun(r, 1)
        val img = Pnm.read(r.pages[0])
        val row = 450
        val firstBlack = (0 until img.width).first { luminance(img.rgb(it, row)) < 128 }
        assertTrue("left edge at $firstBlack px", firstBlack in 297..303)
        val firstBlackY = (0 until img.height).first { luminance(img.rgb(450, it)) < 128 }
        assertTrue("top edge at $firstBlackY px", firstBlackY in 297..303)
    }

    @Test
    fun pclDuplexCopiesOfOddDocument() {
        val pcl = Interpreter.pcl; assumeTrue("GhostPCL not available", pcl != null)
        val spec = JobSpec(Language.PCL, Paper.A4, 300, duplex = Duplex.LONG_EDGE, copies = 2)
        val f = writeJob("pcl-duplex-copies", spec, 3) { Patterns.noise(spec.paper.widthPx(300), spec.paper.heightPx(300), it) }
        val r = Interpreter.render(pcl!!, f, "pgmraw", 20)
        assertEquals(0, r.exitCode)
        assertFalse(r.output, r.output.contains("Error", ignoreCase = true))
        // GhostPCL renders sides, not sheets: 6 printed sides, plus possibly the blank back.
        assertTrue("pages=${r.pages.size}", r.pages.size in 6..7)
    }
}
