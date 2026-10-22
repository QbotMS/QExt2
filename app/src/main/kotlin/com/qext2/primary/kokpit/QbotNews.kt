package com.qext2.primary.kokpit

/**
 * QEXT2-NEWS (0.4.31): wazne wiadomosci z QBota -- pole "news" w odpowiedzi na sygnal jazdy
 * (POST /ride-readiness {"ping":{lat,lon}}), obok "rain". QBot ocenia, tlumaczy i skraca (<= 40 znakow);
 * QExt2 tylko kolejkuje i pokazuje: kazdy wpis raz, 15 s; przerywa podjazdy / nawierzchnie, czeka za ostrzezeniami (RouteMessageEngine).
 * Opis: docs/QEXT2_WIADOMOSCI.md w repo QBota.
 */
data class NewsItem(val id: String, val icon: String, val text: String)

object QbotNews {
    /** 22.10 (Michal: min. 10 s): 15 s faktycznie na ekranie -- czas, gdy wiadomosc czeka za ostrzezeniem, sie nie liczy */
    const val SHOW_MS = 15_000L
    private const val MAX_QUEUE = 20
    private val queue = ArrayDeque<NewsItem>()
    private val seen = HashSet<String>()
    private var cur: NewsItem? = null
    private var shownMs = 0L
    private var lastCall = 0L

    /** start / koniec jazdy */
    @Synchronized fun reset() { queue.clear(); seen.clear(); cur = null; shownMs = 0L; lastCall = 0L }

    /** body = odpowiedz serwera na sygnal jazdy; zwraca liczbe nowych wpisow. Brak pola / pusta lista = nic. */
    @Synchronized fun ingest(body: String): Int {
        val arr = try { org.json.JSONObject(body).optJSONArray("news") } catch (e: Exception) { null } ?: return 0
        var n = 0
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val id = o.optString("id", "")
            val text = o.optString("text", "").trim()
            if (id.isEmpty() || text.isEmpty() || !seen.add(id)) continue
            queue.addLast(NewsItem(id, o.optString("icon", "world").trim().lowercase(), text))
            n++
        }
        while (queue.size > MAX_QUEUE) queue.removeFirst()
        return n
    }

    /** Wpis do pokazania teraz (wolane przy kazdym rysowaniu, gdy wiadomosc moze byc na pasku). Ten sam wpis, az przez SHOW_MS
     *  byl faktycznie widoczny (przerwa > 3 s miedzy wywolaniami = wiadomosc czekala za ostrzezeniem, nie liczy sie); potem nastepny. */
    @Synchronized fun current(nowMs: Long): NewsItem? {
        val c = cur
        if (c != null) {
            val dt = nowMs - lastCall
            if (dt in 0L..3_000L) shownMs += dt
            lastCall = nowMs
            if (shownMs < SHOW_MS) return c
        }
        cur = queue.removeFirstOrNull()
        shownMs = 0L
        lastCall = nowMs
        return cur
    }
}
