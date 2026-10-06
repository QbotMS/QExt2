package com.qext2.primary.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trwaly dziennik jazdy QExt2 (bufor logcat na Karoo trzyma tylko kilka minut).
 * Pogoda po trasie, wiatr z karoo-headwind, rozmiary pol, komunikaty KOKPIT, typowe EF, bledy rysowania.
 * Plik: <externalFilesDir>/ride_log.txt, rotacja do ride_log.1.txt po 512 KB.
 * Odczyt: adb pull /sdcard/Android/data/com.qext2.primary/files/ride_log.txt
 */
object RideFileLog {
    private const val MAX_BYTES = 512 * 1024
    @Volatile private var file: File? = null
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(dir: File?) {
        if (dir == null) return
        try { dir.mkdirs(); file = File(dir, "ride_log.txt") } catch (_: Exception) {}
    }

    @Synchronized
    fun append(line: String) {
        val f = file ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "ride_log.1.txt")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            f.appendText(df.format(Date()) + " " + line + "\n")
        } catch (_: Exception) {}
    }
}
