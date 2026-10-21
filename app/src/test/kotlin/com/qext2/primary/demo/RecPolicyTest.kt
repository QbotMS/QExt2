package com.qext2.primary.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tryb NAGRANIE (0.4.20, sesja demo-2110). Dowod, ze przy WYLACZONYM trybie QExt2 dziala jak dotad:
 * wysylki do QBota dozwolone i brak pola qext2_demo w pliku jazdy. Plus kontrola scenariusza (60 klatek, bez danych prywatnych).
 */
class RecPolicyTest {

    @Test
    fun wylaczony_tryb_zwykla_jazda_wszystko_jak_dotad() {
        assertTrue(RecPolicy.allowSend(recOn = false, recRide = false))
        assertNull(RecPolicy.demoFitValue(recOn = false, recRide = false))
    }

    @Test
    fun wlaczony_tryb_blokuje_wysylki_i_znakuje_plik() {
        assertFalse(RecPolicy.allowSend(recOn = true, recRide = false))
        assertEquals(1.0, RecPolicy.demoFitValue(recOn = true, recRide = false)!!, 0.0)
    }

    @Test
    fun jazda_nagrania_zostaje_zablokowana_do_konca_nawet_po_wylaczeniu_trybu() {
        assertFalse(RecPolicy.allowSend(recOn = false, recRide = true))
        assertEquals(1.0, RecPolicy.demoFitValue(recOn = false, recRide = true)!!, 0.0)
    }

    @Test
    fun klatki_w_petli_co_sekunde() {
        assertEquals(0, RecPolicy.frameIndex(0L, 60))
        assertEquals(59, RecPolicy.frameIndex(59_999L, 60))
        assertEquals(0, RecPolicy.frameIndex(60_000L, 60))
        assertEquals(5, RecPolicy.frameIndex(125_000L, 60))
        assertEquals(0, RecPolicy.frameIndex(1234L, 0))
    }

    @Test
    fun scenariusz_ma_60_klatek_i_zadnych_danych_prywatnych() {
        val f = listOf(File("src/main/assets/demo_nagranie.json"), File("app/src/main/assets/demo_nagranie.json")).first { it.exists() }
        val txt = f.readText()
        assertEquals(60, Regex("\"t\":\\s*\\d+").findAll(txt).count())
        for (k in listOf("\"lat\"", "\"lon\"", "\"weight", "\"waga", "\"home", "\"address")) assertFalse("klucz $k w scenariuszu", txt.contains(k))
    }
}
