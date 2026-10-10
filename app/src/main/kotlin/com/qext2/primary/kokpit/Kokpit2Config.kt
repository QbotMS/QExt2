package com.qext2.primary.kokpit

import org.json.JSONObject

/**
 * Ustawienia komunikatow i kolorow pol KOKPIT 2 z QBota (Setup > QExt2 (Karoo); qext2_config.py, docs/QEXT2_CONFIG.md).
 * Przychodza w odpowiedzi /ride-readiness jako "qext2Config" (postac compact()), zapisywane w AthleteDataStore.
 * Brak ustawien = wartosci domyslne podane w miejscu uzycia = dotychczasowe zachowanie QExt2.
 */
object Kokpit2Config {
    /** domyslna kolejnosc = qext2_config.MESSAGES */
    private val DEFAULT_ORDER = listOf("hub", "wprime", "rain_now", "storm", "snow", "rain_soon", "fuel", "dusk_after", "dusk_soon",
        "descent", "climb", "surface", "poi")
    private val ROUTE_GROUP = setOf("descent", "climb", "surface")

    @Volatile private var loaded = false
    @Volatile private var order: List<String> = DEFAULT_ORDER
    @Volatile private var items: JSONObject = JSONObject()
    @Volatile private var rotation: JSONObject = JSONObject()
    @Volatile private var colors: JSONObject = JSONObject()
    @Volatile var updatedAt: String? = null
        private set

    @Synchronized
    fun load(json: String?) {
        loaded = true
        if (json.isNullOrBlank()) return
        try {
            val js = JSONObject(json)
            val m = js.optJSONObject("messages")
            val o = m?.optJSONArray("order")
            if (o != null) {
                val l = ArrayList<String>()
                for (i in 0 until o.length()) l.add(o.optString(i))
                order = l + DEFAULT_ORDER.filter { it !in l }
            }
            items = m?.optJSONObject("items") ?: JSONObject()
            rotation = js.optJSONObject("rotation") ?: JSONObject()
            colors = js.optJSONObject("colors") ?: JSONObject()
            updatedAt = js.optString("updated_at").takeIf { it.isNotBlank() && it != "null" }
        } catch (e: Exception) {
            com.qext2.primary.util.RideFileLog.append("QEXT2_CONFIG_PARSE_FAIL ${e.message}")
        }
    }

    private fun ensure() {
        if (!loaded) load(try { com.qext2.primary.data.AthleteDataStore.loadQext2Config() } catch (_: Exception) { null })
    }

    private fun item(id: String): JSONObject? { ensure(); return items.optJSONObject(id.removeSuffix("_far")) }

    fun on(id: String): Boolean = item(id)?.optBoolean("on", true) ?: true
    fun level(id: String, def: String): String = item(id)?.optString("level", def)?.takeIf { it in setOf("info", "warn", "crit") } ?: def
    fun p(id: String, key: String, def: Double): Double {
        val ps = item(id)?.optJSONObject("params") ?: return def
        return if (ps.has(key)) ps.optDouble(key, def).takeIf { !it.isNaN() } ?: def else def
    }
    fun pi(id: String, key: String, def: Int): Int = Math.round(p(id, key, def.toDouble())).toInt()
    fun pb(id: String, key: String, def: Boolean): Boolean = item(id)?.optJSONObject("params")?.optBoolean(key, def) ?: def
    fun rot(key: String, def: Long): Long { ensure(); return if (rotation.has(key)) Math.round(rotation.optDouble(key, def.toDouble())) else def }
    fun color(id: String, def: String): String { ensure(); return colors.optJSONObject(id)?.optString("v", def)?.takeIf { it.isNotBlank() } ?: def }
    fun cp(id: String, key: String, def: Double): Double {
        ensure(); val ps = colors.optJSONObject(id)?.optJSONObject("params") ?: return def
        return if (ps.has(key)) ps.optDouble(key, def).takeIf { !it.isNaN() } ?: def else def
    }

    /** Kolejnosc wg precedencji z QBota; zjazd/podjazd/nawierzchnia w zasiegu - wspolne miejsce, miedzy soba wg odleglosci;
     *  dalekie zapowiedzi (cfgId *_far) na koncu, jak dotad. Sortowanie stabilne. */
    fun sortByOrder(l: List<RouteMsg>): List<RouteMsg> {
        ensure()
        fun idx(id: String) = order.indexOf(id).let { if (it < 0) 500 else it }
        val g = ROUTE_GROUP.minOf { idx(it) }
        fun rank(m: RouteMsg) = when {
            m.kind == MsgKind.NONE -> 9999
            m.cfgId.endsWith("_far") -> 9000
            m.cfgId in ROUTE_GROUP -> g
            m.cfgId.isEmpty() -> 8000
            else -> idx(m.cfgId)
        }
        return l.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }
}
