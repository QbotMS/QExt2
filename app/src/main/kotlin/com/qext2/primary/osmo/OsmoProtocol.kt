package com.qext2.primary.osmo

/**
 * 21.10.2026 (0.4.24, sesja osmo-2110): protokol DUML (SOF 0x55) do kamery DJI Osmo Nano przez Bluetooth.
 * 0.4.23 probowalo R-SDK (jezyk pilota DJI) - Nano nie odpowiedzialo ani slowem (test 21.10 20:38), pilot DJI
 * oficjalnie nie obsluguje Nano. DUML to jezyk aplikacji Mimo; opis i kod: projekt Osmosis
 * (github.com/KonradIT/osmosis, licencja MIT, (c) 2026 Konrad Iturbe), CRC z dimadesu/dji-remote (MIT).
 *
 * Ramka BLE: 0 SOF 0x55 | 1 dlugosc (13 + dane) | 2 wersja 0x04 | 3 CRC8 z [0..2] | 4-5 cel (nadawca, odbiorca)
 * | 6-7 id wiadomosci | 8-10 typ (flagi, CmdSet, CmdId) | dane | CRC16 z calosci bez CRC (LE).
 * Flagi: 0x40 prosba, 0xC0 odpowiedz, 0x00 powiadomienie. Adres = (id << 5) | typ: aplikacja 0x02, kamera 0x01,
 * Wi-Fi 0x07, system 0x28, sesja 0xF0, 0x1C.
 */
object OsmoProtocol {

