package com.tabletgamepadbridge

import android.app.Application
import android.os.Build
import com.google.android.gms.ads.MobileAds
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Application class initializing Hidden API bypass and Google Mobile Ads SDK.
 */
class JoyBridgeApplication : Application() {
    companion object {
        init {
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        MobileAds.initialize(this) {}
    }
}
