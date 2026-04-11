package com.curax.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class UserDashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_dashboard)

        val tvGreeting = findViewById<TextView>(R.id.tvUserGreeting)
        val email = LocalUserStore(this).email
        tvGreeting.text = "Welcome, $email"
    }
}
