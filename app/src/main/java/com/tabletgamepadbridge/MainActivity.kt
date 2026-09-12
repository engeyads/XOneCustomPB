package com.tabletgamepadbridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Reads the controller via USB-OTG (GIP protocol), then re-emits it as a
 * real virtual USB gamepad (via /dev/uhid) so any app - not just one we've
 * hardcoded - sees it as a genuine system gamepad, no root required.
 *
 * The /dev/uhid access is provided by PrivilegedMain, a self-contained
 * helper (no separate app, no Shizuku) started once via `adb shell
 * app_process` with shell privilege. It hands its Binder over to this app's
 * own UhidBinderProvider using the same ContentProvider-based mechanism
 * Shizuku itself relies on to get past the SELinux wall that blocks plain
 * socket connects between "shell" and app processes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var helperStatusText: TextView
    private var usbReader: UsbGamepadReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        helperStatusText = findViewById(R.id.shizukuStatusText)
        val openAccessibilityBtn = findViewById<Button>(R.id.openAccessibilityBtn)
        val connectBtn = findViewById<Button>(R.id.connectBtn)
        val checkHelperBtn = findViewById<Button>(R.id.enableShizukuBtn)
        checkHelperBtn.text = "Check Privileged Helper"

        openAccessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        connectBtn.setOnClickListener {
            startUsbReader()
        }

        checkHelperBtn.setOnClickListener {
            updateHelperStatus()
        }

        UhidBinderProvider.onBinderReceived = {
            runOnUiThread { updateHelperStatus() }
        }
        updateHelperStatus()
    }

    private fun updateHelperStatus() {
        val service = UhidBinderProvider.receivedService
        helperStatusText.text = if (service != null && service.asBinder().pingBinder()) {
            "Privileged helper: connected ✓"
        } else {
            "Privileged helper: not connected yet"
        }
    }

    private fun startUsbReader() {
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
    }
}
