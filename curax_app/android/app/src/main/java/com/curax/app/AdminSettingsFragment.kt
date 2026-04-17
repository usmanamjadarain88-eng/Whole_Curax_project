package com.curax.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AdminSettingsFragment : Fragment() {

    private var syncReceiverRegistered = false
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) refresh()
        }
    }

    private fun isUserApp(): Boolean = AppRole.isUser(requireContext())

    private fun readAppVersionName(): String = try {
        val pm = requireContext().packageManager
        val pn = requireContext().packageName
        val vn = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pn, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pn, 0).versionName
        }
        vn.orEmpty()
    } catch (_: Exception) {
        ""
    }

    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = if (StandaloneUi.isUserStandalone(requireContext())) {
            R.layout.fragment_admin_settings_standalone
        } else {
            R.layout.fragment_admin_settings
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tv_standalone_settings_version)?.text =
            "Version ${readAppVersionName()}"
        refresh()
        fetchSettingsFromServer()

        if (isUserApp()) {
            view.findViewById<MaterialButton>(R.id.btn_save_alert_settings).visibility = View.GONE
            view.findViewById<MaterialButton>(R.id.btn_save_gmail).visibility = View.GONE
            disableInputs(view)
        } else {
            view.findViewById<MaterialButton>(R.id.btn_save_alert_settings).setOnClickListener {
                saveSettingsToApi(showSuccess = "Alert settings saved")
            }
            view.findViewById<MaterialButton>(R.id.btn_save_gmail).setOnClickListener {
                saveSettingsToApi(showSuccess = "Gmail settings saved")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!syncReceiverRegistered) {
            val filter = IntentFilter(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(syncReceiver, filter)
            }
            syncReceiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroyView() {
        if (syncReceiverRegistered) {
            try { requireContext().unregisterReceiver(syncReceiver) } catch (_: Exception) {}
            syncReceiverRegistered = false
        }
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (isUserApp()) return
        fetchSettingsFromServer()
    }

    private fun fetchSettingsFromServer() {
        val prefs = Prefs(requireContext())
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty()) return
        if (!isUserApp() && accessCode.isEmpty()) return
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (isUserApp() && (botId.isEmpty() || apiKey.isEmpty())) return

        Thread {
            try {
                var url = if (isUserApp()) {
                    "$base/user/data?bot_id=${URLEncoder.encode(botId, "UTF-8")}&api_key=${URLEncoder.encode(apiKey, "UTF-8")}"
                } else {
                    "$base/admin/data?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                }
                if (!isUserApp() && prefs.actAsUserId.isNotEmpty()) {
                    url += "&act_as_user_id=${URLEncoder.encode(prefs.actAsUserId, "UTF-8")}"
                }
                val req = Request.Builder().url(url).get().build()
                val res = http.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: "{}"
                    try {
                        val data = JSONObject(body)
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            AdminDataBusClient.applyAdminDataJson(requireContext(), data)
                            refresh()
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun bool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> v != null && v.toString().lowercase() in listOf("true", "1", "yes")
    }

    private fun boolOrDefault(v: Any?, default: Boolean = true): Boolean =
        if (v == null) default else bool(v)

    private fun disableInputs(root: View) {
        when (root) {
            is EditText -> root.isEnabled = false
            is CheckBox -> root.isEnabled = false
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                disableInputs(root.getChildAt(i))
            }
        }
    }

    fun refresh() {
        if (!isAdded) return
        val settings = AdminDemoData.getAlertSettings()
        // GET /admin/data and GET /user/data put alert_settings JSON into the store flat
        // (medicine_alerts, stock_alerts, … at top level). Admin save path may nest under "alert_settings".
        val nested = settings["alert_settings"] as? Map<*, *>
        fun section(key: String): Map<*, *>? {
            val fromNested = nested?.get(key) as? Map<*, *>
            if (fromNested != null) return fromNested
            return settings[key] as? Map<*, *>
        }
        val ma = section("medicine_alerts")
        val esc = section("missed_dose_escalation")
        val sa = section("stock_alerts")
        val ea = section("expiry_alerts")
        val gmail = settings["gmail_config"] as? Map<*, *>

        view?.findViewById<CheckBox>(R.id.cb_medicine_30_before)?.isChecked = boolOrDefault(ma?.get("30_min_before"))
        view?.findViewById<CheckBox>(R.id.cb_medicine_15_before)?.isChecked = boolOrDefault(ma?.get("15_min_before"))
        view?.findViewById<CheckBox>(R.id.cb_medicine_exact)?.isChecked = boolOrDefault(ma?.get("exact_time"))
        view?.findViewById<EditText>(R.id.et_medicine_snooze)?.setText((ma?.get("snooze_duration") ?: 5).toString())

        view?.findViewById<CheckBox>(R.id.cb_missed_5_min)?.isChecked = boolOrDefault(esc?.get("5_min_reminder"))
        view?.findViewById<CheckBox>(R.id.cb_missed_15_urgent)?.isChecked = boolOrDefault(esc?.get("15_min_urgent"))
        view?.findViewById<CheckBox>(R.id.cb_missed_30_family)?.isChecked = boolOrDefault(esc?.get("30_min_family"))
        view?.findViewById<CheckBox>(R.id.cb_missed_1hr_log)?.isChecked = boolOrDefault(esc?.get("1_hour_log"))
        view?.findViewById<EditText>(R.id.et_missed_family_email)?.setText(esc?.get("family_email")?.toString()?.trim().orEmpty())

        view?.findViewById<CheckBox>(R.id.cb_stock_enabled)?.isChecked = boolOrDefault(sa?.get("enabled"))
        view?.findViewById<EditText>(R.id.et_stock_threshold)?.setText((sa?.get("low_stock_threshold") ?: 5).toString())
        view?.findViewById<CheckBox>(R.id.cb_stock_empty)?.isChecked = boolOrDefault(sa?.get("empty_alert"))
        view?.findViewById<CheckBox>(R.id.cb_stock_critical)?.isChecked = boolOrDefault(sa?.get("critical_alert"))

        view?.findViewById<CheckBox>(R.id.cb_expiry_30)?.isChecked = boolOrDefault(ea?.get("30_days_before"))
        view?.findViewById<CheckBox>(R.id.cb_expiry_15)?.isChecked = boolOrDefault(ea?.get("15_days_before"))
        view?.findViewById<CheckBox>(R.id.cb_expiry_7)?.isChecked = boolOrDefault(ea?.get("7_days_before"))
        view?.findViewById<CheckBox>(R.id.cb_expiry_1)?.isChecked = boolOrDefault(ea?.get("1_day_before"))

        view?.findViewById<EditText>(R.id.et_gmail_sender)?.setText(gmail?.get("sender_email")?.toString()?.trim().orEmpty())
        view?.findViewById<EditText>(R.id.et_gmail_code)?.setText(gmail?.get("sender_password")?.toString()?.trim().orEmpty())
        view?.findViewById<EditText>(R.id.et_gmail_recipients)?.setText(gmail?.get("recipients")?.toString()?.trim().orEmpty())
    }

    private fun saveSettingsToApi(showSuccess: String) {
        if (isUserApp()) return
        val prefs = Prefs(requireContext())
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            CuraxFeedback.warn(this, "Not signed in as admin")
            return
        }

        val payload = collectSettingsFromUi()
        AdminDemoData.replaceAlertSettings(payload)

        val alertSettings = payload["alert_settings"] as? Map<String, Any?> ?: emptyMap()
        val gmailConfig = payload["gmail_config"] as? Map<String, Any?> ?: emptyMap()

        Thread {
            try {
                val bodyObj = JSONObject().apply {
                    put("access_code", accessCode)
                    if (prefs.actAsUserId.isNotEmpty()) put("act_as_user_id", prefs.actAsUserId)
                    put("alert_settings", mapToJsonObject(alertSettings))
                    put("gmail_config", mapToJsonObject(gmailConfig))
                }
                val body = bodyObj.toString().toRequestBody("application/json".toMediaType())

                val urls = listOf(
                    "$base/admin/alert_settings",
                    "$base/admin/settings"
                )

                var success = false
                for (url in urls) {
                    try {
                        val req = Request.Builder().url(url).put(body).build()
                        val res = http.newCall(req).execute()
                        if (res.isSuccessful) {
                            success = true
                            break
                        }
                    } catch (_: Exception) {}
                }

                activity?.runOnUiThread {
                    if (success) {
                        CuraxFeedback.success(this, showSuccess)
                    } else {
                        CuraxFeedback.warn(this, "Saved locally. Sync will retry automatically")
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    CuraxFeedback.warn(this, "Saved locally. Sync will retry automatically")
                }
            }
        }.start()
    }

    private fun collectSettingsFromUi(): Map<String, Any?> {
        val medicineAlerts = mapOf(
            "30_min_before" to (view?.findViewById<CheckBox>(R.id.cb_medicine_30_before)?.isChecked == true),
            "15_min_before" to (view?.findViewById<CheckBox>(R.id.cb_medicine_15_before)?.isChecked == true),
            "exact_time" to (view?.findViewById<CheckBox>(R.id.cb_medicine_exact)?.isChecked == true),
            "snooze_duration" to (view?.findViewById<EditText>(R.id.et_medicine_snooze)?.text?.toString()?.trim()?.toIntOrNull() ?: 5)
        )

        val missedEsc = mapOf(
            "5_min_reminder" to (view?.findViewById<CheckBox>(R.id.cb_missed_5_min)?.isChecked == true),
            "15_min_urgent" to (view?.findViewById<CheckBox>(R.id.cb_missed_15_urgent)?.isChecked == true),
            "30_min_family" to (view?.findViewById<CheckBox>(R.id.cb_missed_30_family)?.isChecked == true),
            "1_hour_log" to (view?.findViewById<CheckBox>(R.id.cb_missed_1hr_log)?.isChecked == true),
            "family_email" to view?.findViewById<EditText>(R.id.et_missed_family_email)?.text?.toString()?.trim().orEmpty()
        )

        val stockAlerts = mapOf(
            "enabled" to (view?.findViewById<CheckBox>(R.id.cb_stock_enabled)?.isChecked == true),
            "low_stock_threshold" to (view?.findViewById<EditText>(R.id.et_stock_threshold)?.text?.toString()?.trim()?.toIntOrNull() ?: 5),
            "empty_alert" to (view?.findViewById<CheckBox>(R.id.cb_stock_empty)?.isChecked == true),
            "critical_alert" to (view?.findViewById<CheckBox>(R.id.cb_stock_critical)?.isChecked == true)
        )

        val expiryAlerts = mapOf(
            "30_days_before" to (view?.findViewById<CheckBox>(R.id.cb_expiry_30)?.isChecked == true),
            "15_days_before" to (view?.findViewById<CheckBox>(R.id.cb_expiry_15)?.isChecked == true),
            "7_days_before" to (view?.findViewById<CheckBox>(R.id.cb_expiry_7)?.isChecked == true),
            "1_day_before" to (view?.findViewById<CheckBox>(R.id.cb_expiry_1)?.isChecked == true)
        )

        val alertSettings = mapOf(
            "medicine_alerts" to medicineAlerts,
            "missed_dose_escalation" to missedEsc,
            "stock_alerts" to stockAlerts,
            "expiry_alerts" to expiryAlerts
        )

        val gmailConfig = mapOf(
            "sender_email" to view?.findViewById<EditText>(R.id.et_gmail_sender)?.text?.toString()?.trim().orEmpty(),
            "sender_password" to view?.findViewById<EditText>(R.id.et_gmail_code)?.text?.toString()?.trim().orEmpty(),
            "recipients" to view?.findViewById<EditText>(R.id.et_gmail_recipients)?.text?.toString()?.trim().orEmpty()
        )

        return mapOf(
            "alert_settings" to alertSettings,
            "gmail_config" to gmailConfig
        )
    }

    private fun mapToJsonObject(m: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) {
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is Number -> o.put(k, v)
                is Boolean -> o.put(k, v)
                is String -> o.put(k, v)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    o.put(k, mapToJsonObject(v as Map<String, Any?>))
                }
                else -> o.put(k, v.toString())
            }
        }
        return o
    }
}
