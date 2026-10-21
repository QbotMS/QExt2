package com.qext2.primary.osmo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 21.10.2026 (0.4.23, sesja osmo-2110): protokol DJI R-SDK do kamery Osmo (Nano) przez Bluetooth.
 * Przeniesione z projektu Osmosis (github.com/KonradIT/osmosis, licencja MIT, (c) 2026 Konrad Iturbe),
 * plik rsdk/RsdkProtocol.kt, ktory z kolei jest bajt w bajt z demo DJI Osmo-GPS-Controller-Demo.
 * Uzywamy WYLACZNIE odczytu stanu: polaczenie (0x00/0x19), subskrypcja stanu (0x1D/0x05),
 * stan kamery (0x1D/0x02: tryb, nagrywa, bateria) i tryb tekstem (0x1D/0x06). Sterowania nagrywaniem NIE ma.
 *
 * Ramka (little-endian): 0 SOF 0xAA | 1-2 dlugosc (10 bit) | 3 CmdType | 4 ENC | 5-7 RES | 8-9 SEQ |
 * 10-11 CRC16 z [0..9] | 12 CmdSet | 13 CmdID | 14.. dane | +4 CRC32 z [0..koniec-4].
 * GATT: usluga fff0, powiadomienia fff4 (i fff5), zapis fff5 bez potwierdzenia.
 */
object OsmoProtocol {

