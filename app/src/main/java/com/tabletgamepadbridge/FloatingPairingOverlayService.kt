package com.tabletgamepadbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.tabletgamepadbridge.adb.WirelessAdbHelperService

/**
 * Floating Overlay Window Service that displays an input box directly on top of
 * System Settings. This allows the user to see the 6-digit Wireless Debugging code
 * in Settings and type it into JoyBridge simultaneously on tablets that disable split-screen.
 */
class FloatingPairingOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_overlay_channel"
        private const val NOTIF_ID = 888

        fun show(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Settings.canDrawOverlays(context)) {
                val intent = Intent(context, FloatingPairingOverlayService::class.java)
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingPairingOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Floating Pairing Overlay", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (Settings.canDrawOverlays(this)) {
            showOverlayWindow()
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun showOverlayWindow() {
        if (overlayView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.floating_pairing_overlay, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        val header = overlayView!!.findViewById<View>(R.id.overlayHeader)
        val closeBtn = overlayView!!.findViewById<TextView>(R.id.closeOverlayBtn)
        val codeInput = overlayView!!.findViewById<EditText>(R.id.overlayCodeInput)
        val settingsBtn = overlayView!!.findViewById<Button>(R.id.overlaySettingsBtn)
        val pairBtn = overlayView!!.findViewById<Button>(R.id.overlayPairBtn)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        closeBtn.setOnClickListener {
            removeOverlay()
            stopSelf()
        }

        settingsBtn.setOnClickListener {
            openWirelessDebuggingSettings()
        }

        pairBtn.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.length == 6) {
                WirelessAdbHelperService.pair(this, code)
                Toast.makeText(this, "Pairing code sent! Connecting…", Toast.LENGTH_SHORT).show()
                removeOverlay()
                stopSelf()
            } else {
                Toast.makeText(this, "Please enter 6 digits", Toast.LENGTH_SHORT).show()
            }
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openWirelessDebuggingSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JoyBridge Floating Pairing")
            .setContentText("Floating input active over Settings")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
