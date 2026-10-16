package com.qext2.primary.info

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** DEMO pola INFO: pogoda od biezacego km DEMO do mety; deszcz ok. 25 km przed toba (w zasiegu radaru, jasne slupki) i drugi dalej (model). */
object InfoDemo {
    fun wx(nowMs: Long, kmNow: Float, totalKm: Float): InfoWx {
        val items = ArrayList<WxItem>()
        val end = maxOf(totalKm, kmNow + 30f)
        var km = kmNow
        while (km <= end) {
            val d = km - kmNow
            val t = d / 19.1f * 60f
            val g = { c: Float, w: Float -> exp(-((d - c) / w) * ((d - c) / w)) }
            // 16.10: DEMO wiatru zmienne -- slaby/mocny, pod wiatr i w plecy na przemian (widac cieniowanie)
            val hw = 5.5f * sin(d / 6f) + 2.5f * sin(d / 1.9f) + 1.0f * cos(d / 13f)
            val mm = (1.6f * g(25f, 5f) + 0.9f * g(85f, 6f)).let { if (it < 0.05f) 0f else it }
            items.add(WxItem(km, t, 11f - 4f * maxOf(0f, d - 30f) / maxOf(1f, end - kmNow) - 1.6f * g(25f, 8f), mm,
                minOf(95f, 10f + 80f * g(25f, 10f) + 50f * g(85f, 10f)), hw, if (hw > 0f) hw * 1.45f + 1.5f else 2f))
            km += 0.5f
        }
        return InfoWx(true, items, nowMs, 120f)
    }
}
