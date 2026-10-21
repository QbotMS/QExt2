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

/**
 * Kamera DJI Osmo Nano -> stan nagrywania na Karoo (sesja osmo-2110).
 * Decyzje Michala: tylko PODGLAD stanu (bez sterowania), pozniej kropka REC w KOKPIT 2 miedzy pogoda a wiatrem.
 *
 * 0.4.24 (21.10.2026): DUML zamiast R-SDK (Nano nie odpowiadalo na R-SDK). Kolejnosc jak Mimo (Osmosis):
 * powiadomienia fff4/fff5 -> [01 00] do fff4 -> 0x00/0x2b [04 00] -> SetPairingPIN (0x07/0x45, nasz staly
 * identyfikator + "osmo"). 0x45 = 01 sparowane / 02 zatwierdz na kamerze, zgoda przychodzi jako prosba 0x07/0x46.
 * Kazda prosba kamery (flagi 0x40) dostaje odpowiedz - inaczej kamera rozlacza. Po parowaniu: 0x53/0x10,
 * podtrzymanie 0x00/0x2b [01 01] co 1 s (Nano rozlacza bezczynne lacze po 5-6 s), subskrypcja cam_status i
 * cam_record_time. Nagrywanie: bit 7 bajtu 0 w stanie kamery 0x02/0x80 (01 -> 81), jesli przyjdzie przez Bluetooth.
 * Etap DIAGNOSTYCZNY: dziennik dostaje kazdy rodzaj wiadomosci kamery (pierwsze wystapienie w calosci) i kazda zmiane
 * cam_status / 0x02/0x80 - zeby sprawdzic, czy stan nagrywania w ogole idzie przez Bluetooth.
 * Lacze trwa od startu do konca jazdy; laczenie po zapisanym adresie (bez skanowania w tle); ponowna proba co 30 s.
 * Bezpiecznik: FeatureGuard.OSMO. Nic tu nie rzuca wyjatkow na zewnatrz.
 */
@SuppressLint("MissingPermission")
object OsmoLink {
    private const val PREFS = "qext2_osmo"
    private const val K_ADDR = "address"
    private const val K_NAME = "name"
    private const val K_IDENT = "duml_identifier"

    private val SVC: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val FFF4: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
    private val FFF5: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val DJI_MFR = 0x08AA

    private const val PAIR_MS = 60_000L
    private const val RETRY_MS = 30_000L
    private const val KEEPALIVE_MS = 1_000L
    private const val WRITE_GAP_MS = 150L
    private const val SUMMARY_MS = 60_000L
    private const val SCAN_MS = 20_000L
    private const val STATUS_STALE_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    private var gatt: BluetoothGatt? = null
    private val cccd = ArrayDeque<BluetoothGattCharacteristic>()
    private val out = ArrayDeque<ByteArray>()
    private var writing = false
    private val rx = ByteArrayOutputStream()
    private var want = false
    private var rideActive = false
    private var onCheck: ((String) -> Unit)? = null
    private var msgId = 0x8100
    private var subId = 0x6900
    private var pairStatus = -1
    private var linkedAt = 0L
    private var seenTypes = HashMap<Int, Int>()
    private var logged = 0
    private val lastParam = HashMap<String, String>()
    private var last0280: Int = -1
    private var lastMode = ""
    private var batAtLink = -1
    private var gotRecInfo = false
    private var warnedNoRec = false

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

    /** Staly identyfikator tej instalacji (32 znaki) - kamera pamieta zgode pod nim; nowy = ponowne pytanie. */
    private fun identifier(): String {
        val p = prefs()
        return p.getString(K_IDENT, null) ?: UUID.randomUUID().toString().take(32).also { p.edit().putString(K_IDENT, it).apply() }
    }

    private fun log(s: String) { try { RideFileLog.append(s) } catch (_: Throwable) {} }
    private fun h2(v: Int) = String.format(Locale.US, "%02x", v)
    private fun nextId(): Int { msgId = if (msgId >= 0x8FFF) 0x8100 else msgId + 1; return msgId }

