package com.tabletgamepadbridge

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tabletgamepadbridge.adb.PairingProgress
import com.tabletgamepadbridge.adb.WirelessAdbHelperService

/**
 * Reads the controller via USB-OTG (GIP protocol), then re-emits it as a
 * real virtual USB gamepad (via /dev/uhid) so any app - not just one we've
 * hardcoded - sees it as a genuine system gamepad, no root required.
 *
 * The single "Connect Controller" button drives the whole flow
 * automatically: if the privileged helper is already running, it just
 * starts reading the controller; otherwise it silently reconnects to (or,
 * only the very first time on a device, pairs with) Android's own Wireless
 * Debugging feature to (re)start the helper - no manual steps for daily use,
 * since the one-time pairing is a setup task for a parent/adult, not
 * something a child using the controller ever needs to see.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var wirelessStatusText: TextView
    private lateinit var connectBtn: Button
    private var usbReader: UsbGamepadReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttemptsLeft = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!WelcomeActivity.hasAgreed(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        wirelessStatusText = findViewById(R.id.wirelessStatusText)
        connectBtn = findViewById(R.id.connectBtn)
        val openAccessibilityBtn = findViewById<Button>(R.id.openAccessibilityBtn)

        openAccessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        connectBtn.setOnClickListener {
            connectEverything()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WirelessAdbHelperService.onProgress = { progress ->
                runOnUiThread { handleWirelessProgress(progress) }
            }
        }

        UhidBinderProvider.onBinderReceived = {
            runOnUiThread {
                if (isHelperConnected()) {
                    startUsbReader()
                }
            }
        }
    }

    /**
     * The one thing the everyday user (a child, most likely) ever taps.
     * Tries the fully-automatic path first; only falls back to asking for a
     * pairing code if this exact device has genuinely never been paired
     * before (a one-time setup task, not something that recurs).
     */
    private fun connectEverything() {
        if (isHelperConnected()) {
            startUsbReader()
            return
        }

        connectBtn.isEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wirelessStatusText.text = "Connecting…"
            WirelessAdbHelperService.reconnect(this)
        } else {
            wirelessStatusText.text = "Privileged helper not running (requires Android 11+ to auto-start)"
            connectBtn.isEnabled = true
        }
    }

    private fun showPairingDialog() {
        val input = EditText(this).apply {
            hint = "6-digit pairing code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("One-time setup needed")
            .setCancelable(false)
            .setMessage(
                "This device hasn't been set up yet. In Settings → Developer options " +
                    "→ Wireless debugging, tap \"Pair device with pairing code\", then " +
                    "enter the code shown here. You'll only need to do this once."
            )
            .setView(input)
            .setPositiveButton("Pair") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    wirelessStatusText.text = "Pairing…"
                    WirelessAdbHelperService.pair(this, code)
                } else {
                    connectBtn.isEnabled = true
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                connectBtn.isEnabled = true
            }
            .show()
    }

    private fun handleWirelessProgress(progress: PairingProgress) {
        wirelessStatusText.text = when (progress) {
            is PairingProgress.DiscoveringPairingService -> "Looking for pairing service…"
            is PairingProgress.Pairing -> "Pairing…"
            is PairingProgress.PairingFailed -> {
                connectBtn.isEnabled = true
                "Pairing failed: ${progress.message}"
            }
            is PairingProgress.DiscoveringConnectService -> "Connecting…"
            is PairingProgress.Connecting -> "Connecting…"
            is PairingProgress.StartingHelper -> "Almost there…"
            is PairingProgress.Done -> {
                connectBtn.isEnabled = true
                reconnectHelper()
                ""
            }
            is PairingProgress.Failed -> {
                // Reconnect only fails like this when this device has never
                // been paired before - a one-time setup step.
                showPairingDialog()
                "Setting up for the first time…"
            }
        }
    }

    private fun isHelperConnected(): Boolean {
        val service = UhidBinderProvider.receivedService
        return service != null && service.asBinder().pingBinder()
    }

    /**
     * The app can only receive the helper's Binder (pushed every 5s from the
     * privileged process) - it has no way to reach out and request one on
     * demand, so "reconnecting" really means: wait for the next automatic
     * push, then start reading the controller once it arrives.
     */
    private fun reconnectHelper() {
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttemptsLeft = 16 // ~8 seconds at 500ms, more than one 5s push cycle
        pollHelperConnection()
    }

    private fun pollHelperConnection() {
        if (isHelperConnected()) {
            startUsbReader()
            return
        }
        if (reconnectAttemptsLeft <= 0) {
            wirelessStatusText.text = "Couldn't connect to the helper - try again"
            connectBtn.isEnabled = true
            return
        }
        reconnectAttemptsLeft--
        mainHandler.postDelayed({ pollHelperConnection() }, 500)
    }

    private fun startUsbReader() {
        wirelessStatusText.text = "Helper ready ✓"
        connectBtn.isEnabled = true

        usbReader?.stop()
        usbReader = UsbGamepadReader(this) { state ->
            UhidBinderProvider.receivedService?.let {
                try {
                    it.sendInput(GamepadHidReport.build(state))
                } catch (e: Exception) {
                    // transient binder failure; ignore
                }
            }
            runOnUiThread {
                statusText.text = buildString {
                    append("A=${state.a} B=${state.b} X=${state.x} Y=${state.y}\n")
                    append("DPad U=${state.dpadUp} D=${state.dpadDown} L=${state.dpadLeft} R=${state.dpadRight}\n")
                    append("LB=${state.leftBumper} RB=${state.rightBumper} Guide=${state.guide}\n")
                    append("Start=${state.start} Back=${state.back}\n")
                    append("LT=${state.leftTrigger} RT=${state.rightTrigger}\n")
                    append("LStick=(${state.leftStickX},${state.leftStickY}) click=${state.leftStickClick}\n")
                    append("RStick=(${state.rightStickX},${state.rightStickY}) click=${state.rightStickClick}")
                }
            }
        }
        usbReader?.start()
        statusText.text = "Looking for controller... (plug it in via OTG if not already)"
    }

    override fun onDestroy() {
        super.onDestroy()
        usbReader?.stop()
        UhidBinderProvider.onBinderReceived = null
        WirelessAdbHelperService.onProgress = null
        mainHandler.removeCallbacksAndMessages(null)
    }
}
