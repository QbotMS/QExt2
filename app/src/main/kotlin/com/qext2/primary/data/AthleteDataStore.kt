package com.qext2.primary.data

import android.content.Context
import android.content.SharedPreferences

data class AthleteData(
    val ftp: Int = 250,
    val wPrimeKj: Double = 3.75,
    val todayFactor: Float = 1.0f,
    val ltpWatts: Int = 0,
    val ctl: Float = 60f,
    val atl: Float = 40f,
    val humidityPercent: Float = 50f,
    val sunsetTimestampMs: Long = 0L,
    val maxHr: Int = 180,
    val bodyWeightKg: Float = 75f,
    val xertStatus: String = "--",
    val hrvToday: Int = 0,
    val hrvBaseline30d: Float = 0f,
    val hrvDeviation30d: Float = 0f,
    val sleepTodayH: Float = 0f,
    val sleepBaseline30d: Float = 0f,
    val sleepDev: Float = 0f,
    val restingHrDev: Float = 0f,
    val pressureHpa: Float = 1013f,
    val pressureChange24h: Float = 0f,
    val pressureDeficit: Float = 0f,
    val baroMultiplier: Float = 1.0f,
    val partial: Boolean = false,
    val warningReasons: String = "",
    val fetchTimestamp: Long = 0L
) {
    val wPrimeJoules: Double get() = wPrimeKj * 1000

    companion object {
        // KANON todayFactor (2026-07-24). Jedne widelki, zgodne z modelem serwera
        // (readiness_effective, k=0.10, clamp [0.70, 1.10]). Obcinamy U ZRODLA:
        // przy parsowaniu JSON, przy load() i przy save().
        // Konsumenci NIE obcinaja juz po swojemu -- wczesniej bylo 5 roznych klamer
        // (0.85-1.05, 0.75-1.10, 0.50-1.10 x3, brak) i DWA zrodla tej samej liczby
        // (todayFactorRef vs AthleteDataStore.load()), wiec CP i RSRV liczyly sie
        // z innych wartosci w tym samym ticku.
        // Klamry na ILOCZYNACH (cf 0.88-1.06, LTP 0.75-1.10) zostaja -- pilnuja
        // czegos innego niz sam todayFactor.
        const val TODAY_FACTOR_MIN = 0.70f
        const val TODAY_FACTOR_MAX = 1.10f

        fun clampTodayFactor(v: Float): Float =
            if (v.isNaN() || v.isInfinite()) 1.0f
            else v.coerceIn(TODAY_FACTOR_MIN, TODAY_FACTOR_MAX)

        // --- Bramka wieku danych zawodnika (2026-07-24, audyt pol pkt B1) ---
        // todayFactor to sygnal DZIENNY -- po dobie przestaje byc prawdziwy. Wczesniej
        // stary odczyt (brak zasiegu rano, niedostepny serwer) cicho napedzal CP, RSRV
        // i pacing jako dane biezace. Rampa zamiast progu skokowego, zeby liczba nie
        // skoczyla w srodku dlugiej jazdy przekraczajacej dobe.
        // FTP / LTP / W' / CTL NIE sa degradowane -- zmieniaja sie wolno.
        const val TODAY_FACTOR_FRESH_H = 24.0
        const val TODAY_FACTOR_DEAD_H = 48.0

        /** Wiek danych w godzinach; brak odczytu => nieskonczonosc. */
        fun dataAgeHours(fetchTimestampMs: Long, nowMs: Long): Double =
            if (fetchTimestampMs <= 0L) Double.MAX_VALUE
            else (nowMs - fetchTimestampMs).coerceAtLeast(0L) / 3_600_000.0

        /**
         * todayFactor skorygowany o wiek: <=24 h pelna wartosc, 24-48 h liniowe sciaganie
         * odchylenia do 1.0, >48 h lub brak odczytu => neutralne 1.0.
         */
        fun ageAdjustedTodayFactor(raw: Float, fetchTimestampMs: Long, nowMs: Long): Float {
            val tf = clampTodayFactor(raw)
            val ageH = dataAgeHours(fetchTimestampMs, nowMs)
            if (ageH <= TODAY_FACTOR_FRESH_H) return tf
            if (ageH >= TODAY_FACTOR_DEAD_H) return 1.0f
            val k = ((TODAY_FACTOR_DEAD_H - ageH) /
                (TODAY_FACTOR_DEAD_H - TODAY_FACTOR_FRESH_H)).toFloat()
            return 1.0f + (tf - 1.0f) * k
        }

        fun isTodayFactorDegraded(fetchTimestampMs: Long, nowMs: Long): Boolean =
            dataAgeHours(fetchTimestampMs, nowMs) > TODAY_FACTOR_FRESH_H
    }

    fun applyBaroAdjustment(baroSensitive: Boolean): AthleteData {
        if (!baroSensitive) return this
        val m = baroMultiplier.coerceIn(0.80f, 1.00f)
        if (m >= 1.00f) return this
        return copy(todayFactor = clampTodayFactor(todayFactor * m))
    }

    val baroAdjustPercent: Int
        get() {
            val m = baroMultiplier.coerceIn(0.80f, 1.00f)
            return ((1f - m) * 100f).toInt()
        }

    val profileComplete: Boolean
        get() = warningReasons.isEmpty()

    val todayFactorDisplay: String
        get() = "%.2f".format(todayFactor)
}

