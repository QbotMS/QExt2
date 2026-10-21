package com.qext2.primary.osmo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.qext2.primary.health.FeatureGuard
import com.qext2.primary.hrv.H10Recorder
import com.qext2.primary.util.RideFileLog
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * 21.10.2026 (0.4.23, sesja osmo-2110), ETAP 1: kamera DJI Osmo Nano -> stan nagrywania na Karoo.
 * Decyzje Michala: tylko PODGLAD stanu (bez sterowania), pozniej kropka REC w KOKPIT 2 miedzy pogoda a wiatrem.
 * Etap 1 = tylko polaczenie i dziennik jazdy (OSMO_*), bez kropki - sprawdzamy, czy Karoo dogada sie z kamera
 * i ile baterii kamery schodzi (OSMO_STAT co minute).
 *
 * Polaczenie trwa od startu do konca jazdy (Karoo w trakcie - takze pauza - trzyma lacze). Laczenie po zapisanym
 * adresie, bez skanowania (w tle skan na Karoo nie dziala - lekcja z pasa H10). Kamery NIE budzimy: gdy spi
 * albo jest poza zasiegiem, proba konczy sie bledem i wraca co 30 s. Pierwsze polaczenie trzeba zatwierdzic
 * na ekranie kamery - najlepiej z SETUP QExt2 (przycisk "Połącz i sprawdź").
 * Bezpiecznik: FeatureGuard.OSMO (petla padania / 3 bledy / zdalnie z QBota). Nic tu nie rzuca wyjatkow na zewnatrz.
 * Protokol: OsmoProtocol (port z Osmosis, MIT).
 */
@SuppressLint("MissingPermission")
object OsmoLink {
    private const val PREFS = "qext2_osmo"
    private const val K_ADDR = "address"
    private const val K_NAME = "name"
    private const val K_DEVID = "rsdk_device_id"
    private const val K_MAC = "rsdk_mac"

    private val SVC: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val FFF4: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
    private val FFF5: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val DJI_MFR = 0x08AA

    private const val APPROVAL_MS = 45_000L
    private const val RETRY_MS = 30_000L
    private const val POLL_MS = 1_500L
    private const val STALE_MS = 2_000L
    private const val SUMMARY_MS = 60_000L
    private const val SCAN_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    private var gatt: BluetoothGatt? = null
    private var seq = 0
    private val cccd = ArrayDeque<BluetoothGattCharacteristic>()
    private val rx = ByteArrayOutputStream()
    private var want = false
    private var rideActive = false
    private var onCheck: ((String) -> Unit)? = null
    private var linkedAt = 0L
    private var gotNumeric = false
    private var warnedNoNumeric = false
    private var unparsed = 0
    private var unhandled = 0
    private var lastLoggedRec: Boolean? = null
    private var lastMode = ""
    private var batAtLink = -1

    @Volatile var connected = false
        private set
    @Volatile var recording = false
        private set
    @Volatile var battery = -1
        private set
    @Volatile var lastStatusMs = 0L
        private set

    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    private fun prefs() = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun address(): String? = app?.let { prefs().getString(K_ADDR, null) }
    fun name(): String? = app?.let { prefs().getString(K_NAME, null) }

    fun forget() {
        main.post { want = false; close(); app?.let { prefs().edit().remove(K_ADDR).remove(K_NAME).apply() }; log("OSMO_FORGET") }
    }

