package com.tabletgamepadbridge.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

/**
 * Runs the wireless-debugging pair/connect/start flow as a foreground
 * service so it survives the user switching away to read the pairing code
 * in Settings - a plain Activity-scoped call gets throttled/killed the
 * moment the app is backgrounded, since it isn't in the foreground anymore.
 */
@RequiresApi(Build.VERSION_CODES.R)
class WirelessAdbHelperService : Service() {

    companion object {
        private const val CHANNEL_ID = "wireless_adb_helper"
        private const val NOTIFICATION_ID = 42

        const val ACTION_PAIR = "com.tabletgamepadbridge.adb.action.PAIR"
        const val ACTION_RECONNECT = "com.tabletgamepadbridge.adb.action.RECONNECT"
        const val EXTRA_CODE = "code"

        @Volatile
        var onProgress: ((PairingProgress) -> Unit)? = null

        fun pair(context: Context, code: String) {
            val intent = Intent(context, WirelessAdbHelperService::class.java)
                .setAction(ACTION_PAIR)
                .putExtra(EXTRA_CODE, code)
            context.startForegroundService(intent)
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, WirelessAdbHelperService::class.java)
                .setAction(ACTION_RECONNECT)
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Wireless helper setup", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Setting up controller helper…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val starter = WirelessAdbHelperStarter(applicationContext)
        val callback: (PairingProgress) -> Unit = { progress ->
            onProgress?.invoke(progress)
            updateNotification(progress)
            if (progress is PairingProgress.Done || progress is PairingProgress.Failed ||
                progress is PairingProgress.PairingFailed
            ) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        when (intent?.action) {
            ACTION_PAIR -> {
                val code = intent.getStringExtra(EXTRA_CODE)
                if (code != null) {
                    starter.pairAndStart(code, callback)
                } else {
                    stopSelf(startId)
                }
            }
            ACTION_RECONNECT -> starter.reconnectAndStart(callback)
            else -> stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JoyBridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: PairingProgress) {
        val text = when (progress) {
            is PairingProgress.DiscoveringPairingService -> "Looking for pairing service…"
            is PairingProgress.Pairing -> "Pairing…"
            is PairingProgress.PairingFailed -> "Pairing failed: ${progress.message}"
            is PairingProgress.DiscoveringConnectService -> "Looking for connect service…"
            is PairingProgress.Connecting -> "Connecting…"
            is PairingProgress.StartingHelper -> "Starting privileged helper…"
            is PairingProgress.Done -> "Helper started"
            is PairingProgress.Failed -> "Failed: ${progress.message}"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