    private val CRC16_TABLE = IntArray(256) { i ->
        var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }; c
    }
    private val CRC32_TABLE = IntArray(256) { i ->
        var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor -0x12477ce0 /* 0xEDB88320 */ else c ushr 1 }; c
    }

    private fun crc16(d: ByteArray, len: Int): Int {
        var c = 0x3AA3
        for (i in 0 until len) c = CRC16_TABLE[(c xor d[i].toInt()) and 0xFF] xor (c ushr 8)
        return c and 0xFFFF
    }

    private fun crc32(d: ByteArray, len: Int): Int {
        var c = 0x3AA3
        for (i in 0 until len) c = CRC32_TABLE[(c xor d[i].toInt()) and 0xFF] xor (c ushr 8)
        return c
    }

    const val CMD_NO_RESPONSE = 0x00
    const val CMD_WAIT_RESULT = 0x02
    const val ACK_NO_RESPONSE = 0x20

    const val SET_GENERAL = 0x00
    const val ID_CONNECTION = 0x19
    const val SET_CAMERA = 0x1D
    const val ID_STATUS_PUSH = 0x02
    const val ID_STATUS_SUB = 0x05
    const val ID_STATUS_PUSH_NEW = 0x06

    fun frame(cmdSet: Int, cmdId: Int, cmdType: Int, payload: ByteArray, seq: Int): ByteArray {
        val frameLen = 14 + payload.size + 4
        val f = ByteArray(frameLen)
        var o = 0
        f[o++] = 0xAA.toByte()
        val verLen = frameLen and 0x03FF
        f[o++] = (verLen and 0xFF).toByte(); f[o++] = ((verLen ushr 8) and 0xFF).toByte()
        f[o++] = cmdType.toByte()
        f[o++] = 0; f[o++] = 0; f[o++] = 0; f[o++] = 0
        f[o++] = (seq and 0xFF).toByte(); f[o++] = ((seq ushr 8) and 0xFF).toByte()
        val c16 = crc16(f, o)
        f[o++] = (c16 and 0xFF).toByte(); f[o++] = ((c16 ushr 8) and 0xFF).toByte()
        f[o++] = cmdSet.toByte(); f[o++] = cmdId.toByte()
        System.arraycopy(payload, 0, f, o, payload.size); o += payload.size
        val c32 = crc32(f, o)
        f[o++] = (c32 and 0xFF).toByte(); f[o++] = ((c32 ushr 8) and 0xFF).toByte()
        f[o++] = ((c32 ushr 16) and 0xFF).toByte(); f[o] = ((c32 ushr 24) and 0xFF).toByte()
        return f
    }

    class Frame(val cmdSet: Int, val cmdId: Int, val cmdType: Int, val seq: Int, val payload: ByteArray) {
        val isResponse: Boolean get() = (cmdType and 0x20) != 0
    }

    /** Dlugosc calej ramki z naglowka (do skladania fragmentow); -1 gdy za malo bajtow. */
    fun headerLen(b: ByteArray): Int =
        if (b.size < 3) -1 else ((b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)) and 0x03FF

    /** null = zla ramka (dlugosc, CRC). */
    fun parse(f: ByteArray): Frame? {
        if (f.size < 18 || (f[0].toInt() and 0xFF) != 0xAA) return null
        val len = headerLen(f)
        if (len != f.size) return null
        if (crc16(f, 10) != ((f[10].toInt() and 0xFF) or ((f[11].toInt() and 0xFF) shl 8))) return null
        val dataEnd = len - 4
        val stored = (f[dataEnd].toInt() and 0xFF) or ((f[dataEnd + 1].toInt() and 0xFF) shl 8) or
            ((f[dataEnd + 2].toInt() and 0xFF) shl 16) or ((f[dataEnd + 3].toInt() and 0xFF) shl 24)
        if (crc32(f, dataEnd) != stored) return null
        return Frame(
            cmdSet = f[12].toInt() and 0xFF, cmdId = f[13].toInt() and 0xFF,
            cmdType = f[3].toInt() and 0xFF,
            seq = (f[8].toInt() and 0xFF) or ((f[9].toInt() and 0xFF) shl 8),
            payload = f.copyOfRange(14, dataEnd),
        )
    }

    private fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    /** Prosba o polaczenie (0x00/0x19), 33 B. verifyMode 0 = kamera decyduje (pierwszy raz okno na kamerze). */
    fun connectionRequest(deviceId: Int, mac: ByteArray, verifyMode: Int, verifyData: Int): ByteArray {
        val b = buf(33)
        b.putInt(deviceId)
        b.put(mac.size.toByte())
        b.put(mac); repeat(16 - mac.size) { b.put(0) }
        b.putInt(0)
        b.put(0)
        b.put(verifyMode.toByte())
        b.putShort(verifyData.toShort())
        b.putInt(0)
        return b.array()
    }

    /** Odpowiedz na zgode kamery (0x00/0x19 ACK), 9 B. */
    fun connectionResponse(deviceId: Int): ByteArray {
        val b = buf(9)
        b.putInt(deviceId); b.put(0); b.put(0); b.put(0); b.put(0); b.put(0)
        return b.array()
    }

    /** Subskrypcja stanu (0x1D/0x05): tryb 3 = okresowo + przy zmianie, 20 = 2 Hz (wartosci DJI). */
    fun statusSubscription(): ByteArray {
        val b = buf(6)
        b.put(3); b.put(20); b.putInt(0)
        return b.array()
    }

    class Status(val mode: Int, val status: Int, val recordTimeS: Int, val battery: Int) {
        /** 0x03 nagrywa, 0x05 pre-rec (nagrywanie wsteczne) */
        val recording: Boolean get() = status == 0x03 || status == 0x05
    }

    /** Stan kamery (0x1D/0x02). Bateria = ostatni bajt. */
    fun parseStatus(p: ByteArray): Status? {
        if (p.size < 7) return null
        return Status(
            mode = p[0].toInt() and 0xFF, status = p[1].toInt() and 0xFF,
            recordTimeS = (p[5].toInt() and 0xFF) or ((p[6].toInt() and 0xFF) shl 8),
            battery = p[p.size - 1].toInt() and 0xFF,
        )
    }

    /** Tryb tekstem (0x1D/0x06), 46 B: [01][len][nazwa:21][02][len][parametr:21]. Bez informacji o nagrywaniu. */
    fun parseModeText(p: ByteArray): String? {
        if (p.size < 46) return null
        if ((p[0].toInt() and 0xFF) != 0x01 || (p[23].toInt() and 0xFF) != 0x02) return null
        fun str(at: Int, len: Int): String =
            String(p, at, len.coerceIn(0, 21), Charsets.US_ASCII).trimEnd('\u0000').trim()
        val name = str(2, p[1].toInt() and 0xFF); val par = str(25, p[24].toInt() and 0xFF)
        return if (par.isEmpty()) name else "$name $par"
    }

    fun hex(b: ByteArray, max: Int = 48): String = b.take(max).joinToString("") { "%02x".format(it) }
}