    fun crc8(d: ByteArray, len: Int): Int {
        var c = 0x77
        for (i in 0 until len) {
            c = c xor (d[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 }
        }
        return c and 0xFF
    }

    fun crc16(d: ByteArray, len: Int): Int {
        var c = 0x3692
        for (i in 0 until len) {
            c = c xor (d[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        }
        return c and 0xFFFF
    }

    // cel = nadawca | (odbiorca << 8), zapisywany LE -> bajt 4 nadawca, bajt 5 odbiorca
    const val T_WIFI = 0x0702
    const val T_CAMERA = 0x0102
    const val T_SYSTEM = 0x2802
    const val T_SESSION = 0xF002
    const val T_1C = 0x1C02

    /** Buduje ramke. type = flags | (cmdSet << 8) | (cmdId << 16). */
    fun frame(target: Int, id: Int, type: Int, payload: ByteArray): ByteArray {
        val n = 13 + payload.size
        val f = ByteArray(n)
        f[0] = 0x55; f[1] = (n and 0xFF).toByte(); f[2] = 0x04
        f[3] = crc8(f, 3).toByte()
        f[4] = (target and 0xFF).toByte(); f[5] = ((target shr 8) and 0xFF).toByte()
        f[6] = (id and 0xFF).toByte(); f[7] = ((id shr 8) and 0xFF).toByte()
        f[8] = (type and 0xFF).toByte(); f[9] = ((type shr 8) and 0xFF).toByte(); f[10] = ((type shr 16) and 0xFF).toByte()
        System.arraycopy(payload, 0, f, 11, payload.size)
        val c = crc16(f, n - 2)
        f[n - 2] = (c and 0xFF).toByte(); f[n - 1] = ((c shr 8) and 0xFF).toByte()
        return f
    }

    fun request(target: Int, cmdSet: Int, cmdId: Int, payload: ByteArray, id: Int): ByteArray =
        frame(target, id, 0x40 or (cmdSet shl 8) or (cmdId shl 16), payload)

    class Msg(val target: Int, val id: Int, val type: Int, val payload: ByteArray) {
        val flags: Int get() = type and 0xFF
        val cmdSet: Int get() = (type shr 8) and 0xFF
        val cmdId: Int get() = (type shr 16) and 0xFF
    }

    /** Dlugosc ramki z naglowka; -1 gdy za malo bajtow. */
    fun headerLen(b: ByteArray): Int = if (b.size < 2) -1 else (b[1].toInt() and 0xFF)

    /** null = zla ramka (dlugosc, wersja, CRC). */
    fun parse(f: ByteArray): Msg? {
        if (f.size < 13 || (f[0].toInt() and 0xFF) != 0x55) return null
        if ((f[1].toInt() and 0xFF) != f.size || (f[2].toInt() and 0xFF) != 0x04) return null
        if (crc8(f, 3) != (f[3].toInt() and 0xFF)) return null
        val c = (f[f.size - 2].toInt() and 0xFF) or ((f[f.size - 1].toInt() and 0xFF) shl 8)
        if (crc16(f, f.size - 2) != c) return null
        val target = (f[4].toInt() and 0xFF) or ((f[5].toInt() and 0xFF) shl 8)
        val id = (f[6].toInt() and 0xFF) or ((f[7].toInt() and 0xFF) shl 8)
        val type = (f[8].toInt() and 0xFF) or ((f[9].toInt() and 0xFF) shl 8) or ((f[10].toInt() and 0xFF) shl 16)
        return Msg(target, id, type, f.copyOfRange(11, f.size - 2))
    }

    /** Odpowiedz na prosbe kamery: cel zamieniony, to samo id, flagi 0xC0. */
    fun reply(m: Msg, payload: ByteArray): ByteArray {
        val t = ((m.target and 0xFF) shl 8) or ((m.target shr 8) and 0xFF)
        return frame(t, m.id, (m.type and 0xFFFF00) or 0xC0, payload)
    }

    fun packString(s: String): ByteArray { val b = s.toByteArray(Charsets.UTF_8); return byteArrayOf(b.size.toByte()) + b }

    /** SetPairingPIN (0x07/0x45): PackString(identyfikator 32 znaki) + PackString("osmo"). */
    fun pairing(identifier: String, id: Int): ByteArray =
        request(T_WIFI, 0x07, 0x45, packString(identifier) + packString("osmo"), id)

    /** 0x00/0x2b do sesji: 04 00 = otwarcie (przed parowaniem), 01 01 = podtrzymanie co ~1 s. */
    fun sessionPing(open: Boolean, id: Int): ByteArray =
        request(T_SESSION, 0x00, 0x2B, if (open) byteArrayOf(0x04, 0x00) else byteArrayOf(0x01, 0x01), id)

    /** 0x53/0x10 -> 0x1C (Mimo po parowaniu; kamera odpowiada 01 00 00 00). */
    fun session5310(id: Int): ByteArray = request(T_1C, 0x53, 0x10, byteArrayOf(0, 0, 0, 0), id)

    /** Subskrypcja parametru (0x00/0x99 -> system 0x28), bajt w bajt jak Mimo (Osmosis CameraSession.subscription). */
    fun subscribe(name: String, subId: Int, id: Int): ByteArray {
        val nb = name.toByteArray(Charsets.US_ASCII)
        val inner = nb.size + 6
        val p = byteArrayOf(0x02, 0x02, 0x00, 0x00) + le32(subId) + byteArrayOf(0, 0, 0) +
            byteArrayOf((inner and 0xFF).toByte(), ((inner shr 8) and 0xFF).toByte()) +
            byteArrayOf((nb.size and 0xFF).toByte(), ((nb.size shr 8) and 0xFF).toByte()) + nb + byteArrayOf(0, 0, 0, 0)
        return request(T_SYSTEM, 0x00, 0x99, p, id)
    }

    /** Odpowiedz aplikacji na 0x00/0x81 (informacja o urzadzeniu "APP"), jak Osmosis APP_DEVICE_INFO. */
    val APP_DEVICE_INFO: ByteArray = byteArrayOf(0x00, 0x41, 0x50, 0x50) + ByteArray(37) +
        byteArrayOf(0x02) + ByteArray(8) + byteArrayOf(0x02, 0x08) + ByteArray(10)

    /** Wypchniety parametr (0x00/0x99 z czasownikiem 06): nazwa i wartosc; null gdy inny ksztalt. */
    fun parseParam(p: ByteArray): Pair<String, ByteArray>? {
        if (p.size < 17 || (p[0].toInt() and 0xFF) != 0x02 || (p[1].toInt() and 0xFF) != 0x06) return null
        val nl = (p[13].toInt() and 0xFF) or ((p[14].toInt() and 0xFF) shl 8)
        if (nl <= 0 || 15 + nl > p.size) return null
        val name = String(p, 15, nl, Charsets.US_ASCII)
        val vAt = 15 + nl + 6
        if (vAt + 2 > p.size) return name to ByteArray(0)
        val vl = (p[vAt].toInt() and 0xFF) or ((p[vAt + 1].toInt() and 0xFF) shl 8)
        val from = vAt + 2
        return name to p.copyOfRange(from, minOf(p.size, from + vl))
    }

    private fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    fun hex(b: ByteArray, max: Int = 64): String = b.take(max).joinToString("") { "%02x".format(it) } + if (b.size > max) "…" else ""
}
