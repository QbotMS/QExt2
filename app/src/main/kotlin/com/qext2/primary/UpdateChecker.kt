package com.qext2.primary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TAG = "QExt2Update"
    private const val REPO = "QbotMS/QExt2"

    fun check(context: Context, karooSystem: KarooSystemService) {
        try {
            val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            Log.i(TAG, "QEXT_UPDATE_CHECK repo=$REPO current=$currentVersion")

            karooSystem.addConsumer<OnHttpResponse>(
                params = OnHttpResponse.MakeHttpRequest("GET", "https://api.github.com/repos/$REPO/releases?per_page=1", waitForConnection = false),
                onError = { Log.d(TAG, "QEXT_UPDATE_CHECK_FAILED reason=$it") },
                onEvent = { resp ->
                    val s = resp.state
                    if (s is HttpResponseState.Complete && s.statusCode == 200) {
                        try {
                            val releases = JSONArray(String(s.body ?: return@addConsumer))
                            if (releases.length() > 0) {
                                val assets = releases.getJSONObject(0).getJSONArray("assets")
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    if (asset.getString("name").endsWith(".apk")) {
                                        downloadAndInstall(context, asset.getString("browser_download_url"))
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                },
            )
        } catch (_: Exception) {}
    }

    private fun downloadAndInstall(context: Context, url: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "QEXT_UPDATE_DOWNLOAD_START")
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000; connection.readTimeout = 60_000
                val data = connection.inputStream.readBytes()
                connection.disconnect()
                Log.i(TAG, "QEXT_UPDATE_DOWNLOADED size=${data.size}")

                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, "QExt2.apk")
                file.writeBytes(data)

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setData(uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) { context.startActivity(intent) }
                Log.i(TAG, "QEXT_UPDATE_INSTALL_PROMPTED")
            } catch (e: Exception) {
                Log.w(TAG, "QEXT_UPDATE_DOWNLOAD_FAILED msg=${e.message}", e)
            }
        }
    }
}
