package com.qext2.primary.active

import com.qext2.primary.model.SurfaceType

/**
 * Model optymalnej kadencji.
 *
 * Filozofia: kadencja wynika z mocy. Tę samą moc można wytworzyć kręcąc
 * ciężkim biegiem z niską kadencją lub lekkim biegiem z wysoką kadencją.
 * Model wyznacza zakres optymalny uwzględniając kontekst:
 * nawierzchnię, gradient, formę dnia i zmęczenie.
 *
 * Brak górnej granicy patologicznej — wysoka kadencja jest fizjologicznie
 * bezpieczna. Sygnalizujemy tylko zbyt niską (obciążenie stawów).
 */
object OptimalCadenceModel {

    data class CadenceRange(val low: Int, val high: Int) {
        operator fun contains(rpm: Int) = rpm in low..high
        val mid: Int get() = (low + high) / 2
    }

    /**
     * Oblicza optymalny zakres kadencji.
     *
     * @param powerW        bieżąca moc (W), 3s average
     * @param effectiveFtp  skuteczne FTP uwzględniające TodayFactor (W)
     * @param gradePercent  nachylenie (% — ujemne = zjazd)
     * @param surface       nawierzchnia z cache QBot/RouteGraph
     * @param todayFactor   współczynnik dyspozycji (0.70–1.10)
     * @param decouplingPct decoupling HR/moc (% dryfu)
     */
    fun compute(
        powerW: Int,
        effectiveFtp: Float,
        gradePercent: Double,
        surface: SurfaceType,
        todayFactor: Float,
        decouplingPct: Float,
    ): CadenceRange {
        val ftp = effectiveFtp.coerceAtLeast(50f)
        val powerRatio = (powerW.toFloat() / ftp).coerceIn(0.3f, 1.2f)

        // Baza: liniowo z mocą względem FTP
        // 50% FTP → ~77 RPM, 100% FTP → ~85 RPM, 120% FTP → ~88 RPM
        var baseLow  = (70 + powerRatio * 15).toInt()
        var baseHigh = baseLow + 15

        // Korekta gradientu
        // Na stromym podjeździe: niżej (trakcja, równy moment obrotowy)
        // Na zjeździe: wyżej (efektywność, mniejsze obciążenie stawów)
        val gradeAdj = when {
            gradePercent > 12.0 -> -14
            gradePercent > 10.0 -> -12
            gradePercent > 7.0  -> -10
            gradePercent > 5.0  -> -7
            gradePercent > 3.0  -> -4
            gradePercent < -5.0 -> +7
            gradePercent < -3.0 -> +4
            else -> 0
        }

        // Korekta nawierzchni
        // Gravel/luźna: niżej — równy moment = lepsza trakcja,
        // mniejsze ryzyko poślizgu tylnego koła przy zrywach
        val surfaceAdj = when (surface) {
            SurfaceType.LOOSE   -> -10
            SurfaceType.GRAVEL  -> -5
            SurfaceType.PAVED   -> 0
        }

        // Korekta formy dnia
        // Słaby dzień: niżej — mniejsze obciążenie nerwowo-mięśniowe
        // Dobry dzień: lekko wyżej — stać Cię na wyższą kadencję
        val todayAdj = when {
            todayFactor < 0.82f -> -10
            todayFactor < 0.88f -> -7
            todayFactor < 0.93f -> -4
            todayFactor > 1.05f -> +3
            todayFactor > 1.02f -> +2
            else -> 0
        }

        // Korekta zmęczenia (decoupling HR/moc)
        // Serce dryfuje w górę przy tej samej mocy = organizm się przegrzewa.
        // Niższa kadencja = mniejsze pobudzenie nerwowo-mięśniowe = mniejszy
        // koszt tlenowy poza pedałami.
        val decAdj = when {
            decouplingPct > 12f -> -10
            decouplingPct > 8f  -> -7
            decouplingPct > 5f  -> -4
            else -> 0
        }

        val totalAdj = gradeAdj + surfaceAdj + todayAdj + decAdj
        baseLow  = (baseLow  + totalAdj).coerceIn(48, 95)
        baseHigh = (baseHigh + totalAdj).coerceIn(baseLow + 8, 112)

        return CadenceRange(baseLow, baseHigh)
    }

    /**
     * Ocena odchylenia kadencji od zakresu optymalnego.
     * Używana przez pole CADENCE i przez GearAdvisory.
     */
    enum class CadenceStatus {
        CRITICAL_LOW,   // RPM < opt.low - 10 → czerwony
        LOW,            // RPM < opt.low - 5  → pomarańczowy
        OPTIMAL,        // RPM w zakresie     → biały
        HIGH,           // RPM > opt.high     → brak sygnału (nie sygnalizujemy)
        NO_DATA,        // brak pomiaru
    }

    fun assess(rpm: Int, range: CadenceRange): CadenceStatus {
        if (rpm <= 0) return CadenceStatus.NO_DATA
        return when {
            rpm < range.low - 10 -> CadenceStatus.CRITICAL_LOW
            rpm < range.low - 5  -> CadenceStatus.LOW
            rpm > range.high     -> CadenceStatus.HIGH
            else                 -> CadenceStatus.OPTIMAL
        }
    }
}
