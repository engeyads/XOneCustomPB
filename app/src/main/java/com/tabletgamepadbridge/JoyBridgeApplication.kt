package com.tabletgamepadbridge

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Unlocks access to hidden/internal Android APIs (specifically
 * com.android.org.conscrypt.Conscrypt, needed for the wireless-debugging
 * pairing handshake's key-material export) - same approach Shizuku's own
 * app uses (github.com/RikkaApps/Shizuku, GPL-3.0).
 */
class JoyBridgeApplication : Application() {
    companion object {
        init {
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
        }
    }
}