private const val PREFS_NAME = "qext2_athlete"
private const val KEY_FTP = "ftp"
private const val KEY_WPRIME_KJ = "wprime_kj"
private const val KEY_FACTOR = "factor"
private const val KEY_LTP = "ltp"
private const val KEY_CTL = "ctl"
private const val KEY_ATL = "atl"
private const val KEY_HUMIDITY = "humidity"
private const val KEY_SUNSET_TS = "sunset_ts"
private const val KEY_MAX_HR = "max_hr"
private const val KEY_WEIGHT = "weight"
private const val KEY_FETCH_TS = "fetch_ts"
private const val KEY_XERT_STATUS = "xert_status"
private const val KEY_HRV_TODAY = "hrv_today"
private const val KEY_HRV_BASELINE = "hrv_baseline"
private const val KEY_HRV_DEV = "hrv_dev"
private const val KEY_SLEEP_TODAY = "sleep_today"
private const val KEY_SLEEP_BASELINE = "sleep_baseline"
private const val KEY_SLEEP_DEV = "sleep_dev"
private const val KEY_REST_HR_DEV = "rest_hr_dev"
private const val KEY_PRESSURE = "pressure"
private const val KEY_PRESSURE_CHANGE = "pressure_change"
private const val KEY_PRESSURE_DEFICIT = "pressure_deficit"
private const val KEY_BARO_MULTI = "baro_multi"
private const val KEY_PARTIAL = "partial"
private const val KEY_WARNINGS = "warnings"
private const val KEY_SLEEP_DATA_DATE_MARKER = "sleep_data_date_marker"
private const val KEY_SLEEP_REFRESH_PENDING = "sleep_refresh_pending"
private const val KEY_RESERVE_DAILY_TSS_BASE = "reserve_daily_tss_base"
private const val KEY_RESERVE_DAILY_XSS_BASE = "reserve_daily_xss_base"

