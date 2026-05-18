package com.curax.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class ReliabilityCautionsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reliability_cautions)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.reliability_caution_title)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val root = findViewById<android.widget.FrameLayout>(R.id.cautionsContentRoot)
        layoutInflater.inflate(R.layout.include_sidebar_reliability_cautions, root, true)
        ReliabilityCautionsUi.bind(this)
    }
}
