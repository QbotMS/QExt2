package com.qext2.primary.info

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** DEMO pola INFO (przelacznik DEMO jak w KOKPIT 2): trasa 74 -> 164 km, deszcz ok. km 113-130 (pierwsze 2 h z "radaru"), zmienny wiatr. */
object InfoDemo {
    fun wx(nowMs: Long): InfoWx {
        val items = ArrayList<WxItem>()
        var km = 74f
        while (km <= 164f) {
            val t = (km - 74f) / 19.1f * 60f
            val g = { c: Float, w: Float -> exp(-((km - c) / w) * ((km - c) / w)) }
            val hw = 3.4f * cos((km - 74f) / 11f) + 2.0f * sin((km - 74f) / 24f)
            val mm = (1.8f * g(122f, 7f) + 0.25f * g(150f, 3f)).let { if (it < 0.05f) 0f else it }
            items.add(WxItem(km, t, 11f - 4f * maxOf(0f, km - 105f) / 59f - 1.6f * g(122f, 9f), mm,
                minOf(95f, 10f + 80f * g(122f, 13f)), hw, if (hw > 0f) hw * 1.45f + 1.5f else 2f))
            km += 0.5f
        }
        return InfoWx(true, items, nowMs, 120f)
    }
}
