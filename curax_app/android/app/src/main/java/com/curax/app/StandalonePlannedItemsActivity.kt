package com.curax.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.util.Calendar
import java.util.Locale

class StandalonePlannedItemsActivity : AppCompatActivity() {

    private lateinit var llPlanList: LinearLayout
    private lateinit var etTitle: TextInputEditText
    private lateinit var etNotes: TextInputEditText
    private lateinit var btnDate: MaterialButton
    private lateinit var btnTime: MaterialButton
    private lateinit var actActivity: MaterialAutoCompleteTextView
    private lateinit var btnSave: MaterialButton

    private var pickedDate: String = ""
    private var pickedTime: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planned_items)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        llPlanList = findViewById(R.id.llPlanList)
        etTitle = findViewById(R.id.etPlanTitle)
        etNotes = findViewById(R.id.etPlanNotes)
        btnDate = findViewById(R.id.btnPlanDate)
        btnTime = findViewById(R.id.btnPlanTime)
        actActivity = findViewById(R.id.actPlanActivity)
        btnSave = findViewById(R.id.btnSavePlan)

        val cal = Calendar.getInstance()
        pickedDate = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
        btnDate.text = pickedDate
        btnTime.text = getString(R.string.plan_pick_time)

        val acts = resources.getStringArray(R.array.plan_activity_types)
        actActivity.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, acts),
        )
        actActivity.setText(acts[0], false)

        btnDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    pickedDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                    btnDate.text = pickedDate
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        }
        btnTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, min ->
                    pickedTime = String.format(Locale.US, "%02d:%02d:00", h, min)
                    btnTime.text = pickedTime.substring(0, 5)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true,
            ).show()
        }
        btnSave.setOnClickListener { savePlan() }
    }

    override fun onResume() {
        super.onResume()
        reloadPlans()
    }

    private fun savePlan() {
        val title = etTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.plan_field_title))
            return
        }
        val notes = etNotes.text?.toString()?.trim().orEmpty()
        val act = actActivity.text?.toString()?.trim().orEmpty().ifEmpty { "Other" }
        val timePart = pickedTime.trim()
        btnSave.isEnabled = false
        Thread {
            val (ok, err) = UserPlansApi.createPlan(this, title, notes, pickedDate, timePart, act)
            runOnUiThread {
                btnSave.isEnabled = true
                if (ok) {
                    CuraxFeedback.success(this, getString(R.string.plan_saved))
                    etTitle.text?.clear()
                    etNotes.text?.clear()
                    pickedTime = ""
                    btnTime.text = getString(R.string.plan_pick_time)
                    reloadPlans()
                } else {
                    CuraxFeedback.warn(this, getString(R.string.plan_save_failed) + (err?.let { ": $it" } ?: ""))
                }
            }
        }.start()
    }

    private fun reloadPlans() {
        Thread {
            val (list, _) = UserPlansApi.fetchPlans(this)
            runOnUiThread { renderPlanList(list) }
        }.start()
    }

    private fun renderPlanList(plans: List<UserPlanRow>) {
        llPlanList.removeAllViews()
        if (plans.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.plan_list_empty)
                setPadding(8, 16, 8, 8)
                setTextColor(ContextCompat.getColor(this@StandalonePlannedItemsActivity, R.color.text_secondary))
            }
            llPlanList.addView(tv)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (p in plans) {
            val card = inflater.inflate(R.layout.item_plan_row, llPlanList, false)
            card.findViewById<TextView>(R.id.tvPlanTitle).text = p.title
            val meta = buildString {
                append(p.planDate)
                if (p.planTime.isNotBlank()) append(" · ").append(p.planTime.take(5))
                append(" · ").append(p.activityType)
            }
            card.findViewById<TextView>(R.id.tvPlanMeta).text = meta
            val n = card.findViewById<TextView>(R.id.tvPlanNotes)
            if (p.notes.isNotBlank()) {
                n.visibility = View.VISIBLE
                n.text = p.notes
            }
            llPlanList.addView(card)
        }
    }
}
