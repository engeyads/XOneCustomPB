package com.tabletgamepadbridge

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run explainer page. Shown once, before MainActivity, so whoever
 * uses the device (often a child) never has to make sense of what the app
 * is doing - a parent/adult reads and agrees to this once, and everything
 * after that is a single button on the main screen.
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "joybridge_consent"
        private const val KEY_AGREED = "agreed"

        fun hasAgreed(context: android.content.Context): Boolean {
            return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AGREED, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        val agreeCheckbox = findViewById<CheckBox>(R.id.agreeCheckbox)
        val startBtn = findViewById<Button>(R.id.startBtn)

        agreeCheckbox.setOnCheckedChangeListener { _, checked ->
            startBtn.isEnabled = checked
        }

        startBtn.setOnClickListener {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AGREED, true).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
