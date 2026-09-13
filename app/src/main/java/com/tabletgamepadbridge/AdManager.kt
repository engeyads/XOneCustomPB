package com.tabletgamepadbridge

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

private const val TAG = "AdManager"

/**
 * Manages Interstitial Ads for free users (eas2012@gmail.com AdMob account):
 * - Plays 3 ads in a row on app startup & when tapping Connect.
 * - Gives 30 seconds of free usage time after each 3-ad series.
 * - Stops completely when Pro ($5 USD) is purchased.
 */
class AdManager(
    private val activity: Activity,
    private val isProPurchased: () -> Boolean
) {
    companion object {
        // REPLACE WITH YOUR REAL ADMOB INTERSTITIAL AD UNIT ID FROM eas2012@gmail.com ACCOUNT
        const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        private const val USAGE_INTERVAL_MS = 30_000L // 30 seconds of free usage between ad series
        private const val ADS_PER_SERIES = 3 // 3 ads in a row per series
    }

    private var interstitialAd: InterstitialAd? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isAdLoading = false
    private var adsInCurrentSeriesCount = 0
    private var isSeriesPlaying = false

    private val usageTimerRunnable = Runnable {
        if (isProPurchased() || isSeriesPlaying) return@Runnable
        Log.i(TAG, "30-second usage timer ended. Starting 3-ad series.")
        startAdSeries()
    }

    fun startAdLoop() {
        if (isProPurchased()) return
        preloadAd()
        // Play 3-ad series on app startup
        handler.postDelayed({ startAdSeries() }, 1_500L)
    }

    fun onConnectTapped() {
        if (isProPurchased() || isSeriesPlaying) return
        Log.i(TAG, "Connect tapped - triggering 3-ad series")
        startAdSeries()
    }

    fun stopAdLoop() {
        handler.removeCallbacks(usageTimerRunnable)
        interstitialAd = null
        isSeriesPlaying = false
        adsInCurrentSeriesCount = 0
    }

    fun startAdSeries() {
        if (isProPurchased() || isSeriesPlaying) return
        handler.removeCallbacks(usageTimerRunnable) // Cancel pending 30s timer
        isSeriesPlaying = true
        adsInCurrentSeriesCount = 0
        playNextAdInSeries()
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

    private fun playNextAdInSeries() {
        if (isProPurchased()) {
            stopAdLoop()
            return
        }

        if (adsInCurrentSeriesCount >= ADS_PER_SERIES) {
            Log.i(TAG, "Finished series of $ADS_PER_SERIES ads. Starting $USAGE_INTERVAL_MS ms usage timer.")
            isSeriesPlaying = false
            adsInCurrentSeriesCount = 0
            preloadAd()
            handler.postDelayed(usageTimerRunnable, USAGE_INTERVAL_MS)
            return
        }

        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    adsInCurrentSeriesCount++
                    preloadAd()
                    handler.postDelayed({ playNextAdInSeries() }, 1_000L)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    interstitialAd = null
                    adsInCurrentSeriesCount++
                    preloadAd()
                    handler.postDelayed({ playNextAdInSeries() }, 1_000L)
                }
            }
            ad.show(activity)
        } else {
            preloadAd()
            handler.postDelayed({ playNextAdInSeries() }, 2_000L)
        }
    }
}
