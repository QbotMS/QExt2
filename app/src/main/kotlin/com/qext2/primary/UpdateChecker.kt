package com.qext2.primary

import android.content.Intent
import android.net.Uri
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import org.json.JSONArray

object UpdateChecker {

    private const val TAG = "QExt2Update"
    private const val REPO = "QbotMS/QExt2"
    private var checked = false

    fun check(karooSystem: KarooSystemService) {
        if (checked) return
        checked = true

        try {
            val currentVersion = karooSystem.applicationContext
                .packageManager
                .getPackageInfo(karooSystem.applicationContext.packageName, 0)
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
                                        val url = asset.getString("browser_download_url")
                                        Log.i(TAG, "QEXT_UPDATE_FOUND tag=${latest.getString("tag_name")}")
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        karooSystem.applicationContext.startActivity(intent)
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
}