    private val deviceId: Int
        get() {
            val p = prefs()
            return p.getInt(K_DEVID, 0).takeIf { it != 0 } ?: (Random.nextInt() or 1).also { p.edit().putInt(K_DEVID, it).apply() }
        }
    private val ctrlMac: ByteArray
        get() {
            val p = prefs()
            p.getString(K_MAC, null)?.let { h -> return ByteArray(6) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
            return ByteArray(6).also { Random.nextBytes(it); p.edit().putString(K_MAC, it.joinToString("") { b -> "%02x".format(b) }).apply() }
        }

    private fun log(s: String) { try { RideFileLog.append(s) } catch (_: Throwable) {} }

    private fun hex2(v: Int): String = String.format(Locale.US, "%02x", v)

    /** Stan do kropki (etap 2): 0 brak kamery, 1 polaczona - nie nagrywa, 2 nagrywa. */
    fun dot(): Int = when {
        !connected || System.currentTimeMillis() - lastStatusMs > 10_000L -> 0
        recording -> 2
        else -> 1
    }

    // ---------------- jazda ----------------

    fun onRideStart() {
        main.post {
            rideActive = true; want = true
            if (address() == null) log("OSMO_SKIP brak wybranej kamery") else open("start jazdy")
        }
    }

    fun onRideEnd() {
        main.post {
            rideActive = false; want = false
            if (connected) summary("koniec jazdy")
            main.removeCallbacks(retry)
            close()
            log("OSMO_RIDE_END")
        }
    }

    // ---------------- SETUP ----------------

    /** Szuka kamery DJI (nazwa Osmo* albo znacznik producenta DJI bez nazwy), zapisuje adres. */
    fun search(onDone: (String?) -> Unit) {
        val ctx = app
        if (ctx == null) { onDone(null); return }
        val sc = try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner } catch (_: Throwable) { null }
        if (sc == null) { onDone(null); return }
        var done = false
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val nm = try { r.scanRecord?.deviceName ?: r.device.name } catch (_: Throwable) { null }
                val dji = r.scanRecord?.getManufacturerSpecificData(DJI_MFR) != null
                val isOsmo = nm?.startsWith("Osmo", ignoreCase = true) == true
                if (!isOsmo && !(dji && nm == null)) return
                val self = this
                main.post {
                    if (!done) {
                        done = true
                        try { sc.stopScan(self) } catch (_: Throwable) {}
                        val label = nm ?: ("DJI " + r.device.address)
                        prefs().edit().putString(K_ADDR, r.device.address).putString(K_NAME, label).apply()
                        log("OSMO_PAIRED $label ${r.device.address} rssi=${r.rssi}")
                        onDone(label)
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                main.post { if (!done) { done = true; log("OSMO_SCAN_FAIL $errorCode"); onDone(null) } }
            }
        }
        try { sc.startScan(cb) } catch (e: Throwable) { log("OSMO_SCAN_FAIL ${e.javaClass.simpleName}"); onDone(null); return }
        main.postDelayed({ if (!done) { done = true; try { sc.stopScan(cb) } catch (_: Throwable) {}; log("OSMO_SCAN nic"); onDone(null) } }, SCAN_MS)
    }

    /** Test z SETUP: laczy, czeka na pierwszy stan, opisuje wynik. Poza jazda rozlacza po chwili. */
    fun check(onDone: (String) -> Unit) {
        main.post {
            if (address() == null) onDone("Najpierw znajdź kamerę.")
            else if (connected && lastStatusMs > 0) onDone(statusText())
            else { onCheck = onDone; want = true; if (gatt == null) open("test z SETUP") }
        }
    }

    private fun statusText(): String =
        "Połączono: " + (if (recording) "NAGRYWA" else "nie nagrywa") + (if (battery in 0..100) ", bateria $battery%" else "") +
            (if (lastMode.isNotEmpty()) ", tryb $lastMode" else "")

    private fun finishCheck(msg: String) {
        val cb = onCheck ?: return
        onCheck = null
        cb(msg)
        if (!rideActive) main.postDelayed({ if (!rideActive && onCheck == null) { want = false; close(); log("OSMO_CHECK_END") } }, 5_000L)
    }

    // ---------------- polaczenie ----------------

    private fun open(reason: String) {
        val ctx = app ?: return
        if (gatt != null) return
        val off = FeatureGuard.reason(FeatureGuard.OSMO)
        if (off != null) { log("OSMO_OFF powod=$off"); finishCheck("Wyłączone ($off)."); return }
        val addr = address() ?: return
        if (!H10Recorder.hasPermissions(ctx)) { log("OSMO_NO_PERMISSION"); finishCheck("Brak zgody na Bluetooth."); return }
        try {
            val ad = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
            log("OSMO_CONNECT $addr ($reason)")
            seq = 0; gotNumeric = false; warnedNoNumeric = false; unparsed = 0; unhandled = 0; rx.reset()
            gatt = ad.getRemoteDevice(addr).connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            main.removeCallbacks(approvalTimeout)
            main.postDelayed(approvalTimeout, APPROVAL_MS)
        } catch (e: Throwable) { fail("open", e) }
    }

    private fun close() {
        main.removeCallbacks(approvalTimeout); main.removeCallbacks(poll); main.removeCallbacks(summaryTick)
        val g = gatt; gatt = null
        try { g?.disconnect() } catch (_: Throwable) {}
        try { g?.close() } catch (_: Throwable) {}
        connected = false; lastStatusMs = 0L; rx.reset(); cccd.clear()
    }

    private val retry = Runnable { if (want && gatt == null) open("ponowna proba") }

    private fun scheduleRetry() {
        main.removeCallbacks(retry)
        if (want && rideActive) main.postDelayed(retry, RETRY_MS)
    }

    private val approvalTimeout = Runnable {
        if (!connected) {
            log("OSMO_TIMEOUT brak polaczenia/zgody w ${APPROVAL_MS / 1000} s (kamera wylaczona, poza zasiegiem albo niezatwierdzona)")
            close()
            finishCheck("Nie połączono. Włącz kamerę; za pierwszym razem zatwierdź połączenie na jej ekranie.")
            scheduleRetry()
        }
    }

