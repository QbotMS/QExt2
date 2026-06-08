package com.qext2.primary.setup

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.CheckBox
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
                minValue = 5
                maxValue = 100
                value = AthleteDataStore.loadCarbPacketSize().coerceIn(5, 100)
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

        findViewById<TextView>(R.id.btn_reset_carb)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset CARB")
                .setMessage("Wyzerowac sume przyjetych wegli (${AthleteDataStore.loadCarbIntakeTotal()}g)?")
                .setPositiveButton("TAK") { _, _ ->
                    AthleteDataStore.resetCarbIntakeTotal()
                    setStatus("CARB: zresetowane do 0g")
                }
                .setNegativeButton("NIE", null)
                .show()
        }

        findViewById<TextView>(R.id.btn_report_bug)?.setOnClickListener {
            val logs = com.qext2.primary.LogCollector.collect()
            val tv = TextView(this@SetupActivity).apply {
                setText(logs)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.WHITE)
                setPadding(16, 16, 16, 16)
                setTextIsSelectable(true)
            }
            val scrollView = android.widget.ScrollView(this).apply {
                addView(tv)
            }
            AlertDialog.Builder(this)
                .setTitle("Logi QExt2")
                .setView(scrollView)
                .setPositiveButton("OK", null)
                .show()
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

        val tfColor = when {
            data.todayFactor >= 0.90f -> Color.parseColor("#4ADE80")
            data.todayFactor >= 0.80f -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#FF5252")
        }
        findViewById<TextView>(R.id.tv_today_factor)?.apply {
            text = data.todayFactorDisplay
            setTextColor(tfColor)
        }

        findViewById<TextView>(R.id.tv_hrv)?.apply {
            text = if (data.hrvToday > 0 && data.hrvBaseline30d > 0f)
                "${data.hrvToday} / ${data.hrvBaseline30d.toInt()} (±${data.hrvDeviation30d.toInt()})"
            else "—"
            val color = when {
                data.hrvDeviation30d >= -3f -> Color.parseColor("#4ADE80")
                data.hrvDeviation30d >= -8f -> Color.parseColor("#F59E0B")
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
                data.sleepDev >= -1.5f -> Color.parseColor("#F59E0B")
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
            setTextColor(if (pct <= 0) Color.parseColor("#4ADE80") else Color.parseColor("#F59E0B"))
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

        findViewById<TextView>(R.id.tv_pi_status)?.apply {
            text = if (data.profileComplete) "GBOT profile OK" else data.warningReasons.replace("|", ", ")
            setTextColor(if (data.profileComplete) Color.parseColor("#4ADE80") else Color.parseColor("#F59E0B"))
        }

        findViewById<TextView>(R.id.tv_status)?.text = ""
    }

    private fun bindDeadline() {
        val (hour, min) = AthleteDataStore.loadDeadline()
        findViewById<TextView>(R.id.tv_deadline)?.text = "%02d:%02d".format(hour, min)
    }

    private fun bindCarbPacket() {
        val grams = AthleteDataStore.loadCarbPacketSize()
        findViewById<TextView>(R.id.tv_carb_packet)?.text = "${grams} g"
    }

    private fun bindCheckboxes() {
        val cbBaro = findViewById<CheckBox>(R.id.cb_baro)
        val cbHrZone = findViewById<CheckBox>(R.id.cb_hr_zone)
        val cbCapTwilight = findViewById<CheckBox>(R.id.cb_cap_twilight)
        cbBaro?.isChecked = AthleteDataStore.loadBaroSensitive()
        cbHrZone?.isChecked = AthleteDataStore.loadHrZoneMode()
        cbCapTwilight?.isChecked = AthleteDataStore.loadCapTwilight()
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
        showSunsetData()
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
}
