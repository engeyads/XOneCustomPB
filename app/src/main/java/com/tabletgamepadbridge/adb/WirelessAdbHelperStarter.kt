package com.tabletgamepadbridge.adb

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WirelessAdbHelper"

sealed class PairingProgress {
    data object DiscoveringPairingService : PairingProgress()
    data object Pairing : PairingProgress()
    data class PairingFailed(val message: String) : PairingProgress()
    data object DiscoveringConnectService : PairingProgress()
    data object Connecting : PairingProgress()
    data object StartingHelper : PairingProgress()
    data object Done : PairingProgress()
    data class Failed(val message: String) : PairingProgress()
}

/**
 * Connects directly to the device's Wireless Debugging service using the pre-authenticated
 * PC adbkey (iyads@IYAD), executing the helper process automatically without asking for
 * any pairing code.
 */
@RequiresApi(Build.VERSION_CODES.R)
class WirelessAdbHelperStarter(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val key: AdbKey by lazy {
        AdbKey(PreferenceAdbKeyStore(context.getSharedPreferences("adbkey", Context.MODE_PRIVATE)), "iyads@IYAD")
    }

    private val appProcessCommand: String by lazy {
        val apkPath = context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
        "CLASSPATH=$apkPath app_process / --nice-name=tgb_privileged com.tabletgamepadbridge.PrivilegedMain"
    }

    /** Reconnects using the trusted PC key (iyads@IYAD). */
    fun reconnectAndStart(onProgress: (PairingProgress) -> Unit) {
        onProgress(PairingProgress.DiscoveringConnectService)
        discoverPort(AdbMdns.TLS_CONNECT) { port ->
            if (port <= 0) {
                onProgress(PairingProgress.Failed("Wireless debugging port not found - make sure Wireless debugging is ON in Developer options"))
                return@discoverPort
            }
            connectAndStart(port, onProgress)
        }
    }

    /** Full flow: pair using a fresh code if needed. */
    fun pairAndStart(pairingCode: String, onProgress: (PairingProgress) -> Unit) {
        onProgress(PairingProgress.DiscoveringPairingService)
        discoverPort(AdbMdns.TLS_PAIRING) { pairingPort ->
            if (pairingPort <= 0) {
                onProgress(PairingProgress.Failed("Could not find pairing service - tap \"Pair device with pairing code\" in Settings first"))
                return@discoverPort
            }

            onProgress(PairingProgress.Pairing)
            Thread {
                try {
                    AdbPairingClient("127.0.0.1", pairingPort, pairingCode, key).use { client ->
                        val ok = client.start()
                        mainHandler.post {
                            if (ok) {
                                reconnectAndStart(onProgress)
                            } else {
                                onProgress(PairingProgress.PairingFailed("Pairing rejected - check code"))
                            }
                        }
                    }
                } catch (e: AdbInvalidPairingCodeException) {
                    mainHandler.post { onProgress(PairingProgress.PairingFailed("Wrong pairing code")) }
                } catch (e: Exception) {
                    Log.e(TAG, "Pairing failed", e)
                    mainHandler.post { onProgress(PairingProgress.PairingFailed("Pairing failed: ${e.message}")) }
                }
            }.start()
        }
    }

    private fun connectAndStart(connectPort: Int, onProgress: (PairingProgress) -> Unit) {
        onProgress(PairingProgress.Connecting)
        Thread {
            try {
                Log.i(TAG, "Connecting to Wireless Debugging on 127.0.0.1:$connectPort with iyads@IYAD key")
                AdbClient("127.0.0.1", connectPort, key).use { adb ->
                    adb.connect()
                    mainHandler.post { onProgress(PairingProgress.StartingHelper) }
                    adb.shellCommand(appProcessCommand) { output ->
                        Log.d(TAG, "helper output: ${String(output)}")
                    }
                }
                mainHandler.post { onProgress(PairingProgress.Done) }
            } catch (e: Exception) {
                Log.e(TAG, "Connect/start failed", e)
                mainHandler.post { onProgress(PairingProgress.Failed("Connect failed: ${e.message}")) }
            }
        }.start()
    }

    private fun discoverPort(serviceType: String, timeoutMs: Long = 8_000, callback: (Int) -> Unit) {
        val delivered = AtomicBoolean(false)
        var mdns: AdbMdns? = null

        val timeoutRunnable = Runnable {
            if (delivered.compareAndSet(false, true)) {
                mdns?.stop()
                Log.w(TAG, "mDNS discovery timed out for $serviceType")
                callback(-1)
            }
        }

        mdns = AdbMdns(context, serviceType) { port ->
            if (port > 0 && delivered.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeoutRunnable)
                mdns?.stop()
                Log.i(TAG, "Found mDNS $serviceType on port $port")
                mainHandler.post { callback(port) }
            }
        }
        mdns.start()
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }
}
