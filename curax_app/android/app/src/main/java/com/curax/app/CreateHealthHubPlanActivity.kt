package com.curax.app

import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Full-screen plan creation (opened from Health Hub planned list +). Keeps the plans sheet
 * stable underneath like other hub flows — no nested bottom sheet.
 *
 * Date/time pickers follow the same UX as admin Add medicine and Medical reminders (Calendar +
 * Set time rows, default Material dialogs — no custom window sizing).
 */
class CreateHealthHubPlanActivity : AppCompatActivity() {

    private val dateFormatUtc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!StandaloneUserMutationGate.warnIfBlocked(this)) {
            finish()
            return
        }
        setContentView(R.layout.activity_create_health_hub_plan)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarCreatePlan)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val actHealth = findViewById<MaterialAutoCompleteTextView>(R.id.actPlanHealthType)
        val chipGroup = findViewById<ChipGroup>(R.id.chipGroupPlanSuggestions)
        val etTitle = findViewById<TextInputEditText>(R.id.etCreatePlanTitle)
        val etNotes = findViewById<TextInputEditText>(R.id.etCreatePlanNotes)
        val tvDateDisplay = findViewById<TextView>(R.id.tvCreatePlanDateDisplay)
        val btnCalendar = findViewById<MaterialButton>(R.id.btnCreatePlanCalendar)
        val tvTimeDisplay = findViewById<TextView>(R.id.tvCreatePlanTimeDisplay)
        val btnSetTime = findViewById<MaterialButton>(R.id.btnCreatePlanSetTime)
        val actActivity = findViewById<MaterialAutoCompleteTextView>(R.id.actCreatePlanActivity)
        val btnSave = findViewById<MaterialButton>(R.id.btnCreatePlanSave)

        var selectedHealthSlug = "general"
        val healthLabels = HealthHubPlanTemplates.labels(this)
        actHealth.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, healthLabels))
        actHealth.setText(HealthHubPlanTemplates.labelForSlug(this, selectedHealthSlug), false)

        fun refillSuggestionChips() {
            chipGroup.removeAllViews()
            for ((t, n) in HealthHubPlanTemplates.suggestions(this, selectedHealthSlug)) {
                val chip = Chip(this)
                chip.text = t
                chip.isCheckable = false
                chip.setOnClickListener {
                    etTitle.setText(t)
                    etNotes.setText(n)
                }
                chipGroup.addView(chip)
            }
        }

        actHealth.setOnItemClickListener { _, _, position, _ ->
            selectedHealthSlug = HealthHubPlanTemplates.ORDER.getOrNull(position) ?: "general"
            refillSuggestionChips()
        }
        refillSuggestionChips()

        val calNow = Calendar.getInstance()
        var pickedDate = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calNow.get(Calendar.YEAR),
            calNow.get(Calendar.MONTH) + 1,
            calNow.get(Calendar.DAY_OF_MONTH),
        )
        /** HH:mm:ss for API (same shape as previous create-plan flow). */
        var pickedTime = "08:00:00"

        tvDateDisplay.text = getString(R.string.plan_create_date_label, pickedDate)
        tvTimeDisplay.text = getString(R.string.plan_create_time_label, pickedTime.substring(0, 5))

        val acts = resources.getStringArray(R.array.plan_activity_types)
        actActivity.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, acts))
        actActivity.setText(acts[0], false)

        btnCalendar.setOnClickListener {
            val selCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            try {
                val parsed = dateFormatUtc.parse(pickedDate)
                if (parsed != null) selCal.time = parsed
            } catch (_: Exception) {
                selCal.timeInMillis = MaterialDatePicker.todayInUtcMilliseconds()
            }
            val picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(selCal.timeInMillis)
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
                pickedDate = String.format(
                    Locale.US,
                    "%04d-%02d-%02d",
                    c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH),
                )
                tvDateDisplay.text = getString(R.string.plan_create_date_label, pickedDate)
            }
            picker.show(supportFragmentManager, "create_health_hub_plan_date")
        }

        btnSetTime.setOnClickListener {
            val parts = pickedTime.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val tp = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .build()
            tp.addOnPositiveButtonClickListener {
                pickedTime = String.format(
                    Locale.US,
                    "%02d:%02d:00",
                    tp.hour,
                    tp.minute,
                )
                tvTimeDisplay.text = getString(R.string.plan_create_time_label, pickedTime.substring(0, 5))
            }
            tp.show(supportFragmentManager, "create_health_hub_plan_time")
        }

        btnSave.setOnClickListener {
            val title = etTitle.text?.toString()?.trim().orEmpty()
            if (title.isEmpty()) {
                CuraxFeedback.warn(this, getString(R.string.plan_field_title))
                return@setOnClickListener
            }
            val notes = etNotes.text?.toString()?.trim().orEmpty()
            val act = actActivity.text?.toString()?.trim().orEmpty().ifEmpty { "Other" }
            val timePart = pickedTime.trim()
            btnSave.isEnabled = false
            Thread {
                val (ok, err) = UserPlansApi.createPlan(
                    applicationContext,
                    title,
                    notes,
                    pickedDate,
                    timePart,
                    act,
                    selectedHealthSlug,
                )
                runOnUiThread {
                    btnSave.isEnabled = true
                    if (isFinishing) return@runOnUiThread
                    if (ok) {
                        StandaloneOfflineMirror.persistMergedSnapshot(applicationContext)
                        LocalAlertsController.reschedule(applicationContext)
                        CuraxFeedback.success(this, getString(R.string.plan_saved))
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        CuraxFeedback.warn(
                            this,
                            getString(R.string.plan_save_failed) + (err?.let { ": $it" } ?: ""),
                        )
                    }
                }
            }.start()
        }
    }
}
