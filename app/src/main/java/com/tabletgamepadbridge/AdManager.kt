package com.tabletgamepadbridge

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

private const val TAG = "AdManager"

/**
 * Manages periodic 15-second Interstitial Ads for free users (eas2012@gmail.com AdMob account).
 * Stops immediately when the user purchases the $5 USD Pro version.
 */
class AdManager(
    private val activity: Activity,
    private val isProPurchased: () -> Boolean
) {
    companion object {
        // REPLACE WITH YOUR REAL ADMOB INTERSTITIAL AD UNIT ID FROM eas2012@gmail.com ACCOUNT
        const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        private const val AD_INTERVAL_MS = 15_000L // 15 seconds
        private const val MAX_AUTO_ADS = 3
    }

    private var interstitialAd: InterstitialAd? = null
    private val handler = Handler(Looper.getMainLooper())
    private var adsShownCount = 0
    private var isAdLoading = false

    private val adLoopRunnable = object : Runnable {
        override fun run() {
            if (isProPurchased()) {
                stopAdLoop()
                return
            }

            if (adsShownCount < MAX_AUTO_ADS) {
                showOrLoadAd()
                handler.postDelayed(this, AD_INTERVAL_MS)
            }
        }
    }

    fun startAdLoop() {
        if (isProPurchased()) return
        preloadAd()
        handler.postDelayed(adLoopRunnable, AD_INTERVAL_MS)
    }

    fun stopAdLoop() {
        handler.removeCallbacks(adLoopRunnable)
        interstitialAd = null
    }

    private fun preloadAd() {
        if (isProPurchased() || isAdLoading || interstitialAd != null) return

        isAdLoading = true
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            activity,
            INTERSTITIAL_AD_UNIT_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isAdLoading = false
                    Log.i(TAG, "Interstitial Ad loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isAdLoading = false
                    Log.w(TAG, "Interstitial Ad failed to load: ${error.message}")
                }
            }
        )
    }

    private fun showOrLoadAd() {
        if (isProPurchased()) return

        val ad = interstitialAd
        if (ad != null) {
            ad.show(activity)
            interstitialAd = null
            adsShownCount++
            preloadAd()
        } else {
            preloadAd()
        }
    }
}
