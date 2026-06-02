package com.qext2.primary

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
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

                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)

                session.openWrite("QExt2.apk", 0, data.size.toLong()).use { out ->
                    out.write(data)
                    session.fsync(out)
                }

                val intent = Intent(context, context.javaClass)
                intent.putExtra("session_id", sessionId)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                session.commit(pendingIntent.intentSender)
                session.close()
                Log.i(TAG, "QEXT_UPDATE_SESSION_COMMITTED session=$sessionId")
            } catch (e: Exception) {
                Log.w(TAG, "QEXT_UPDATE_DOWNLOAD_FAILED msg=${e.message}", e)
            }
        }
    }
}
