package dev.uoftprint.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a regular test: when DEMO_DIR is set, wraps the pre-rendered pages page300.pgm / page600.pgm
 * found there and writes them as print jobs with the real writers, for print-quality comparisons.
 */
class SharpnessDemo {
    private class PnmPage(file: File) : RasterPage {
        private val img = Pnm.read(file)
        override val width = img.width
        override val height = img.height
        override fun readRows(y: Int, count: Int, dst: IntArray) {
            for (r in 0 until count) for (x in 0 until width) dst[r * width + x] = img.rgb(x, y + r) or (0xFF shl 24)
        }
    }

    @Test
    fun writeDemoJobs() {
        val dir = System.getenv("DEMO_DIR")?.let(::File)
        assumeTrue("DEMO_DIR not set", dir != null && dir.isDirectory)
        for (dpi in listOf(300, 600)) {
            val src = File(dir, "page$dpi.pgm")
            val ps = JobSpec(Language.POSTSCRIPT, Paper.LETTER, dpi)
            File(dir, "app$dpi.ps").outputStream().buffered().use { JobWriter.write(it, ps, 1) { PnmPage(src) } }
            if (dpi == 600) {
                val pcl = JobSpec(Language.PCL, Paper.LETTER, dpi)
                File(dir, "app$dpi.pcl").outputStream().buffered().use { JobWriter.write(it, pcl, 1) { PnmPage(src) } }
            }
        }
    }
}
