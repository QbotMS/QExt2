package com.qext2.primary.eta

import org.json.JSONObject

/**
 * Model czasu QBota dla ETA (2026-10-19): {"version","paved":[11],"unpaved":[11],"stops":{profil:[mikro,krotkie]},
 * "p75ByMonthH":[12],"minLongH"} z /ride-readiness (pole "etaModel"). Trzymany na Karoo; nowa wersja = podmiana.
 */
object EtaModelJson {
    fun apply(json: String?): Boolean = try {
        val o = JSONObject(json ?: "")
        fun arr(k: String): DoubleArray? = o.optJSONArray(k)?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        val stops = HashMap<String, DoubleArray>()
        o.optJSONObject("stops")?.let { s ->
            val it = s.keys()
            while (it.hasNext()) {
                val k = it.next(); val a = s.optJSONArray(k) ?: continue
                if (a.length() == 2) stops[k] = doubleArrayOf(a.getDouble(0), a.getDouble(1))
            }
        }
        val pv = arr("paved"); val uv = arr("unpaved")
        pv != null && uv != null && EtaSpeedTable.setModel(o.optString("version", "?"), pv, uv, stops,
            arr("p75ByMonthH"), o.optDouble("minLongH", 2.0))
    } catch (e: Exception) { false }

    fun version(json: String?): String? = try { JSONObject(json ?: "").optString("version").takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
}
