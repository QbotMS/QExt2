package com.qext2.primary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import org.json.JSONArray
import java.io.File

object UpdateChecker {

    private const val TAG = "QExt2Update"
    private const val REPO = "QbotMS/QExt2"
    private var checked = false

    fun check(context: Context, karooSystem: KarooSystemService) {
        if (checked) return
        checked = true

        try {
            val currentVersion = context
                .packageManager
                .getPackageInfo(context.packageName, 0)
                .versionCode
            Log.i(TAG, "QEXT_UPDATE_CHECK repo=$REPO current=$currentVersion")

            karooSystem.addConsumer<OnHttpResponse>(
                params = OnHttpResponse.MakeHttpRequest(
                    method = "GET",
                    url = "https://api.github.com/repos/$REPO/releases?per_page=1",
                    waitForConnection = false,
                ),
                onError = { Log.d(TAG, "QEXT_UPDATE_CHECK_FAILED reason=$it") },
                onEvent = { resp ->
                    val s = resp.state
                    if (s is HttpResponseState.Complete && s.statusCode == 200) {
                        try {
                            val releases = JSONArray(String(s.body ?: return@addConsumer))
                            if (releases.length() > 0) {
                                val latest = releases.getJSONObject(0)
                                val assets = latest.getJSONArray("assets")
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    if (asset.getString("name").endsWith(".apk")) {
                                        downloadApk(context, karooSystem, asset.getString("browser_download_url"))
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

    private fun downloadApk(context: Context, system: KarooSystemService, url: String) {
        Log.i(TAG, "QEXT_UPDATE_DOWNLOAD_START")
        system.addConsumer<OnHttpResponse>(
            params = OnHttpResponse.MakeHttpRequest(method = "GET", url = url, waitForConnection = true),
            onError = { Log.w(TAG, "QEXT_UPDATE_DOWNLOAD_FAILED reason=$it") },
            onEvent = { resp ->
                val s = resp.state
                if (s is HttpResponseState.Complete && s.statusCode == 200) {
                    val data = s.body
                    if (data != null) {
                        try {
                            val file = File(context.cacheDir, "qext2_update.apk")
                            file.writeBytes(data)
                            Log.i(TAG, "QEXT_UPDATE_DOWNLOADED size=${data.size} path=${file.absolutePath}")

                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.w(TAG, "QEXT_UPDATE_INSTALL_FAILED msg=${e.message}")
                        }
                    }
                }
            },
        )
    }
}
