package com.qext2.primary.kokpit

import org.json.JSONObject

/**
 * 2026-10-14: ostrzezenie o deszczu z RADARU QBota (odpowiedz na sygnal jazdy wysylany co 5 min w czasie nagrywania).
 * Serwer: POST /ride-readiness {"ping":{lat,lon}} -> {"status":"OK","rain":null|{za_min,pada,mmh,sila,dur_min,eta,...}}.
 * Dok. serwera: QBot docs/POGODA_OSTRZEZENIE.md.
 * Pierwszenstwo: swieza odpowiedz QBota (<= FRESH_MS) wygrywa z lokalna prognoza Open-Meteo. Gdy QBot mowi "brak" -
 * lokalna prognoza tylko dla zdarzen dalszych niz RADAR_HOR_MIN (radar widzi ~60 min). Brak odpowiedzi -> jak dotad (zapas).
 * Pole OPAD: radar = wartosc w mm (natezenie), prognoza = % (szansa).
 */
data class QbotRain(val zaMin: Int, val pada: Boolean, val mmh: Float, val sila: String, val durMin: Int, val etaMs: Long)

data class RainMerged(val now: Float?, val soon: RainSoon?)

object QbotRadar {
    const val PING_EVERY_MS = 5 * 60_000L
    const val FRESH_MS = 10 * 60_000L
    const val RADAR_HOR_MIN = 60

    @Volatile var lastOkMs: Long = 0L
        private set
    @Volatile var rain: QbotRain? = null
        private set

    fun reset() { lastOkMs = 0L; rain = null }

    fun accept(nowMs: Long, r: QbotRain?) { rain = r; lastOkMs = nowMs }

    /** Odpowiedz serwera -> (ok, deszcz albo null). ok=false: zla odpowiedz (traktuj jak brak odpowiedzi). */
    fun parse(body: String, nowMs: Long): Pair<Boolean, QbotRain?> {
        val o = try { JSONObject(body) } catch (e: Exception) { return false to null }
        if (o.optString("status") != "OK") return false to null
        val r = o.optJSONObject("rain") ?: return true to null
        val za = r.optInt("za_min", 0)
        return true to QbotRain(
            zaMin = za, pada = r.optBoolean("pada", za <= 2), mmh = r.optDouble("mmh", 0.0).toFloat(),
            sila = r.optString("sila", ""), durMin = r.optInt("dur_min", 0),
            etaMs = etaFrom(r.optString("eta", ""), nowMs) ?: (nowMs + za * 60_000L),
        )
    }

    /** "HH:MM" (czas lokalny) -> ms, najblizsze nowMs (dzis albo +-1 doba). */
    fun etaFrom(hhmm: String, nowMs: Long): Long? {
        val p = hhmm.split(":"); if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMs; set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        var t = c.timeInMillis
        if (t - nowMs > 12 * 3_600_000L) t -= 86_400_000L
        if (nowMs - t > 12 * 3_600_000L) t += 86_400_000L
        return t
    }

    fun merge(nowMs: Long, localNow: Float?, localSoon: RainSoon?): RainMerged = merge(nowMs, localNow, localSoon, lastOkMs, rain)

    /** Czysta funkcja (testy): co pokazac w polu OPAD i komunikacie. */
    fun merge(nowMs: Long, localNow: Float?, localSoon: RainSoon?, okMs: Long, r: QbotRain?): RainMerged {
        if (okMs <= 0L || nowMs - okMs > FRESH_MS) return RainMerged(localNow, localSoon)
        if (r == null) return RainMerged(localNow, localSoon?.takeIf { it.minutes > RADAR_HOR_MIN })
        val za = ((r.etaMs - nowMs) / 60_000L).toInt()
        val koniecMs = r.etaMs + r.durMin * 60_000L
        if (nowMs > koniecMs + 5 * 60_000L) return RainMerged(localNow, localSoon?.takeIf { it.minutes > RADAR_HOR_MIN })
        return if (r.pada || za <= 2) {
            val zost = maxOf(5, ((koniecMs - nowMs) / 60_000L).toInt())
            RainMerged(maxOf(0.1f, r.mmh), RainSoon(0, -1, r.mmh, "RAIN", 0f, radar = true, sila = r.sila, durMin = zost))
        } else {
            RainMerged(localNow, RainSoon(za, -1, r.mmh, "RAIN", 0f, radar = true, sila = r.sila, durMin = r.durMin))
        }
    }
}
