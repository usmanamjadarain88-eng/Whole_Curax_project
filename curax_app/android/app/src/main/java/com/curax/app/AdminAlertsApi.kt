package com.curax.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object AdminAlertsApi {

    /**
     * DELETE alerts on central DB for this admin. Returns true when HTTP 200.
     */
    fun deleteAlerts(
        baseUrl: String,
        accessCode: String,
        serverAlertIds: List<String>,
        http: OkHttpClient,
    ): Boolean {
        if (baseUrl.isBlank() || accessCode.isBlank() || serverAlertIds.isEmpty()) return false
        val base = baseUrl.trim().removeSuffix("/")
        val body = JSONObject().apply {
            put("access_code", accessCode)
            put("alert_ids", JSONArray(serverAlertIds.distinct()))
        }
        val req = Request.Builder()
            .url("$base/admin/delete-alerts")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val res = http.newCall(req).execute()
        return res.isSuccessful
    }
}
