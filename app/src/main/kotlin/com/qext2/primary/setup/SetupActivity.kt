package com.qext2.primary.setup

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.qext2.primary.BuildConfig
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.data.AthleteDataStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * SETUP v3 (projekt zaakceptowany 2026-10-09, kanwa "QExt2 SETUP – nowy projekt").
 * Jeden ekran, zagladany rzadko: stan danych, forma dnia z QBota, koniec jazdy (automatycznie zmrok,
 * reczna godzina tylko na dzis), kaseta. Wszystko inne ma stale wartosci (AthleteDataStore).
 */
class SetupActivity : Activity() {

    private val bg = Color.parseColor("#0B1018")
    private val card = Color.parseColor("#151D29")
    private val btn = Color.parseColor("#243145")
    private val txt = Color.parseColor("#EEF2F6")
    private val sub = Color.parseColor("#A9B4C2")
    private val green = Color.parseColor("#4ADE80")
    private val amber = Color.parseColor("#FBBF24")
    private val orange = Color.parseColor("#F59E0B")
    private val red = Color.parseColor("#FF8A8A")
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private lateinit var root: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    /** Rozmiary z makiety (Karoo 3: 480 x 800 px, density 1.875 -- docs/FIELD_LOOK_PLAN.md) przeliczane na
     *  rzeczywista szerokosc ekranu. NIE dp: 1 dp = 1.875 px na Karoo 3, wiec dp z makiety dawaly 2x za duzo. */
    private val scale by lazy { resources.displayMetrics.widthPixels / 480f }
    private fun dp(v: Int) = (v * scale).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AthleteDataStore.init(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(18)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
        render()
    }

    override fun onResume() { super.onResume(); render() }

    override fun onPause() {
        super.onPause()
        QExt2PrimaryExtension.instance?.pushSettings()   // E7.1: kopia ustawien na serwer
    }

    private fun render() {
        root.removeAllViews()
        root.addView(label("QEXT2", 30, txt, bold = true).apply { setPadding(dp(4), 0, 0, dp(10)) })
        root.addView(statusCard())
        root.addView(formCard())
        root.addView(deadlineCard())
        root.addView(cassetteCard())
        root.addView(label("Wersja ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ustawienia zapisywane na serwerze", 13, sub).apply {
            setPadding(dp(4), dp(14), 0, 0)
        })
    }

    // ---------- bloki ----------
    private fun dataFresh(): Boolean {
        val ts = AthleteDataStore.load().fetchTimestamp
        return ts > 0L && Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
    }

    private fun statusCard(): View {
        val a = AthleteDataStore.load()
        val fresh = dataFresh()
        val when_ = if (a.fetchTimestamp > 0L) Instant.ofEpochMilli(a.fetchTimestamp).atZone(ZoneId.systemDefault()) else null
        val bike = when (QExt2PrimaryExtension.instance?.aggregator?.bikeKey()) {
            "10625" -> "Grizl"; "27856" -> "Grail"; "none" -> "Monster"; else -> "rozpozna się po starcie jazdy"
        }
        val c = box(if (fresh) Color.parseColor("#0F2A1C") else Color.parseColor("#3A1416"))
        if (fresh) {
            c.addView(label("Wszystko działa", 26, green, bold = true))
            c.addView(label("Dane z QBota: dziś ${when_?.format(hm)}\nRower: $bike", 15, Color.parseColor("#C7F0D8")))
        } else {
            val day = when_?.toLocalDate()
            val ago = when { when_ == null -> "brak danych"; day == LocalDate.now().minusDays(1) -> "z wczoraj ${when_.format(hm)}"; else -> "z ${day}" }
            c.addView(label("Dane z QBota $ago", 26, red, bold = true))
            c.addView(label("QExt2 liczy na ostatnim profilu, forma dnia = 1,00. Dane pobiorą się same, gdy będzie sieć.", 15, Color.parseColor("#FECACA")))
            c.addView(button("Pobierz teraz", Color.parseColor("#DC2626")) {
                QExt2PrimaryExtension.instance?.refetchAthleteData()
                ui.postDelayed({ render() }, 4000)
            })
        }
        return c
    }

