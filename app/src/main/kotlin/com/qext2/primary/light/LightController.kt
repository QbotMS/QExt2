package com.qext2.primary.light

import android.content.Context
import android.util.Log
import com.qext2.primary.util.RideFileLog
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.SavedDevices

/**
 * Lampa tylna (16.10.2026): stan i bateria z oficjalnej listy urzadzen Karoo (SavedDevices), zmiana trybu tapnieciem przez
 * nieoficjalna usluge (LightControl). Tryb nie jest odczytywany z lampy -- pokazujemy ostatnio ustawiony przez QExt2.
 */
object LightController {
    private const val TAG = "QEXT_LIGHT"
    private const val LIGHT_TYPE = "TYPE_BIKE_LIGHT_ID"
    private val MODES = listOf("SLOW_FLASH", "FAST_FLASH", "STEADY4", "STEADY5", "OFF")
    private val LABELS = mapOf("SLOW_FLASH" to "MIGANIE", "FAST_FLASH" to "SZYBKIE MIG.", "STEADY4" to "STAŁE MOCNE", "STEADY5" to "STAŁE SŁABE", "OFF" to "WYŁĄCZONA")

    data class Ui(val found: Boolean, val controllable: Boolean, val mode: String?, val battery: String?, val flash: String?)

    @Volatile private var control: LightControl? = null
    @Volatile private var lightId: String? = null
    @Volatile private var battery: BatteryStatus? = null
    @Volatile private var mode: String? = null
    @Volatile private var flash: String? = null
    @Volatile private var flashUntil = 0L
    private var consumerId: String? = null

    fun init(context: Context, karoo: KarooSystemService?) {
        if (control == null) control = LightControl(context.applicationContext).also { it.bind() }
        if (consumerId == null && karoo != null) {
            consumerId = try {
                karoo.addConsumer<SavedDevices>(onEvent = { sd ->
                    val lights = sd.devices.filter { it.enabled && it.supportedDataTypes.contains(LIGHT_TYPE) }
                    val rear = lights.firstOrNull { d -> d.id.split("-").getOrNull(1)?.toIntOrNull() == 35 } ?: lights.firstOrNull()
                    if (rear?.id != lightId) log("LIGHT found=${rear != null} name=${rear?.name}")
                    lightId = rear?.id
                    battery = rear?.details?.lastBattery
                })
            } catch (e: Exception) {
                Log.w(TAG, "SavedDevices fail ${e.message}"); null
            }
        }
    }

    fun ui(): Ui {
        val f = if (System.currentTimeMillis() < flashUntil) flash else null
        val b = when (battery) {
            BatteryStatus.NEW, BatteryStatus.GOOD -> "bat. dobra"
            BatteryStatus.OK -> "bat. OK"
            BatteryStatus.LOW -> "bat. niska"
            BatteryStatus.CRITICAL -> "bat. KRYT."
            else -> null
        }
        return Ui(lightId != null, lightId != null && control?.ready() == true, mode?.let { LABELS[it] }, b, f)
    }

    /** Tapniecie: nastepny tryb. Bez lampy / bez uslugi -> tylko komunikat. */
    fun cycle() {
        val id = lightId
        val c = control
        if (id == null) { show("brak lampy"); log("LIGHT_TAP no_light"); return }
        if (c == null || !c.ready()) { c?.bind(); show("brak sterowania"); log("LIGHT_TAP not_ready"); return }
        val next = MODES[(MODES.indexOf(mode ?: "OFF") + 1) % MODES.size]
        val ok = c.setMode(id, next)
        if (ok) { mode = next; show(null) } else show("błąd zmiany")
        log("LIGHT_SET mode=$next ok=$ok")
    }

    private fun show(m: String?) { flash = m; flashUntil = if (m == null) 0L else System.currentTimeMillis() + 4_000L }

    private fun log(m: String) { Log.i(TAG, m); try { RideFileLog.append(m) } catch (_: Exception) {} }
}
