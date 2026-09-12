package com.tabletgamepadbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run explainer page with custom electric blue theme and GitHub repository link.
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "joybridge_consent"
        private const val KEY_AGREED = "agreed"
        private const val GITHUB_URL = "https://github.com/engeyads/XOneCustomPB"

        fun hasAgreed(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AGREED, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        val agreeCheckbox = findViewById<CheckBox>(R.id.agreeCheckbox)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val githubLinkText = findViewById<TextView>(R.id.githubLinkText)

        githubLinkText.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
            startActivity(intent)
        }

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
