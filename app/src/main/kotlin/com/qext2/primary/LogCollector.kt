package com.qext2.primary

import java.io.BufferedReader
import java.io.InputStreamReader

object LogCollector {

    fun collect(): String {
        val lines = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.forEachLine { line ->
                if (line.contains("QExt2") || line.contains("QEXT_") || line.contains("AndroidRuntime")) {
                    lines.add(line)
                }
            }
            reader.close()
        } catch (_: Exception) {}
        return lines.joinToString("\n").ifEmpty { "Brak logow QExt2" }
    }
}