    private fun formCard(): View {
        val c = box(card)
        c.addView(label("Forma dnia z QBota", 15, sub))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        if (dataFresh()) {
            val f = AthleteDataStore.load().todayFactor
            val (desc, col) = when {
                f < 0.95f -> "słabiej niż zwykle" to orange
                f < 0.995f -> "trochę słabiej niż zwykle" to amber
                f <= 1.02f -> "normalnie" to txt
                else -> "lepiej niż zwykle" to green
            }
            row.addView(label("%.2f".format(f).replace('.', ','), 56, col, bold = true))
            row.addView(label("  $desc", 17, txt).apply { setPadding(0, 0, 0, dp(8)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            c.addView(row)
            c.addView(label(when {
                f < 0.995f -> "Rezerwa (RSRV) spada dziś szybciej niż zwykle."
                f > 1.005f -> "Rezerwa (RSRV) spada dziś wolniej niż zwykle."
                else -> "Rezerwa (RSRV) liczona normalnie."
            }, 14, sub))
        } else {
            row.addView(label("1,00", 56, sub, bold = true))
            row.addView(label("  brak dzisiejszej, liczę neutralnie", 15, orange).apply { setPadding(0, 0, 0, dp(8)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            c.addView(row)
        }
        return c
    }

    private fun deadlineCard(): View {
        val c = box(card)
        val agg = QExt2PrimaryExtension.instance?.aggregator
        val today = AthleteDataStore.loadDeadlineToday()
        val effMs = agg?.getDeadlineMs()?.takeIf { it > 0L }
        val eff = effMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(hm) }
        if (today == null) {
            c.addView(label("Koniec jazdy", 15, sub))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            row.addView(label(eff ?: "zmrok", 56, txt, bold = true))
            row.addView(label("  o zmroku, automatycznie", 17, orange).apply { setPadding(0, 0, 0, dp(8)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            c.addView(row)
            c.addView(button("Dziś muszę skończyć wcześniej", btn) {
                val start = effMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                val h = start?.hour ?: 17; val m = (start?.minute ?: 0) / 15 * 15
                AthleteDataStore.saveDeadlineToday(h, m); applyDeadline()
            })
        } else {
            c.addView(label("Koniec jazdy – tylko dziś", 15, sub))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(squareButton("−15") { shiftToday(-15) })
            row.addView(label("%02d:%02d".format(today.first, today.second), 72, txt, bold = true).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(squareButton("+15") { shiftToday(15) })
            c.addView(row)
            c.addView(label("Jutro wraca automatycznie zmrok. Później niż zmrok się nie da.", 14, sub))
            c.addView(button("Wróć do zmroku", btn) { AthleteDataStore.clearDeadlineToday(); applyDeadline() })
        }
        return c
    }

    private fun shiftToday(min: Int) {
        val (h, m) = AthleteDataStore.loadDeadlineToday() ?: return
        val t = (h * 60 + m + min).coerceIn(6 * 60, 23 * 60 + 45)
        AthleteDataStore.saveDeadlineToday(t / 60, t % 60); applyDeadline()
    }

    private fun applyDeadline() {
        QExt2PrimaryExtension.instance?.refreshDeadlineConfig()
        render()
    }

    private fun cassetteCard(): View {
        val c = box(card)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(label("Kaseta", 15, sub))
        val custom = AthleteDataStore.loadCassetteOverrideEnabled()
        col.addView(label(if (custom) AthleteDataStore.loadCassetteCogsRaw() else "z przerzutki AXS", 19, txt, bold = true))
        col.addView(label(if (custom) "wymuszona (Grizl, Grail); Monster bez zmian" else "Monster: własna estymacja 11-50", 13, sub))
        row.addView(col)
        row.addView(Button(this).apply {
            text = "Zmień"; setTextColor(txt); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * scale); isAllCaps = false; background = round(btn, 12)
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(56))
            setOnClickListener { cassetteDialog() }
        })
        c.addView(row)
        return c
    }

    private fun cassetteDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "np. 10-11-13-15-17-19-21-24-28-32-37-44-52"
            setText(AthleteDataStore.loadCassetteCogsRaw())
        }
        AlertDialog.Builder(this)
            .setTitle("Zębatki kasety")
            .setView(input)
            .setPositiveButton("Zapisz") { _, _ ->
                val raw = input.text.toString().trim()
                AthleteDataStore.saveCassetteCogsRaw(raw)
                AthleteDataStore.saveCassetteOverrideEnabled(raw.isNotEmpty())
                QExt2PrimaryExtension.instance?.refreshCassetteOverride(); render()
            }
            .setNeutralButton("Z przerzutki AXS") { _, _ ->
                AthleteDataStore.saveCassetteOverrideEnabled(false)
                QExt2PrimaryExtension.instance?.refreshCassetteOverride(); render()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    // ---------- pomocnicze widoki ----------
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun box(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(color, 14)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
    }

    private fun label(t: String, sp: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * scale); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 19 * scale); setTextColor(Color.WHITE); isAllCaps = false; background = round(color, 12)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun squareButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24 * scale); setTextColor(txt); isAllCaps = false; background = round(btn, 12)
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        setOnClickListener { onClick() }
    }
}