object AthleteDataStore {
    private var prefs: SharedPreferences? = null
    private const val KEY_DEADLINE_HOUR = "deadline_hour"
    private const val KEY_DEADLINE_MIN = "deadline_min"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(): AthleteData {
        val p = prefs ?: return AthleteData()
        return AthleteData(
            ftp = p.getInt(KEY_FTP, 250),
            wPrimeKj = p.getDouble(KEY_WPRIME_KJ, 3.75),
            todayFactor = AthleteData.clampTodayFactor(p.getFloat(KEY_FACTOR, 1.0f)),
            ltpWatts = p.getInt(KEY_LTP, 0),
            ctl = p.getFloat(KEY_CTL, 60f),
            atl = p.getFloat(KEY_ATL, 40f),
            humidityPercent = p.getFloat(KEY_HUMIDITY, 50f),
            sunsetTimestampMs = p.getLong(KEY_SUNSET_TS, 0L),
            maxHr = p.getInt(KEY_MAX_HR, 180),
            bodyWeightKg = p.getFloat(KEY_WEIGHT, 75f),
            xertStatus = p.getString(KEY_XERT_STATUS, "--") ?: "--",
            hrvToday = p.getInt(KEY_HRV_TODAY, 0),
            hrvBaseline30d = p.getFloat(KEY_HRV_BASELINE, 0f),
            hrvDeviation30d = p.getFloat(KEY_HRV_DEV, 0f),
            sleepTodayH = p.getFloat(KEY_SLEEP_TODAY, 0f),
            sleepBaseline30d = p.getFloat(KEY_SLEEP_BASELINE, 0f),
            sleepDev = p.getFloat(KEY_SLEEP_DEV, 0f),
            restingHrDev = p.getFloat(KEY_REST_HR_DEV, 0f),
            pressureHpa = p.getFloat(KEY_PRESSURE, 1013f),
            pressureChange24h = p.getFloat(KEY_PRESSURE_CHANGE, 0f),
            pressureDeficit = p.getFloat(KEY_PRESSURE_DEFICIT, 0f),
            baroMultiplier = p.getFloat(KEY_BARO_MULTI, 1.0f),
            partial = p.getBoolean(KEY_PARTIAL, false),
            warningReasons = p.getString(KEY_WARNINGS, "") ?: "",
            fetchTimestamp = p.getLong(KEY_FETCH_TS, 0L)
        )
    }

    fun save(data: AthleteData) {
        prefs?.edit()?.apply {
            putInt(KEY_FTP, data.ftp)
            putDouble(KEY_WPRIME_KJ, data.wPrimeKj)
            putFloat(KEY_FACTOR, AthleteData.clampTodayFactor(data.todayFactor))
            putInt(KEY_LTP, data.ltpWatts)
            putFloat(KEY_CTL, data.ctl)
            putFloat(KEY_ATL, data.atl)
            putFloat(KEY_HUMIDITY, data.humidityPercent)
            putLong(KEY_SUNSET_TS, data.sunsetTimestampMs)
            putInt(KEY_MAX_HR, data.maxHr)
            putFloat(KEY_WEIGHT, data.bodyWeightKg)
            putString(KEY_XERT_STATUS, data.xertStatus)
            putInt(KEY_HRV_TODAY, data.hrvToday)
            putFloat(KEY_HRV_BASELINE, data.hrvBaseline30d)
            putFloat(KEY_HRV_DEV, data.hrvDeviation30d)
            putFloat(KEY_SLEEP_TODAY, data.sleepTodayH)
            putFloat(KEY_SLEEP_BASELINE, data.sleepBaseline30d)
            putFloat(KEY_SLEEP_DEV, data.sleepDev)
            putFloat(KEY_REST_HR_DEV, data.restingHrDev)
            putFloat(KEY_PRESSURE, data.pressureHpa)
            putFloat(KEY_PRESSURE_CHANGE, data.pressureChange24h)
            putFloat(KEY_PRESSURE_DEFICIT, data.pressureDeficit)
            putFloat(KEY_BARO_MULTI, data.baroMultiplier)
            putBoolean(KEY_PARTIAL, data.partial)
            putString(KEY_WARNINGS, data.warningReasons)
            putLong(KEY_FETCH_TS, data.fetchTimestamp)
            apply()
        }
    }

    private fun SharedPreferences.Editor.putDouble(key: String, value: Double) {
        putLong(key, java.lang.Double.doubleToRawLongBits(value))
    }