    private fun fail(where: String, e: Throwable) {
        log("OSMO_ERR $where ${e.javaClass.simpleName}: ${e.message}")
        FeatureGuard.report(FeatureGuard.OSMO, false)
        close()
        finishCheck("Błąd: ${e.javaClass.simpleName}")
        scheduleRetry()
    }

    private fun nextSeq(): Int { seq = (seq + 1) and 0xFFFF; return seq }

    @Suppress("DEPRECATION")
    private fun send(cmdSet: Int, cmdId: Int, type: Int, payload: ByteArray, useSeq: Int = nextSeq()) {
        val g = gatt ?: return
        val ch = g.getService(SVC)?.getCharacteristic(FFF5)
        if (ch == null) { log("OSMO_NO_FFF5"); return }
        val v = OsmoProtocol.frame(cmdSet, cmdId, type, payload, useSeq)
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, v, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = v; g.writeCharacteristic(ch)
        }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g === gatt) try {
                    if (newState == BluetoothProfile.STATE_CONNECTED) { log("OSMO_GATT_UP status=$status"); g.requestMtu(517) }
                    else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val was = connected
                        log("OSMO_GATT_DOWN status=$status")
                        if (was) summary("rozlaczenie")
                        close()
                        finishCheck("Kamera rozłączyła się (status $status).")
                        scheduleRetry()
                    }
                } catch (e: Throwable) { fail("state", e) }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (g === gatt) try { log("OSMO_MTU $mtu"); g.discoverServices() } catch (e: Throwable) { fail("mtu", e) } }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { if (g === gatt) try { services(g) } catch (e: Throwable) { fail("services", e) } }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (g === gatt) try { nextCccd(g) } catch (e: Throwable) { fail("cccd", e) } }
        }

        @Deprecated("API <= 32")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value?.copyOf() ?: return
            main.post { if (g === gatt) bytes(v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            main.post { if (g === gatt) bytes(v) }
        }
    }

    private fun services(g: BluetoothGatt) {
        val svc = g.getService(SVC)
        if (svc == null) {
            log("OSMO_NO_FFF0 uslugi=" + g.services.joinToString(",") { it.uuid.toString().substring(4, 8) })
            close(); finishCheck("To nie wygląda na kamerę DJI (brak usługi fff0).")
            return
        }
        cccd.clear()
        for (u in listOf(FFF4, FFF5)) {
            val ch = svc.getCharacteristic(u) ?: continue
            g.setCharacteristicNotification(ch, true); cccd.add(ch)
        }
        nextCccd(g)
    }

    @Suppress("DEPRECATION")
    private fun nextCccd(g: BluetoothGatt) {
        val ch = cccd.poll()
        if (ch == null) { requestConnection(); return }
        val d = ch.getDescriptor(CCCD)
        if (d == null) { nextCccd(g); return }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d)
        }
    }

    private fun requestConnection() {
        log("OSMO_REQUEST prosba o polaczenie (za pierwszym razem zatwierdz na kamerze)")
        send(OsmoProtocol.SET_GENERAL, OsmoProtocol.ID_CONNECTION, OsmoProtocol.CMD_WAIT_RESULT,
            OsmoProtocol.connectionRequest(deviceId, ctrlMac, verifyMode = 0, verifyData = Random.nextInt(0, 10000)))
    }

    /** Sklada fragmenty ramki R-SDK (0xAA) i przekazuje cale ramki. Inne bajty (DUML 0x55) pomija. */
    private fun bytes(v: ByteArray) {
        try {
            if (v.isEmpty()) return
            if ((v[0].toInt() and 0xFF) == 0xAA) rx.reset() else if (rx.size() == 0) return
            rx.write(v)
            val b = rx.toByteArray()
            val len = OsmoProtocol.headerLen(b)
            if (len < 0 || b.size < len) return
            rx.reset()
            val f = OsmoProtocol.parse(b.copyOf(len))
            if (f == null) {
                if (unparsed++ < 10) log("OSMO_BAD_FRAME ${b.size}B ${OsmoProtocol.hex(b, 20)}")
                return
            }
            frame(f)
        } catch (e: Throwable) { fail("bytes", e) }
    }

    private fun frame(f: OsmoProtocol.Frame) {
        if (f.cmdSet == OsmoProtocol.SET_GENERAL && f.cmdId == OsmoProtocol.ID_CONNECTION) { connectionFrame(f); return }
        if (f.cmdSet == OsmoProtocol.SET_CAMERA && f.cmdId == OsmoProtocol.ID_STATUS_PUSH) {
            val s = OsmoProtocol.parseStatus(f.payload)
            if (s == null) { log("OSMO_STATUS_SHORT ${f.payload.size}B"); return }
            gotNumeric = true
            lastStatusMs = System.currentTimeMillis()
            recording = s.recording
            if (s.battery in 0..100) battery = s.battery
            if (batAtLink < 0 && battery >= 0) batAtLink = battery
            if (lastLoggedRec != recording) {
                lastLoggedRec = recording
                log("OSMO_REC " + (if (recording) "NAGRYWA" else "stop") + " status=0x" + hex2(s.status) +
                    " tryb=0x" + hex2(s.mode) + " bat=$battery czas=${s.recordTimeS}s")
            }
            finishCheck(statusText())
            return
        }
        if (f.cmdSet == OsmoProtocol.SET_CAMERA && f.cmdId == OsmoProtocol.ID_STATUS_PUSH_NEW) {
            lastStatusMs = System.currentTimeMillis()
            val m = OsmoProtocol.parseModeText(f.payload)
            if (m == null) { if (unhandled++ < 20) log("OSMO_MODE_RAW ${f.payload.size}B ${OsmoProtocol.hex(f.payload)}") }
            else if (m != lastMode) { lastMode = m; log("OSMO_MODE $m") }
            return
        }
        if (unhandled++ < 20)
            log("OSMO_OTHER " + hex2(f.cmdSet) + "/" + hex2(f.cmdId) + " typ=0x" + hex2(f.cmdType) + " ${f.payload.size}B ${OsmoProtocol.hex(f.payload)}")
    }

    private fun connectionFrame(f: OsmoProtocol.Frame) {
        if (connected) return
        if (f.isResponse) {
            if (f.payload.size > 4 && f.payload[4].toInt() != 0) {
                log("OSMO_REJECT ret=${f.payload[4].toInt()}")
                close(); finishCheck("Kamera odrzuciła połączenie."); scheduleRetry()
            }
            return
        }
        if (f.payload.size < 29) return
        val mode = f.payload[26].toInt() and 0xFF
        val data = (f.payload[27].toInt() and 0xFF) or ((f.payload[28].toInt() and 0xFF) shl 8)
        if (mode != 2) { log("OSMO_VERIFY_MODE $mode"); return }
        if (data != 0) { log("OSMO_REJECT verify=$data"); close(); finishCheck("Kamera odrzuciła połączenie."); scheduleRetry(); return }
        main.removeCallbacks(approvalTimeout)
        send(OsmoProtocol.SET_GENERAL, OsmoProtocol.ID_CONNECTION, OsmoProtocol.ACK_NO_RESPONSE, OsmoProtocol.connectionResponse(deviceId), useSeq = f.seq)
        connected = true
        linkedAt = System.currentTimeMillis()
        batAtLink = -1; lastLoggedRec = null
        FeatureGuard.report(FeatureGuard.OSMO, true)
        log("OSMO_LINKED zgoda kamery, subskrypcja stanu")
        subscribe()
        main.postDelayed(poll, POLL_MS)
        main.postDelayed(summaryTick, SUMMARY_MS)
    }

    private fun subscribe() = send(OsmoProtocol.SET_CAMERA, OsmoProtocol.ID_STATUS_SUB, OsmoProtocol.CMD_NO_RESPONSE, OsmoProtocol.statusSubscription())

    /** Kamera, ktora odpowiada na subskrypcje tylko raz (Action 6 w Osmosis) - ponawiaj, gdy stan ucichl. */
    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (!connected) return
            try {
                if (System.currentTimeMillis() - lastStatusMs > STALE_MS) subscribe()
                if (!gotNumeric && !warnedNoNumeric && System.currentTimeMillis() - linkedAt > 15_000L) {
                    warnedNoNumeric = true
                    log("OSMO_NO_REC_INFO 15 s bez stanu 0x1D/0x02 - kamera nie podaje, czy nagrywa")
                    finishCheck("Połączono, ale kamera nie podaje stanu nagrywania" + (if (lastMode.isNotEmpty()) " (tryb $lastMode)" else "") + ".")
                }
            } catch (e: Throwable) { fail("poll", e); return }
            main.postDelayed(this, POLL_MS)
        }
    }

    private val summaryTick: Runnable = object : Runnable {
        override fun run() {
            if (!connected) return
            summary("co minute")
            main.postDelayed(this, SUMMARY_MS)
        }
    }

    /** OSMO_STAT: do pomiaru baterii kamery (spadek od polaczenia) i jakosci lacza. */
    private fun summary(why: String) {
        val now = System.currentTimeMillis()
        val min = String.format(Locale.US, "%.1f", (now - linkedAt) / 60_000.0)
        val drop = if (batAtLink >= 0 && battery >= 0) batAtLink - battery else -1
        val ago = if (lastStatusMs > 0) (now - lastStatusMs) / 1000 else -1
        log("OSMO_STAT ($why) rec=${if (recording) 1 else 0} bat=$battery spadek=$drop pkt w $min min, ostatni stan $ago s temu, tryb=$lastMode")
    }
}
