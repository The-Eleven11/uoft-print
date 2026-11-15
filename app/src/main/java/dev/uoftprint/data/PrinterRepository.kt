package dev.uoftprint.data

import android.content.Context
import dev.uoftprint.core.Duplex
import dev.uoftprint.core.Language
import dev.uoftprint.core.Paper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Single source of truth for printers and settings, shared by the UI and the print service
 * (both run in the app process). Stored as JSON in SharedPreferences.
 */
class PrinterRepository private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("printers", Context.MODE_PRIVATE)

    private var overrides: Map<String, BuiltInOverride> = loadOverrides()
    private var custom: List<PrinterConfig> = loadCustom()

    private val _printers = MutableStateFlow(compose())
    val printers: StateFlow<List<PrinterConfig>> = _printers.asStateFlow()

    private val _releaseName = MutableStateFlow(prefs.getString(KEY_RELEASE_NAME, "") ?: "")
    /** Name the job is filed under at the UofT print release station (LPD job owner). */
    val releaseName: StateFlow<String> = _releaseName.asStateFlow()

    fun find(id: String): PrinterConfig? = _printers.value.firstOrNull { it.id == id }

    fun setReleaseName(name: String) {
        _releaseName.value = name
        prefs.edit().putString(KEY_RELEASE_NAME, name).apply()
    }

    fun setEnabled(p: PrinterConfig, enabled: Boolean) = update(p) { copy(enabled = enabled) }
    fun setDefaultDuplex(p: PrinterConfig, duplex: Duplex) = update(p) { copy(defaultDuplex = duplex) }
    fun setDpi(p: PrinterConfig, dpi: Int) = update(p) { copy(dpi = dpi) }

    @Synchronized
    fun saveCustom(p: PrinterConfig) {
        val withId = if (p.id.isEmpty()) p.copy(id = CUSTOM_PREFIX + UUID.randomUUID()) else p
        custom = custom.filterNot { it.id == withId.id } + withId
        persist()
    }

    @Synchronized
    fun deleteCustom(p: PrinterConfig) {
        custom = custom.filterNot { it.id == p.id }
        persist()
    }

    @Synchronized
    private fun update(p: PrinterConfig, change: PrinterConfig.() -> PrinterConfig) {
        val current = find(p.id) ?: return
        val updated = current.change()
        if (updated.builtIn) {
            overrides = overrides + (updated.queue to BuiltInOverride(updated.enabled, updated.defaultDuplex, updated.dpi))
        } else {
            custom = custom.map { if (it.id == updated.id) updated else it }
        }
        persist()
    }

    private fun compose(): List<PrinterConfig> = BuiltInPrinters.all(overrides) + custom.sortedBy { it.name.lowercase() }

    private fun persist() {
        val o = JSONObject()
        for ((queue, v) in overrides) {
            o.put(queue, JSONObject().put("enabled", v.enabled).put("duplex", v.duplex.name).apply { v.dpi?.let { put("dpi", it) } })
        }
        val c = JSONArray()
        for (p in custom) c.put(p.toJson())
        prefs.edit().putString(KEY_OVERRIDES, o.toString()).putString(KEY_CUSTOM, c.toString()).apply()
        _printers.value = compose()
    }

    private fun loadOverrides(): Map<String, BuiltInOverride> = runCatching {
        val o = JSONObject(prefs.getString(KEY_OVERRIDES, "{}")!!)
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            BuiltInOverride(
                enabled = v.optBoolean("enabled"),
                duplex = enumOr(v.optString("duplex"), Duplex.NONE),
                dpi = if (v.has("dpi")) v.getInt("dpi") else null,
            )
        }
    }.getOrDefault(emptyMap())

    private fun loadCustom(): List<PrinterConfig> = runCatching {
        val a = JSONArray(prefs.getString(KEY_CUSTOM, "[]")!!)
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun PrinterConfig.toJson() = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port).put("queue", queue)
        .put("language", language.name).put("color", color).put("duplexCapable", duplexCapable)
        .put("defaultDuplex", defaultDuplex.name).put("dpi", dpi).put("enabled", enabled)
        .put("papers", JSONArray(papers.map { it.name }))

    private fun fromJson(o: JSONObject): PrinterConfig {
        val papers = o.optJSONArray("papers")?.let { a -> (0 until a.length()).map { enumOr(a.getString(it), Paper.LETTER) } }
        return PrinterConfig(
            id = o.getString("id"),
            name = o.getString("name"),
            host = o.getString("host"),
            port = o.optInt("port", 515),
            queue = o.getString("queue"),
            language = enumOr(o.optString("language"), Language.POSTSCRIPT),
            color = o.optBoolean("color"),
            duplexCapable = o.optBoolean("duplexCapable"),
            defaultDuplex = enumOr(o.optString("defaultDuplex"), Duplex.NONE),
            dpi = o.optInt("dpi", 300),
            enabled = o.optBoolean("enabled", true),
            builtIn = false,
            papers = papers?.distinct()?.ifEmpty { null } ?: CUSTOM_PAPERS,
        )
    }

    companion object {
        const val CUSTOM_PREFIX = "custom:"
        val CUSTOM_PAPERS = listOf(Paper.LETTER, Paper.LEGAL, Paper.A4)
        private const val KEY_OVERRIDES = "builtin_overrides"
        private const val KEY_CUSTOM = "custom_printers"
        private const val KEY_RELEASE_NAME = "release_name"

        @Volatile private var instance: PrinterRepository? = null
        fun get(context: Context): PrinterRepository =
            instance ?: synchronized(this) { instance ?: PrinterRepository(context.applicationContext).also { instance = it } }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        fun newCustom() = PrinterConfig(
            id = "", name = "", host = "", port = 515, queue = "", language = Language.POSTSCRIPT,
            color = false, duplexCapable = true, defaultDuplex = Duplex.NONE, dpi = 300,
            enabled = true, builtIn = false, papers = CUSTOM_PAPERS,
        )
    }
}