    /** Stan do kropki (etap 2): 0 brak kamery, 1 polaczona - nie nagrywa, 2 nagrywa. */
    fun dot(): Int = when {
        !connected || !gotRecInfo || System.currentTimeMillis() - lastStatusMs > STATUS_STALE_MS -> if (connected) 1 else 0
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

    /** Test z SETUP: laczy, paruje, czeka na stan. Poza jazda rozlacza po 2 min (czas na wlaczenie/wylaczenie nagrywania). */
    fun check(onDone: (String) -> Unit) {
        main.post {
            if (address() == null) onDone("Najpierw znajdź kamerę.")
            else if (connected) onDone(statusText())
            else { onCheck = onDone; want = true; if (gatt == null) open("test z SETUP") }
        }
    }

    private fun statusText(): String {
        val rec = if (!gotRecInfo) "stan nagrywania: brak danych (sprawdzam)" else if (recording) "NAGRYWA" else "nie nagrywa"
        return "Połączono: $rec" + (if (battery in 0..100) ", bateria $battery%" else "")
    }

    private fun finishCheck(msg: String) {
        val cb = onCheck ?: return
        onCheck = null
        cb(msg)
        if (!rideActive) {
            main.removeCallbacks(checkEnd)
            main.postDelayed(checkEnd, if (connected) 120_000L else 3_000L)
        }
    }

    private val checkEnd = Runnable { if (!rideActive && onCheck == null) { if (connected) summary("koniec testu"); want = false; close(); log("OSMO_CHECK_END") } }

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
            log("OSMO_CONNECT $addr ($reason) DUML")
            pairStatus = -1; seenTypes = HashMap(); logged = 0; lastParam.clear(); last0280 = -1
            gotRecInfo = false; warnedNoRec = false; rx.reset(); out.clear(); writing = false; pairingStarted = false
            gatt = ad.getRemoteDevice(addr).connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            main.removeCallbacks(pairTimeout)
            main.postDelayed(pairTimeout, PAIR_MS)
        } catch (e: Throwable) { fail("open", e) }
    }

    private fun close() {
        main.removeCallbacks(pairTimeout); main.removeCallbacks(keepalive); main.removeCallbacks(summaryTick)
        main.removeCallbacks(pump); main.removeCallbacks(noRecCheck)
        val g = gatt; gatt = null
        try { g?.disconnect() } catch (_: Throwable) {}
        try { g?.close() } catch (_: Throwable) {}
        connected = false; lastStatusMs = 0L; rx.reset(); cccd.clear(); out.clear(); writing = false
    }

    private val retry = Runnable { if (want && gatt == null) open("ponowna proba") }

    private fun scheduleRetry() {
        main.removeCallbacks(retry)
        if (want && rideActive) main.postDelayed(retry, RETRY_MS)
    }

    private val pairTimeout = Runnable {
        if (!connected) {
            log("OSMO_TIMEOUT brak parowania w ${PAIR_MS / 1000} s (pairStatus=$pairStatus)")
            close()
            finishCheck(if (pairStatus == 2) "Kamera czekała na zgodę — nie zatwierdzono na czas." else "Nie połączono. Włącz kamerę i spróbuj ponownie.")
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

    /** Kolejka zapisow do fff5: zapis bez potwierdzenia gubi ramki wyslane jedna po drugiej - odstep 150 ms. */
    private fun send(frame: ByteArray) { out.add(frame); if (!writing) { writing = true; main.post(pump) } }

    private val pump: Runnable = object : Runnable {
        @Suppress("DEPRECATION")
        override fun run() {
            val f = out.poll()
            val g = gatt
            if (f == null || g == null) { writing = false; return }
            try {
                val ch = g.getService(SVC)?.getCharacteristic(FFF5)
                if (ch == null) { log("OSMO_NO_FFF5"); writing = false; return }
                val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, f, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0
                    else { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = f; g.writeCharacteristic(ch) }
                if (!ok && logged++ < 300) log("OSMO_WRITE_FAIL ${OsmoProtocol.hex(f, 16)}")
            } catch (e: Throwable) { fail("write", e); return }
            main.postDelayed(this, WRITE_GAP_MS)
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

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid != FFF4) return
            main.post { if (g === gatt) try { log("OSMO_ARM status=$status"); startPairing() } catch (e: Throwable) { fail("arm", e) } }
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
        log("OSMO_CHARS " + svc.characteristics.joinToString(",") { it.uuid.toString().substring(4, 8) })
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
        if (ch == null) { arm(g); return }
        val d = ch.getDescriptor(CCCD)
        if (d == null) { nextCccd(g); return }
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
    }

    /** [01 00] do fff4 z potwierdzeniem (jak Osmosis: \"arm pairing\"); dalej w onCharacteristicWrite. */
    @Suppress("DEPRECATION")
    private fun arm(g: BluetoothGatt) {
        val ch = g.getService(SVC)?.getCharacteristic(FFF4)
        if (ch == null) { startPairing(); return }
        val v = byteArrayOf(0x01, 0x00)
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            else { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; ch.value = v; g.writeCharacteristic(ch) }
        log("OSMO_ARM_SENT ok=$ok")
        if (!ok) startPairing()
    }

    private var pairingStarted = false

    private fun startPairing() {
        if (pairingStarted) return
        pairingStarted = true
        log("OSMO_PAIR_REQUEST (za pierwszym razem zatwierdz na kamerze)")
        send(OsmoProtocol.sessionPing(open = true, id = nextId()))
        send(OsmoProtocol.pairing(identifier(), 0x8092))
        // zapis bez potwierdzenia moze zginac - ponow parowanie z TYM SAMYM identyfikatorem, poki kamera milczy
        for (d in longArrayOf(3_000L, 6_000L, 10_000L)) main.postDelayed({
            if (gatt != null && !connected && pairStatus == -1) { log("OSMO_PAIR_RETRY"); send(OsmoProtocol.pairing(identifier(), 0x8092)) }
        }, d)
    }

    /** Sklada fragmenty ramki DUML (0x55) i przekazuje cale ramki. */
    private fun bytes(v: ByteArray) {
        try {
            if (v.isEmpty()) return
            if ((v[0].toInt() and 0xFF) == 0x55) rx.reset() else if (rx.size() == 0) {
                if (logged++ < 300) log("OSMO_RAW ${v.size}B ${OsmoProtocol.hex(v, 32)}")
                return
            }
            rx.write(v)
            var b = rx.toByteArray()
            while (b.isNotEmpty()) {
                val len = OsmoProtocol.headerLen(b)
                if (len < 13 || b.size < len) return
                val m = OsmoProtocol.parse(b.copyOf(len))
                if (m == null) { if (logged++ < 300) log("OSMO_BAD_FRAME ${b.size}B ${OsmoProtocol.hex(b, 24)}"); rx.reset(); return }
                message(m)
                b = b.copyOfRange(len, b.size)
                rx.reset(); if (b.isNotEmpty()) rx.write(b)
                if (b.isNotEmpty() && (b[0].toInt() and 0xFF) != 0x55) { rx.reset(); return }
            }
        } catch (e: Throwable) { fail("bytes", e) }
    }

    private fun message(m: OsmoProtocol.Msg) {
        // kazda prosba kamery dostaje odpowiedz (inaczej rozlacza po ~6 s); 0x00/0x81 = informacja o urzadzeniu
        if (m.flags == 0x40) {
            send(OsmoProtocol.reply(m, if (m.cmdSet == 0x00 && m.cmdId == 0x81) OsmoProtocol.APP_DEVICE_INFO else m.payload))
            if (m.cmdSet == 0x07 && m.cmdId == 0x46) { log("OSMO_APPROVED zgoda na kamerze ${OsmoProtocol.hex(m.payload)}"); paired() }
        }
        val key = (m.flags shl 16) or (m.cmdSet shl 8) or m.cmdId
        val n = (seenTypes[key] ?: 0) + 1
        seenTypes[key] = n
        if (n == 1 && logged++ < 300)
            log("OSMO_MSG " + h2(m.cmdSet) + "/" + h2(m.cmdId) + " fl=" + h2(m.flags) + " cel=" + String.format(Locale.US, "%04x", m.target) +
                " ${m.payload.size}B ${OsmoProtocol.hex(m.payload)}")
        when {
            m.cmdSet == 0x07 && m.cmdId == 0x45 && m.flags != 0x40 -> {
                val st = if (m.payload.size >= 2) m.payload[1].toInt() and 0xFF else -2
                if (st != pairStatus) {
                    pairStatus = st
                    log("OSMO_PAIR_STATUS " + when (st) { 1 -> "juz sparowane"; 2 -> "ZATWIERDZ NA KAMERZE"; else -> "status=$st" })
                    if (st == 2) onCheck?.let { it("Zatwierdź połączenie na ekranie kamery…"); onCheck = it }
                }
                if (st == 1) paired()
            }
            m.cmdSet == 0x07 && m.cmdId == 0x46 && m.flags != 0x40 -> { log("OSMO_APPROVED (odp.) ${OsmoProtocol.hex(m.payload)}"); paired() }
            m.cmdSet == 0x02 && m.cmdId == 0x80 && m.payload.isNotEmpty() -> {
                val b0 = m.payload[0].toInt() and 0xFF
                lastStatusMs = System.currentTimeMillis()
                if (m.payload.size >= 30) {
                    gotRecInfo = true
                    recording = (b0 and 0x80) != 0
                }
                if (b0 != last0280) {
                    last0280 = b0
                    log("OSMO_0280 b0=0x" + h2(b0) + " rec=" + (if (recording) "NAGRYWA" else "stop") +
                        " ${m.payload.size}B ${OsmoProtocol.hex(m.payload, 40)}")
                    finishCheck(statusText())
                }
            }
            m.cmdSet == 0x0D && m.cmdId == 0x02 && m.payload.size > 20 -> {
                val pct = m.payload[20].toInt() and 0xFF
                if (pct in 0..100) { if (battery != pct && batAtLink >= 0) log("OSMO_BAT $pct%"); battery = pct; if (batAtLink < 0) batAtLink = pct }
            }
            m.cmdSet == 0x00 && m.cmdId == 0x99 -> {
                val pv = OsmoProtocol.parseParam(m.payload) ?: return
                val hx = OsmoProtocol.hex(pv.second, 48)
                if (lastParam[pv.first] != hx) {
                    lastParam[pv.first] = hx
                    if (logged++ < 300) log("OSMO_PARAM ${pv.first} = $hx")
                }
            }
        }
    }

    private fun paired() {
        if (connected) return
        connected = true
        linkedAt = System.currentTimeMillis()
        batAtLink = -1
        main.removeCallbacks(pairTimeout)
        FeatureGuard.report(FeatureGuard.OSMO, true)
        log("OSMO_LINKED sparowane - podtrzymanie co 1 s, subskrypcja stanu")
        send(OsmoProtocol.session5310(nextId()))
        for (n in listOf("cam_status", "cam_record_time")) send(OsmoProtocol.subscribe(n, subId++, nextId()))
        main.postDelayed(keepalive, KEEPALIVE_MS)
        main.postDelayed(summaryTick, SUMMARY_MS)
        main.postDelayed(noRecCheck, 15_000L)
        onCheck?.let { it("Sparowano. Czekam na stan kamery — włącz i wyłącz nagrywanie."); onCheck = it }
    }

    private val keepalive: Runnable = object : Runnable {
        override fun run() {
            if (!connected) return
            send(OsmoProtocol.sessionPing(open = false, id = nextId()))
            main.postDelayed(this, KEEPALIVE_MS)
        }
    }

    private val noRecCheck = Runnable {
        if (connected && !gotRecInfo && !warnedNoRec) {
            warnedNoRec = true
            log("OSMO_NO_REC_INFO 15 s po parowaniu bez stanu 0x02/0x80 - parametry: " + lastParam.keys.joinToString(","))
            finishCheck("Połączono, ale kamera nie podaje (jeszcze) stanu nagrywania. Włącz i wyłącz nagrywanie — zapiszę, co przyśle.")
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
        log("OSMO_STAT ($why) rec=${if (!gotRecInfo) "?" else if (recording) "1" else "0"} bat=$battery spadek=$drop pkt w $min min, ostatni stan $ago s temu, rodzajow wiadomosci=${seenTypes.size}")
    }
}
