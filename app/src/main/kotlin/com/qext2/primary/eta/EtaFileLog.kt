package com.qext2.primary.eta

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trwaly zapis logow ETA v2 do pliku (bufor logcat na Karoo miesci tylko kilka minut).
 * Plik: <externalFilesDir>/eta_log.txt, rotacja do eta_log.1.txt po 256 KB.
 * Odczyt: adb pull /sdcard/Android/data/com.qext2.primary/files/eta_log.txt
 */
object EtaFileLog {
    private const val MAX_BYTES = 256 * 1024
    @Volatile private var file: File? = null
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(dir: File?) {
        if (dir == null) return
        try {
            dir.mkdirs()
            file = File(dir, "eta_log.txt")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun append(line: String) {
        val f = file ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "eta_log.1.txt")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            f.appendText(df.format(Date()) + " " + line + "\n")
        } catch (_: Exception) {
        }
    }
}
