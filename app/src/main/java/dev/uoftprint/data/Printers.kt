package dev.uoftprint.data

import dev.uoftprint.core.Duplex
import dev.uoftprint.core.Language
import dev.uoftprint.core.Paper

const val UOFT_HOST = "printhere.utoronto.ca"

data class PrinterConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val queue: String,
    val language: Language,
    val color: Boolean,
    val duplexCapable: Boolean,
    val defaultDuplex: Duplex,
    val dpi: Int,
    val enabled: Boolean,
    val builtIn: Boolean,
    val papers: List<Paper>,
    /** Official U of T building code where the printer stands (built-in printers only). */
    val building: String? = null,
) {
    /** Code shown on the tile: the building code, or for custom printers the start of the queue name. */
    val tileCode: String
        get() = building ?: queue.substringBeforeLast('-', queue).ifEmpty { queue }.take(6)

    /** Resolution actually sent: black-and-white pages are always 1-bit at 600 dpi. */
    fun dpiFor(colorPage: Boolean) = if (colorPage) dpi else 600
}

/** One row of the table on printhere.utoronto.ca/linuxprinting.html. */
private data class UoftQueue(val name: String, val queue: String, val duplex: Boolean, val model: String, val building: String)

/**
 * Building codes follow the U of T campus map (Office of Space Management), with two choices made
 * for recognisability: the Athletic Centre is AC and the Robarts complex is RB. Buildings missing
 * from the map: Jackman Law Building shares LW with Flavelle House, the John W. Graham Library is
 * GR, and the UC Commuter Student Centre is in the University College Union (UP).
 *
 * PCL queues get PCL 5e raster, which every PCL 5e / PCL 6 printer understands.
 */
private val UOFT_QUEUES = listOf(
    UoftQueue("Athletic Centre Colour Printer", "AC-Colour", true, "PS", "AC"),
    UoftQueue("Bora Laskin (Law) Library B&W Printer", "BL-BW", true, "PCL5", "LW"),
    UoftQueue("Chemistry Library Printer", "CL-BW", true, "PS", "LM"),
    UoftQueue("CIRHR Lab (Rm105) Printer", "CIRHR-BW", true, "PS", "IR"),
    UoftQueue("Emmanuel Library B&W Printer", "EM-BW", true, "PS", "EM"),
    UoftQueue("Engineering Library B&W Printer", "EN-BW", true, "PS", "SF"),
    UoftQueue("Gerstein Library B&W Printers", "GE-BW", true, "PS", "SM"),
    UoftQueue("Gerstein Library Colour Printer", "GE-Colour", false, "PS", "SM"),
    UoftQueue("Graham Library B&W Printer", "GR-BW", true, "PS", "GR"),
    UoftQueue("Graham Library Colour Printer", "GR-Colour", false, "PS", "GR"),
    UoftQueue("Kelly Library B&W Printer", "KE-BW", true, "PS", "KL"),
    UoftQueue("Kelly Library Colour Printer", "KE-Colour", false, "PS", "KL"),
    UoftQueue("Milt Harris (Rotman) Library B&W Printer", "MH-BW", true, "PS", "RT"),
    UoftQueue("Music Library B&W Printer", "MU-BW", true, "PCL6", "EJ"),
    UoftQueue("Noranda Library B&W Printer", "NO-BW", true, "PS", "ES"),
    UoftQueue("OISE Library B&W Printer", "OL-BW", true, "PS", "OI"),
    UoftQueue("Pratt Library B&W Printer", "PR-BW", true, "PS", "PR"),
    UoftQueue("Pratt Library Colour Printer", "PR-Colour", true, "PS", "PR"),
    UoftQueue("Rehabilitation Sciences Computer Lab B&W Printer", "RH-BW", true, "PCL5", "RU"),
    UoftQueue("Robarts East Asian Library B&W Printers", "EA-BW", true, "PS", "RB"),
    UoftQueue("Robarts InfoCommons B&W Printers", "IC-BW", true, "PS", "RB"),
    UoftQueue("Robarts InfoCommons Colour Printer", "IC-Colour", false, "PS", "RB"),
    UoftQueue("Robarts Media Commons B&W Printer", "MC-BW", true, "PS", "RB"),
    UoftQueue("Robarts Photocopiers", "RO-MFU", true, "PCL5", "RB"),
    UoftQueue("University College Commuter Student Centre B&W Printer", "UC-CSC-BW", true, "PCL6", "UP"),
    UoftQueue("University College Library B&W Printer", "UC-LIB-BW", true, "PCL6", "UC"),
)

/** Saved per-printer choices for a built-in UofT printer. */
data class BuiltInOverride(val enabled: Boolean = false, val duplex: Duplex = Duplex.NONE, val dpi: Int? = null)

object BuiltInPrinters {
    const val ID_PREFIX = "uoft:"

    fun all(overrides: Map<String, BuiltInOverride>): List<PrinterConfig> = UOFT_QUEUES.map { q ->
        val o = overrides[q.queue] ?: BuiltInOverride()
        val language = if (q.model == "PS") Language.POSTSCRIPT else Language.PCL
        PrinterConfig(
            id = ID_PREFIX + q.queue,
            name = q.name,
            host = UOFT_HOST,
            port = 515,
            queue = q.queue,
            language = language,
            color = q.queue.endsWith("Colour"),
            duplexCapable = q.duplex,
            defaultDuplex = if (q.duplex) o.duplex else Duplex.NONE,
            dpi = o.dpi ?: 300,
            enabled = o.enabled,
            builtIn = true,
            papers = listOf(Paper.LETTER),
            building = q.building,
        )
    }
}
