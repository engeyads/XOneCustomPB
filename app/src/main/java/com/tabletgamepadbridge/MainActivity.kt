package com.tabletgamepadbridge

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tabletgamepadbridge.adb.PairingProgress
import com.tabletgamepadbridge.adb.WirelessAdbHelperService
import java.util.Locale

/**
 * Reads the controller via USB-OTG (GIP protocol), then re-emits it as a
 * real virtual USB gamepad (via /dev/uhid) with real-time live input metrics.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var controllerVisualizer: GamepadVisualizerView
    private lateinit var connectedBadge: TextView
    private lateinit var helperStatusText: TextView
    private lateinit var wirelessStatusText: TextView
    private lateinit var batteryText: TextView
    private lateinit var latencyText: TextView
    private lateinit var pollText: TextView
    private lateinit var deadzoneText: TextView
    private lateinit var deadzoneProgressBar: ProgressBar
    private lateinit var leftStickCoords: TextView
    private lateinit var rightStickCoords: TextView
    private lateinit var leftTriggerValue: TextView
    private lateinit var rightTriggerValue: TextView
    private lateinit var lastInputText: TextView
    private lateinit var connectBtn: Button
    private lateinit var recenterBtn: Button

    private var usbReader: UsbGamepadReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttemptsLeft = 0
    private var lastInputName = "—"
    private var staticDeadzonePct = 27

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!WelcomeActivity.hasAgreed(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        controllerVisualizer = findViewById(R.id.controllerVisualizer)
        connectedBadge = findViewById(R.id.connectedBadge)
        helperStatusText = findViewById(R.id.helperStatusText)
        wirelessStatusText = findViewById(R.id.wirelessStatusText)
        batteryText = findViewById(R.id.batteryText)
        latencyText = findViewById(R.id.latencyText)
        pollText = findViewById(R.id.pollText)
        deadzoneText = findViewById(R.id.deadzoneText)
        deadzoneProgressBar = findViewById(R.id.deadzoneProgressBar)
        leftStickCoords = findViewById(R.id.leftStickCoords)
        rightStickCoords = findViewById(R.id.rightStickCoords)
        leftTriggerValue = findViewById(R.id.leftTriggerValue)
        rightTriggerValue = findViewById(R.id.rightTriggerValue)
        lastInputText = findViewById(R.id.lastInputText)
        connectBtn = findViewById(R.id.connectBtn)
        recenterBtn = findViewById(R.id.recenterBtn)

        val openDevSettingsBtn = findViewById<Button>(R.id.openDevSettingsBtn)
        val agreementBtn = findViewById<Button>(R.id.agreementBtn)

        openDevSettingsBtn.setOnClickListener {
            startFloatingPairingOrSettings()
        }

        agreementBtn.setOnClickListener {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        connectBtn.setOnClickListener {
            connectEverything()
        }

        recenterBtn.setOnClickListener {
            controllerVisualizer.updateState(GamepadState())
            leftStickCoords.text = "+0.00 , +0.00"
            rightStickCoords.text = "+0.00 , +0.00"
            leftTriggerValue.text = "0%"
            rightTriggerValue.text = "0%"
            deadzoneText.text = "$staticDeadzonePct%"
            deadzoneProgressBar.progress = staticDeadzonePct
            lastInputText.text = "—"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WirelessAdbHelperService.onProgress = { progress ->
                runOnUiThread { handleWirelessProgress(progress) }
            }
        }

        UhidBinderProvider.onBinderReceived = {
            runOnUiThread {
                if (isHelperConnected()) {
                    updateHelperUI(true)
                    startUsbReader()
                }
            }
        }

        updateHelperUI(isHelperConnected())
    }

    private fun startFloatingPairingOrSettings() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Display Over Other Apps Needed")
                .setMessage("To type your 6-digit Wireless Debugging code directly over Settings, please enable 'Display over other apps' for JoyBridge.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    openWirelessDebuggingSettings()
                }
                .show()
        } else {
            FloatingPairingOverlayService.show(this)
            openWirelessDebuggingSettings()
        }
    }

    private fun openWirelessDebuggingSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun updateHelperUI(connected: Boolean) {
        if (connected) {
            helperStatusText.text = "CONNECTED"
            helperStatusText.setTextColor(Color.parseColor("#1B8230"))
        } else {
            helperStatusText.text = "NOT CONNECTED"
            helperStatusText.setTextColor(Color.parseColor("#C83232"))
        }
    }

    private fun connectEverything() {
        if (isHelperConnected()) {
            startUsbReader()
            return
        }

        connectBtn.isEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wirelessStatusText.text = "Searching for Wireless Debugging…"
            WirelessAdbHelperService.reconnect(this)
        } else {
            wirelessStatusText.text = "Helper not running (requires Android 11+)"
            connectBtn.isEnabled = true
        }
    }

    private fun showPairingDialog() {
        startFloatingPairingOrSettings()
    }

    private fun handleWirelessProgress(progress: PairingProgress) {
        wirelessStatusText.text = when (progress) {
            is PairingProgress.DiscoveringPairingService -> "Looking for pairing service…"
            is PairingProgress.Pairing -> "Pairing…"
            is PairingProgress.PairingFailed -> {
                connectBtn.isEnabled = true
                "Pairing failed: ${progress.message}"
            }
            is PairingProgress.DiscoveringConnectService -> "Searching for Wireless Debugging…"
            is PairingProgress.Connecting -> "Authenticating with Wireless Debugging…"
            is PairingProgress.StartingHelper -> "Starting helper process…"
            is PairingProgress.Done -> {
                connectBtn.isEnabled = true
                reconnectHelper()
                "Helper started ✓"
            }
            is PairingProgress.Failed -> {
                connectBtn.isEnabled = true
                if (progress.needsPairing) {
                    showPairingDialog()
                }
                progress.message
            }
        }
    }

    private fun isHelperConnected(): Boolean {
        val service = UhidBinderProvider.receivedService
        return service != null && service.asBinder().pingBinder()
    }

    private fun reconnectHelper() {
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttemptsLeft = 16
        pollHelperConnection()
    }

    private fun pollHelperConnection() {
        val connected = isHelperConnected()
        updateHelperUI(connected)
        if (connected) {
            startUsbReader()
            return
        }
        if (reconnectAttemptsLeft <= 0) {
            wirelessStatusText.text = "Couldn't connect to helper"
            connectBtn.isEnabled = true
            return
        }
        reconnectAttemptsLeft--
        mainHandler.postDelayed({ pollHelperConnection() }, 500)
    }

    private fun startUsbReader() {
        updateHelperUI(true)
        wirelessStatusText.text = "Helper active"
        connectBtn.isEnabled = true

        usbReader?.stop()
        usbReader = UsbGamepadReader(this) { state ->
            UhidBinderProvider.receivedService?.let {
                try {
                    it.sendInput(GamepadHidReport.build(state))
                } catch (_: Exception) {
                }
            }

            val lx = state.leftStickX / 32767.0f
            val ly = -state.leftStickY / 32767.0f
            val rx = state.rightStickX / 32767.0f
            val ry = -state.rightStickY / 32767.0f

            val ltPct = ((state.leftTrigger / 1023.0f) * 100).toInt().coerceIn(0, 100)
            val rtPct = ((state.rightTrigger / 1023.0f) * 100).toInt().coerceIn(0, 100)

            determineLastInput(state)

            runOnUiThread {
                controllerVisualizer.updateState(state)

                batteryText.text = "${state.batteryPercent}%"
                latencyText.text = "${state.latencyMs} ms"
                pollText.text = "${state.pollHz} Hz"

                deadzoneText.text = "$staticDeadzonePct%"
                deadzoneProgressBar.progress = staticDeadzonePct

                leftStickCoords.text = String.format(Locale.US, "%+.2f , %+.2f", lx, ly)
                rightStickCoords.text = String.format(Locale.US, "%+.2f , %+.2f", rx, ry)
                leftTriggerValue.text = "$ltPct%"
                rightTriggerValue.text = "$rtPct%"
                lastInputText.text = lastInputName

                connectedBadge.text = "CONNECTED"
                connectedBadge.setBackgroundColor(Color.parseColor("#E1F5FE"))
                connectedBadge.setTextColor(Color.parseColor("#0277BD"))
            }
        }
        usbReader?.start()
    }

    private fun determineLastInput(state: GamepadState) {
        when {
            state.a -> lastInputName = "BUTTON_A"
            state.b -> lastInputName = "BUTTON_B"
            state.x -> lastInputName = "BUTTON_X"
            state.y -> lastInputName = "BUTTON_Y"
            state.leftBumper -> lastInputName = "BUMPER_L"
            state.rightBumper -> lastInputName = "BUMPER_R"
            state.guide -> lastInputName = "BUTTON_GUIDE"
            state.start -> lastInputName = "START"
            state.back -> lastInputName = "BACK"
            state.dpadUp -> lastInputName = "DPAD_UP"
            state.dpadDown -> lastInputName = "DPAD_DOWN"
            state.dpadLeft -> lastInputName = "DPAD_LEFT"
            state.dpadRight -> lastInputName = "DPAD_RIGHT"
            state.leftTrigger > 50 -> lastInputName = "TRIGGER_L (${((state.leftTrigger / 1023.0f) * 100).toInt()}%)"
            state.rightTrigger > 50 -> lastInputName = "TRIGGER_R (${((state.rightTrigger / 1023.0f) * 100).toInt()}%)"
            state.leftStickClick -> lastInputName = "L_THUMB_CLICK"
            state.rightStickClick -> lastInputName = "R_THUMB_CLICK"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbReader?.stop()
        UhidBinderProvider.onBinderReceived = null
        WirelessAdbHelperService.onProgress = null
        mainHandler.removeCallbacksAndMessages(null)
    }
}
