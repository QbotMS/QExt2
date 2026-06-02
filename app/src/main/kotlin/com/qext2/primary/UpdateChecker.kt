package com.qext2.primary

import android.content.Context
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import org.json.JSONArray

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
                                val tag = latest.getString("tag_name")
                                val assets = latest.getJSONArray("assets")
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    if (asset.getString("name").endsWith(".apk")) {
                                        Log.i(TAG, "QEXT_UPDATE_AVAILABLE tag=$tag current=$currentVersion")
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
