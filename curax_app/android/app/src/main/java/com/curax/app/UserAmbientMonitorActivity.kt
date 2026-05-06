package com.curax.app



import android.os.Bundle

import android.os.Handler

import android.os.Looper

import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity

import com.google.android.material.appbar.MaterialToolbar



/**

 * Default-mode user shell: live ambient readings (hardware-backed later). Demo stream until BLE/USB is wired.

 */

class UserAmbientMonitorActivity : AppCompatActivity() {



    private val mainHandler = Handler(Looper.getMainLooper())

    private var tickSeq = 0

    private val tickRunnable = object : Runnable {

        override fun run() {

            tickSeq += 1

            val f = AmbientDemoReadout.format(this@UserAmbientMonitorActivity, tickSeq)

            tvTemp1.text = f.tempZone1

            tvTemp2.text = f.tempZone2

            tvHumidity.text = f.humidity

            tvUpdated.text = f.updatedLine

            mainHandler.postDelayed(this, 2000L)

        }

    }



    private lateinit var tvTemp1: TextView

    private lateinit var tvTemp2: TextView

    private lateinit var tvHumidity: TextView

    private lateinit var tvUpdated: TextView



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_user_ambient_monitor)



        findViewById<MaterialToolbar>(R.id.toolbarAmbient).setNavigationOnClickListener {

            onBackPressedDispatcher.onBackPressed()

        }



        tvTemp1 = findViewById(R.id.tvAmbientTemp1)

        tvTemp2 = findViewById(R.id.tvAmbientTemp2)

        tvHumidity = findViewById(R.id.tvAmbientHumidity)

        tvUpdated = findViewById(R.id.tvAmbientUpdated)

    }



    override fun onStart() {

        super.onStart()

        mainHandler.removeCallbacks(tickRunnable)

        mainHandler.post(tickRunnable)

    }



    override fun onStop() {

        mainHandler.removeCallbacks(tickRunnable)

        super.onStop()

    }

}

