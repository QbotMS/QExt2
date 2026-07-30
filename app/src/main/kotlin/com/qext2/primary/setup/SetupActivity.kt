package com.qext2.primary.setup

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import com.qext2.primary.BuildConfig
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.data.AthleteDataStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        AthleteDataStore.init(this)
        findViewById<TextView>(R.id.tv_version)?.text = "v${BuildConfig.VERSION_NAME}"
        showStoredData()
        bindDeadline()
        bindCarbPacket()
        bindCheckboxes()
        setupTabs()

        findViewById<TextView>(R.id.tv_deadline)?.setOnClickListener {
            android.util.Log.e("QExt2Setup", "DEADLINE CLICKED!")
            val (h, m) = AthleteDataStore.loadDeadline()
            TimePickerDialog(this, { _, hour, minute ->
                AthleteDataStore.saveDeadline(hour, minute)
                QExt2PrimaryExtension.instance?.refreshDeadlineConfig()
                bindDeadline()
                showSunsetData()
                setStatus("Deadline: %02d:%02d".format(hour, minute))
            }, h, m, true).show()
        }


        findViewById<TextView>(R.id.tv_carb_packet)?.setOnClickListener {
            val picker = NumberPicker(this).apply {
                minValue = 15
                maxValue = 60
                value = AthleteDataStore.loadCarbPacketSize().coerceIn(15, 60)
                wrapSelectorWheel = false
            }
            val container = LinearLayout(this).apply {
                setPadding(32, 24, 32, 8)
                addView(picker)
            }
            AlertDialog.Builder(this)
                .setTitle("Carb porcja (g)")
                .setView(container)
                .setPositiveButton("OK") { _, _ ->
                    val grams = picker.value
                    AthleteDataStore.saveCarbPacketSize(grams)
                    bindCarbPacket()
                    setStatus("Carb porcja: ${grams}g")
                }
                .setNegativeButton("Anuluj", null)
                .show()
        }

        val btn = findViewById<TextView>(R.id.btn_refetch)
        btn?.setOnClickListener {
            android.util.Log.i("QExt2Setup", "QEXT_READINESS_FETCH_START")
            setStatus("Odswiezanie...")
            btn.alpha = 0.4f
            btn.isEnabled = false
            QExt2PrimaryExtension.instance?.refetchAthleteData()
            btn.postDelayed({
                showStoredData()
                showSunsetData()
                setStatus("Gotowe!")
                btn.alpha = 1.0f
                btn.isEnabled = true
            }, 5000L)
        }

    }

    override fun onResume() {
        super.onResume()
        showStoredData()
    }

    private fun showStoredData() {
        val raw = AthleteDataStore.load()
        val baroSens = AthleteDataStore.loadBaroSensitive()
        val data = raw.applyBaroAdjustment(baroSens)

        findViewById<TextView>(R.id.tv_ftp)?.text = if (data.ftp > 0) "${data.ftp} W" else "—"
        findViewById<TextView>(R.id.tv_wmax)?.text = "${data.wPrimeJoules.toInt()} J"
        findViewById<TextView>(R.id.tv_pp)?.text = if (data.ltpWatts > 0) "${data.ltpWatts} W" else "—"

        findViewById<TextView>(R.id.tv_hrv)?.apply {
            text = if (data.hrvToday > 0 && data.hrvBaseline30d > 0f)
                "${data.hrvToday} / ${data.hrvBaseline30d.toInt()} (±${data.hrvDeviation30d.toInt()})"
            else "—"
            val color = when {
                data.hrvDeviation30d >= -3f -> Color.parseColor("#4ADE80")
                data.hrvDeviation30d >= -8f -> Color.parseColor("#FACC15")
                else -> Color.parseColor("#FF5252")
            }
            setTextColor(color)
        }

        findViewById<TextView>(R.id.tv_sleep)?.apply {
            text = if (data.sleepTodayH > 0f && data.sleepBaseline30d > 0f)
                "%.1fh / %.1fh (±%.1fh)".format(data.sleepTodayH, data.sleepBaseline30d, data.sleepDev)
            else "—"
            val color = when {
                data.sleepDev >= -0.5f -> Color.parseColor("#4ADE80")
                data.sleepDev >= -1.5f -> Color.parseColor("#FACC15")
                else -> Color.parseColor("#FF5252")
            }
            setTextColor(color)
        }

        findViewById<TextView>(R.id.tv_pressure)?.text = if (data.pressureHpa > 0f)
            "%.0f hPa / %+.0f hPa/24h".format(data.pressureHpa, data.pressureChange24h)
        else "—"

        findViewById<TextView>(R.id.tv_baro_info)?.apply {
            val pct = data.baroAdjustPercent
            text = if (pct <= 0) "brak korekty" else "korekta −${pct}%"
            setTextColor(if (pct <= 0) Color.parseColor("#4ADE80") else Color.parseColor("#FACC15"))
        }

        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val lastRefresh = AthleteDataStore.loadLastRefresh()
        val dateStr = if (data.fetchTimestamp > 0) {
            val ageH = (System.currentTimeMillis() - data.fetchTimestamp) / 3_600_000L
            val staleTag = if (ageH > 12) " ⚠️ dane stare (${ageH}h)" else ""
            "Dane z API: ${sdf.format(Date(data.fetchTimestamp))}$staleTag"
        } else {
            "Brak danych — poczekaj na synchronizację"
        }
        val refreshStr = if (lastRefresh > 0) {
            " / odswiezone: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastRefresh))}"
        } else ""
        findViewById<TextView>(R.id.tv_fetch_date)?.text = dateStr + refreshStr

        findViewById<TextView>(R.id.tv_today_factor)?.text = data.todayFactorDisplay

        findViewById<TextView>(R.id.tv_pi_status)?.apply {
            text = if (data.profileComplete) "QBOT profile OK" else data.warningReasons.replace("|", ", ")
            setTextColor(if (data.profileComplete) Color.parseColor("#4ADE80") else Color.parseColor("#FACC15"))
        }

        findViewById<TextView>(R.id.tv_status)?.text = ""
    }

    private fun bindDeadline() {
        val (hour, min) = AthleteDataStore.loadDeadline()
        findViewById<TextView>(R.id.tv_deadline)?.text = "%02d:%02d".format(hour, min)
    }


    private fun bindCheckboxes() {
        val cbBaro = findViewById<CheckBox>(R.id.cb_baro)
        val cbHrZone = findViewById<CheckBox>(R.id.cb_hr_zone)
        val cbCapTwilight = findViewById<CheckBox>(R.id.cb_cap_twilight)
        cbBaro?.isChecked = AthleteDataStore.loadBaroSensitive()
        cbHrZone?.isChecked = AthleteDataStore.loadHrZoneMode()
        cbCapTwilight?.isChecked = AthleteDataStore.loadCapTwilight()
        // TodayFactor: przelacznik SESYJNY (bez preferencji) -- reset na ON po restarcie Karoo.
        val cbTf = findViewById<CheckBox>(R.id.cb_tf)
        cbTf?.isChecked = com.qext2.primary.data.TodayFactorSession.enabled
        cbTf?.setOnCheckedChangeListener { _, checked ->
            com.qext2.primary.data.TodayFactorSession.enabled = checked
        }
        cbBaro?.setOnCheckedChangeListener { _, checked ->
            AthleteDataStore.saveBaroSensitive(checked)
            showStoredData()
            QExt2PrimaryExtension.instance?.refreshBaroSensitive(checked)
        }
        cbHrZone?.setOnCheckedChangeListener { _, checked ->
            AthleteDataStore.saveHrZoneMode(checked)
        }
        cbCapTwilight?.setOnCheckedChangeListener { _, checked ->
            AthleteDataStore.saveCapTwilight(checked)
            showSunsetData()
            QExt2PrimaryExtension.instance?.refreshCapTwilight(checked)
        }
        bindRidingMode()
        bindCassetteOverride()
        showSunsetData()
    }

    private fun bindCassetteOverride() {
        val cb = findViewById<CheckBox>(R.id.cb_cassette_override)
        val tvCogs = findViewById<TextView>(R.id.tv_cassette_cogs)

        fun renderCogs() {
            val raw = AthleteDataStore.loadCassetteCogsRaw()
            val cogs = AthleteDataStore.parseCogs(raw)
            tvCogs?.text = if (cogs.isEmpty()) "— (dotknij, wpisz np. 10,12,14,...,52)"
            else cogs.joinToString(",") + "  (${cogs.size} biegów)"
        }

        cb?.isChecked = AthleteDataStore.loadCassetteOverrideEnabled()
        renderCogs()

        cb?.setOnCheckedChangeListener { _, checked ->
            AthleteDataStore.saveCassetteOverrideEnabled(checked)
            QExt2PrimaryExtension.instance?.refreshCassetteOverride()
            setStatus(if (checked) "Override kasety: ON" else "Override kasety: OFF")
        }

        val cbEdge = findViewById<CheckBox>(R.id.cb_gear_edge_beep)
        cbEdge?.isChecked = AthleteDataStore.loadGearEdgeBeepEnabled()
        cbEdge?.setOnCheckedChangeListener { _, checked ->
            AthleteDataStore.saveGearEdgeBeepEnabled(checked)
            setStatus(if (checked) "Dzwiek skrajnych koronek: ON" else "Dzwiek skrajnych koronek: OFF")
        }

        tvCogs?.setOnClickListener {
            val input = EditText(this).apply {
                setText(AthleteDataStore.loadCassetteCogsRaw())
                hint = "10,12,14,16,18,21,24,28,32,36,42,52"
                setSingleLine(true)
            }
            val container = LinearLayout(this).apply {
                setPadding(32, 24, 32, 8)
                addView(input)
            }
            AlertDialog.Builder(this)
                .setTitle("Kaseta custom (od najmniejszej koronki)")
                .setMessage("Wpisz koronki po przecinku, od najmniejszej (10T) do największej. Bieg 1 (AXS) = największa koronka.")
                .setView(container)
                .setPositiveButton("Zapisz") { _, _ ->
                    val raw = input.text.toString()
                    val cogs = AthleteDataStore.parseCogs(raw)
                    AthleteDataStore.saveCassetteCogsRaw(cogs.joinToString(","))
                    QExt2PrimaryExtension.instance?.refreshCassetteOverride()
                    renderCogs()
                    setStatus("Kaseta: ${cogs.size} koronek")
                }
                .setNegativeButton("Anuluj", null)
                .show()
        }
    }

    private fun bindRidingMode() {
        val btnDef = findViewById<android.widget.TextView>(R.id.btn_mode_defensive)
        val btnNorm = findViewById<android.widget.TextView>(R.id.btn_mode_normal)
        val btnOff = findViewById<android.widget.TextView>(R.id.btn_mode_offensive)
        val btnAuto = findViewById<android.widget.TextView>(R.id.btn_mode_auto)
        val buttons = listOf(btnDef, btnNorm, btnOff, btnAuto)

        fun highlight(selected: Int) {
            buttons.forEachIndexed { idx, btn ->
                btn?.setBackgroundColor(
                    if (idx == selected) 0xFF1D4ED8.toInt() else 0xFF1E2A3A.toInt()
                )
                btn?.setTextColor(
                    if (idx == selected) 0xFFFFFFFF.toInt() else 0xFF9CA3AF.toInt()
                )
            }
        }

        highlight(AthleteDataStore.loadRidingMode())

        buttons.forEachIndexed { idx, btn ->
            btn?.setOnClickListener {
                AthleteDataStore.saveRidingMode(idx)
                QExt2PrimaryExtension.instance?.refreshModeFactor()
                highlight(idx)
            }
        }
    }

    private fun showSunsetData() {
        val civilDuskMs = QExt2PrimaryExtension.instance?.aggregator?.getCivilDuskMs() ?: 0L
        val apiSunsetMs = AthleteDataStore.load().sunsetTimestampMs
        val twilightMs = if (civilDuskMs > 0L) civilDuskMs else apiSunsetMs
        val capTwilight = AthleteDataStore.loadCapTwilight()
        val (hour, min) = AthleteDataStore.loadDeadline()

        val sunsetStr = if (twilightMs > 0L) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            sdf.format(Date(twilightMs))
        } else "—"
        findViewById<TextView>(R.id.tv_sunset)?.text = sunsetStr

        val activeDeadline = if (capTwilight && twilightMs > 0L) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val twilightTime = sdf.format(Date(twilightMs))
            "Aktywny deadline: min(%02d:%02d, $twilightTime)".format(hour, min)
        } else {
            "Aktywny deadline: %02d:%02d".format(hour, min)
        }
        findViewById<TextView>(R.id.tv_active_deadline)?.text = activeDeadline
    }

    private fun setStatus(msg: String) {
        findViewById<TextView>(R.id.tv_status)?.text = msg
    }

    private fun setupTabs() {
        val tabs = listOf(
            Pair(R.id.tab_dane, R.id.ll_tab_dane),
            Pair(R.id.tab_jazda, R.id.ll_tab_jazda),
            Pair(R.id.tab_paliwo, R.id.ll_tab_paliwo),
            Pair(R.id.tab_naw, R.id.ll_tab_naw)
        )
        fun select(idx: Int) {
            tabs.forEachIndexed { i, pair ->
                findViewById<TextView>(pair.first)?.apply {
                    setTextColor(if (i == idx) 0xFFFFFFFF.toInt() else 0xFF9CA3AF.toInt())
                    setBackgroundColor(if (i == idx) 0xFF131C2E.toInt() else 0)
                }
                findViewById<LinearLayout>(pair.second)?.visibility =
                    if (i == idx) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        tabs.forEachIndexed { i, pair ->
            findViewById<TextView>(pair.first)?.setOnClickListener { select(i) }
        }
        select(0)
    }

    private fun bindCarbPacket() {
        val grams = AthleteDataStore.loadCarbPacketSize()
        findViewById<TextView>(R.id.tv_carb_packet)?.text = "${grams} g"
    }
}
