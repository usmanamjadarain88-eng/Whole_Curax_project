package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone user: persist a merged view of [AdminDemoData] into [Prefs.cachedUserDataSnapshotJson]
 * so [UserDataBusClient.restoreCachedUserData] can hydrate medicines, API alerts, reminders, and
 * settings immediately on next cold start.
 *
 * Does not touch the data-bus WebSocket, HTTP fetch pipeline, or FCM — callers invoke this from UI
 * / fragment layers after local state is already updated.
 */
object StandaloneOfflineMirror {

    fun persistMergedSnapshot(context: Context) {
        val ctx = context.applicationContext
        if (!StandaloneUi.isUserStandalone(ctx)) return
        if (!AppRole.isUser(ctx)) return

        val prefs = Prefs(ctx)
        val base = try {
            JSONObject(prefs.cachedUserDataSnapshotJson.trim().ifEmpty { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }

        base.put("medicines", medicinesToJsonArray(AdminDemoData.medicines))
        base.put("dose_log", DoseTrackingLocalStore.doseLogToJsonArray(ctx))
        base.put("alerts", apiAlertsToJsonArray(AdminDemoData.getApiAlerts()))
        base.put("medical_reminders", medicalRemindersToJson(AdminDemoData.getMedicalReminders()))
        val settings = AdminDemoData.getAlertSettings()
        if (settings.isNotEmpty()) {
            base.put("alert_settings", mapToJsonObject(settings))
        }

        val st = prefs.lastSyncTime.trim()
        if (st.isNotEmpty()) base.put("server_time", st)
        val ufn = prefs.userHubFirstName.trim()
        if (ufn.isNotEmpty()) base.put("user_first_name", ufn)
        if (AppModeManager.isStandaloneMode(ctx)) {
            base.put("user_display_mode", "standalone")
        }
        val pic = prefs.userProfilePictureDataUrl.trim()
        if (pic.isNotEmpty()) {
            base.put("profile_picture", pic)
        }

        prefs.cachedUserDataSnapshotJson = base.toString()
        prefs.userStandaloneDataReady = true
        LocalAlertsController.reschedule(ctx)
    }

    /** Payload for POST /user/standalone-sync (medicines + dose log + reminders + client clock). */
    fun buildStandaloneSyncPayload(context: Context): JSONObject {
        val ctx = context.applicationContext
        return JSONObject().apply {
            put("medicines", medicinesToJsonArray(AdminDemoData.medicines))
            put("dose_append", DoseTrackingLocalStore.doseLogToJsonArray(ctx))
            put("medical_reminders", medicalRemindersToJson(AdminDemoData.getMedicalReminders()))
            put("client_ms", System.currentTimeMillis())
        }
    }

    private fun medicinesToJsonArray(meds: List<AdminDemoData.Medicine>): JSONArray {
        val arr = JSONArray()
        for (m in meds) {
            arr.put(
                JSONObject().apply {
                    put("name", m.name)
                    put("box_id", m.box)
                    put("quantity", m.stock)
                    put("low_stock", AdminDemoData.getLowStockThreshold())
                    put("dosage", "${m.dosePerDay} per day")
                    put("dose_per_day", m.dosePerDay)
                    put("exact_time", m.exactTime)
                    put("instructions", "${m.dosePerDay} per day")
                    put("times", JSONArray().put(m.exactTime))
                    put("expiry", m.expiry)
                },
            )
        }
        return arr
    }

    private val alertTimeFmt =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }

    private fun apiAlertsToJsonArray(items: List<AlertItem>): JSONArray {
        val arr = JSONArray()
        for (a in items) {
            arr.put(
                JSONObject().apply {
                    put("type", a.type)
                    put("message", a.message)
                    put("created_at", alertTimeFmt.format(Date(a.receivedAt)))
                    if (a.userName.isNotBlank()) put("user_name", a.userName)
                },
            )
        }
        return arr
    }

    private fun medicalRemindersToJson(map: Map<String, List<Map<String, Any?>>>): JSONObject {
        val root = JSONObject()
        for (key in listOf("appointments", "prescriptions", "lab_tests", "custom")) {
            val arr = JSONArray()
            for (m in map[key].orEmpty()) {
                arr.put(mapToJsonObject(m))
            }
            root.put(key, arr)
        }
        return root
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) {
            o.put(k, valueToJson(v))
        }
        return o
    }

    private fun valueToJson(value: Any?): Any {
        if (value == null) return JSONObject.NULL
        return when (value) {
            is Map<*, *> -> {
                val out = JSONObject()
                for ((k, v) in value) {
                    out.put(k.toString(), valueToJson(v))
                }
                out
            }
            is List<*> -> {
                val arr = JSONArray()
                for (e in value) {
                    arr.put(valueToJson(e))
                }
                arr
            }
            is Boolean, is Int, is Long, is Float, is Double -> value
            is String -> value
            else -> value.toString()
        }
    }
}