    private fun SharedPreferences.getDouble(key: String, default: Double): Double {
        val bits = getLong(key, java.lang.Double.doubleToRawLongBits(default))
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun saveDeadline(hour: Int, minute: Int) {
        prefs?.edit()?.apply {
            putInt(KEY_DEADLINE_HOUR, hour.coerceIn(0, 23))
            putInt(KEY_DEADLINE_MIN, minute.coerceIn(0, 59))
            apply()
        }
    }

    fun loadDeadline(): Pair<Int, Int> {
        val p = prefs ?: return 21 to 0
        return p.getInt(KEY_DEADLINE_HOUR, 21) to p.getInt(KEY_DEADLINE_MIN, 0)
    }

    fun saveBaroSensitive(enabled: Boolean) {
        prefs?.edit()?.putBoolean("baro_sensitive", enabled)?.apply()
    }

    fun loadBaroSensitive(): Boolean {
        return prefs?.getBoolean("baro_sensitive", true) ?: true
    }

    fun saveHrZoneMode(enabled: Boolean) {
        prefs?.edit()?.putBoolean("hr_zone_mode", enabled)?.apply()
    }

    fun loadHrZoneMode(): Boolean {
        return prefs?.getBoolean("hr_zone_mode", false) ?: false
    }

    fun saveCapTwilight(enabled: Boolean) {
        prefs?.edit()?.putBoolean("cap_twilight", enabled)?.apply()
    }

    fun loadCapTwilight(): Boolean {
        return prefs?.getBoolean("cap_twilight", false) ?: false
    }

    // Riding mode: 0=defensive, 1=normal, 2=offensive
    fun saveRidingMode(mode: Int) {
        prefs?.edit()?.putInt("riding_mode", mode.coerceIn(0, 3))?.apply()
    }

    fun loadRidingMode(): Int = prefs?.getInt("riding_mode", 3) ?: 3

    fun loadRidingModeFactor(): Float = when (loadRidingMode()) {
        0 -> 0.88f
        2 -> 1.12f
        3 -> 1.00f  // AUTO placeholder; aggregator overrides with todayFactor-derived value
        else -> 1.00f
    }

    fun saveLastRefresh() {
        prefs?.edit()?.putLong("last_refresh_ts", System.currentTimeMillis())?.apply()
    }

    fun loadLastRefresh(): Long {
        return prefs?.getLong("last_refresh_ts", 0L) ?: 0L
    }

    fun updateSleepDataDateMarker(markerRaw: String?): Boolean {
        val marker = markerRaw?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val previous = loadSleepDataDateMarker()
        if (previous == marker) return false
        prefs?.edit()?.apply {
            putString(KEY_SLEEP_DATA_DATE_MARKER, marker)
            putBoolean(KEY_SLEEP_REFRESH_PENDING, previous != null)
            apply()
        }
        return true
    }

    fun loadSleepDataDateMarker(): String? {
        return prefs?.getString(KEY_SLEEP_DATA_DATE_MARKER, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun loadSleepRefreshPending(): Boolean {
        return prefs?.getBoolean(KEY_SLEEP_REFRESH_PENDING, false) ?: false
    }

    fun consumeSleepRefreshPending() {
        prefs?.edit()?.putBoolean(KEY_SLEEP_REFRESH_PENDING, false)?.apply()
    }

    fun saveReserveDailyTssBase(value: Float) {
        val safe = if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(0f, 9999f)
        prefs?.edit()?.putFloat(KEY_RESERVE_DAILY_TSS_BASE, safe)?.apply()
    }

    fun loadReserveDailyTssBase(): Float {
        val v = prefs?.getFloat(KEY_RESERVE_DAILY_TSS_BASE, 0f) ?: 0f
        return if (v.isNaN() || v.isInfinite()) 0f else v.coerceIn(0f, 9999f)
    }

    private const val KEY_RESERVE_BASE_DATE = "reserve_base_date"

    fun saveReserveDailyTssBaseDate(date: String) {
        prefs?.edit()?.putString(KEY_RESERVE_BASE_DATE, date)?.apply()
    }

    fun loadReserveDailyTssBaseDate(): String {
        return prefs?.getString(KEY_RESERVE_BASE_DATE, "") ?: ""
    }

    // RSRV 2026-07-07: przepiecie zasilania na XSS (ModelQ). TSS wyzej zostaje WYLACZNIE dla
    // wlasnego pola statystyk "TSS" -- nie jest juz wejsciem do rideReservePercent.
    fun saveReserveDailyXssBase(value: Float) {
        val safe = if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(0f, 9999f)
        prefs?.edit()?.putFloat(KEY_RESERVE_DAILY_XSS_BASE, safe)?.apply()
    }

    fun loadReserveDailyXssBase(): Float {
        val v = prefs?.getFloat(KEY_RESERVE_DAILY_XSS_BASE, 0f) ?: 0f
        return if (v.isNaN() || v.isInfinite()) 0f else v.coerceIn(0f, 9999f)
    }

    private const val KEY_RESERVE_BASE_XSS_DATE = "reserve_base_xss_date"

    fun saveReserveDailyXssBaseDate(date: String) {
        prefs?.edit()?.putString(KEY_RESERVE_BASE_XSS_DATE, date)?.apply()
    }

    fun loadReserveDailyXssBaseDate(): String {
        return prefs?.getString(KEY_RESERVE_BASE_XSS_DATE, "") ?: ""
    }

    fun saveCarbPacketSize(grams: Int) {
        prefs?.edit()?.putInt("carb_packet", grams.coerceIn(5, 100))?.apply()
    }

    fun loadCarbPacketSize(): Int {
        return prefs?.getInt("carb_packet", 20) ?: 20
    }

    fun saveCarbIntakeTotal(grams: Int) {
        prefs?.edit()?.putInt("carb_intake_total", grams.coerceAtLeast(0))?.commit()
    }

    fun loadCarbIntakeTotal(): Int {
        return prefs?.getInt("carb_intake_total", 0) ?: 0
    }

    fun addCarbIntake(grams: Int): Int {
        val next = (loadCarbIntakeTotal() + grams).coerceAtLeast(0)
        saveCarbIntakeTotal(next)
        return next
    }

    fun resetCarbIntakeTotal() {
        saveCarbIntakeTotal(0)
    }

    fun resetCarbSessionState() {
        prefs?.edit()?.apply {
            putInt("carb_intake_total", 0)
            putFloat("carb_needed_total_g", 0f)
            putLong("carb_last_elapsed_sec", 0L)
            apply()
        }
    }

    fun markCarbTapNow() {
        prefs?.edit()?.putLong("carb_last_tap_ms", System.currentTimeMillis())?.commit()
    }

    fun loadCarbLastTapMs(): Long {
        return prefs?.getLong("carb_last_tap_ms", 0L) ?: 0L
    }

    fun saveCarbNeededTotal(grams: Double) {
        prefs?.edit()?.putFloat("carb_needed_total_g", grams.toFloat().coerceAtLeast(0f))?.apply()
    }

    fun loadCarbNeededTotal(): Double {
        return (prefs?.getFloat("carb_needed_total_g", 0f) ?: 0f).toDouble()
    }

    fun saveCarbLastElapsedSec(sec: Long) {
        prefs?.edit()?.putLong("carb_last_elapsed_sec", sec.coerceAtLeast(0L))?.apply()
    }

    fun loadCarbLastElapsedSec(): Long {
        return prefs?.getLong("carb_last_elapsed_sec", 0L) ?: 0L
    }

    fun saveCarbLastClickId(id: Long) {
        prefs?.edit()?.putLong("carb_last_click_id", id)?.apply()
    }

    fun loadCarbLastClickId(): Long {
        return prefs?.getLong("carb_last_click_id", 0L) ?: 0L
    }

    fun undoCarbIntake(packetGrams: Int): Int {
        val current = loadCarbIntakeTotal()
        val next = (current - packetGrams).coerceAtLeast(0)
        saveCarbIntakeTotal(next)
        return next
    }

    fun saveGateLastRequestMs(ts: Long) {
        prefs?.edit()?.putLong("gate_last_request_ms", ts.coerceAtLeast(0L))?.apply()
    }

    fun loadGateLastRequestMs(): Long {
        return prefs?.getLong("gate_last_request_ms", 0L) ?: 0L
    }

    fun saveGateUiState(state: String) {
        prefs?.edit()
            ?.putString("gate_ui_state", state)
            ?.putLong("gate_ui_state_ts", System.currentTimeMillis())
            ?.apply()
    }

    fun loadGateUiState(): String {
        val raw = prefs?.getString("gate_ui_state", "GATE") ?: "GATE"
        val ts = prefs?.getLong("gate_ui_state_ts", 0L) ?: 0L
        return resolveGateUiState(raw, ts, System.currentTimeMillis())
    }

    private val TEMP_STATES = setOf("FURTKA...", "FURTKA OK", "FURTKA FAIL", "FURTKA WAIT")
    private const val GATE_STATE_EXPIRY_MS = 5_000L

    fun resolveGateUiState(rawState: String?, tsMs: Long, nowMs: Long): String {
        if (rawState == null || rawState.isBlank() || rawState == "GATE") return "GATE"
        if (rawState !in TEMP_STATES) return "GATE"
        if (tsMs <= 0L || tsMs > nowMs) return "GATE"
        if (nowMs - tsMs > GATE_STATE_EXPIRY_MS) return "GATE"
        return rawState
    }

    private const val KEY_LOCATION_LAT = "location_lat"
    private const val KEY_LOCATION_LON = "location_lon"

    fun saveLocation(lat: Double, lon: Double) {
        prefs?.edit()?.apply {
            putDouble(KEY_LOCATION_LAT, lat)
            putDouble(KEY_LOCATION_LON, lon)
            apply()
        }
    }

    fun loadLocationLat(): Double? {
        val p = prefs ?: return null
        val v = p.getDouble(KEY_LOCATION_LAT, Double.NaN)
        return if (v.isNaN()) null else v
    }

    fun loadLocationLon(): Double? {
        val p = prefs ?: return null
        val v = p.getDouble(KEY_LOCATION_LON, Double.NaN)
        return if (v.isNaN()) null else v
    }

    private const val KEY_ELAPSED_SNAPSHOT_SEC = "elapsed_snapshot_sec"
    private const val KEY_DISTANCE_SNAPSHOT_M = "distance_snapshot_m"
    private const val KEY_ELAPSED_SNAPSHOT_TS = "elapsed_snapshot_ts"

    fun saveElapsedSnapshot(elapsedSec: Long, distanceM: Double) {
        prefs?.edit()?.apply {
            putLong(KEY_ELAPSED_SNAPSHOT_SEC, elapsedSec)
            putDouble(KEY_DISTANCE_SNAPSHOT_M, distanceM)
            putLong(KEY_ELAPSED_SNAPSHOT_TS, if (elapsedSec > 0L) System.currentTimeMillis() else 0L)
            apply()
        }
    }

    fun elapsedSnapshotAgeMs(): Long {
        val ts = prefs?.getLong(KEY_ELAPSED_SNAPSHOT_TS, 0L) ?: 0L
        return if (ts <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - ts
    }

    fun loadElapsedSnapshot(): Pair<Long, Double> {
        val p = prefs ?: return 0L to 0.0
        return p.getLong(KEY_ELAPSED_SNAPSHOT_SEC, 0L) to p.getDouble(KEY_DISTANCE_SNAPSHOT_M, 0.0)
    }

    private const val KEY_STATSCALC_SNAPSHOT = "statscalc_snapshot"

    fun saveStatsCalcSnapshot(data: String) {
        prefs?.edit()?.putString(KEY_STATSCALC_SNAPSHOT, data)?.apply()
    }

    fun loadStatsCalcSnapshot(): String? {
        return prefs?.getString(KEY_STATSCALC_SNAPSHOT, null)
    }

    // --- Cassette override ---
    // Pozwala wyswietlac biegi z fizycznej kasety (np. custom 10-52) zamiast
    // tej wpisanej w AXS, bez edycji konfiguracji AXS pole-po-polu.
    fun saveCassetteOverrideEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean("cassette_override", enabled)?.apply()
    }

    fun loadCassetteOverrideEnabled(): Boolean =
        prefs?.getBoolean("cassette_override", false) ?: false

    fun saveCassetteCogsRaw(raw: String) {
        prefs?.edit()?.putString("cassette_cogs", raw)?.apply()
    }

    fun loadCassetteCogsRaw(): String =
        prefs?.getString("cassette_cogs", "") ?: ""

    fun loadCassetteCogs(): IntArray = parseCogs(loadCassetteCogsRaw())

    /**
     * Parsuje liste koronek "10,12,...,52" -> IntArray.
     * Tolerancyjny: spacje, srednik, ukosnik, biale znaki jako separatory;
     * ignoruje puste i niedodatnie tokeny. Kolejnosc zachowana (jak wpisano).
     */
    fun parseCogs(raw: String): IntArray =
        raw.split(',', ';', ' ', '/', '\t', '\n')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .toIntArray()
}
