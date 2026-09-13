package com.tabletgamepadbridge

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

private const val TAG = "AdManager"

/**
 * Starts 3 Interstitial Ads in a row IMMEDIATELY on app launch for free users,
 * followed by 15 seconds of usage, then 3 ads again in a loop.
 * Stops completely when Pro ($5 USD) is purchased.
 */
class AdManager(
    private val activity: Activity,
    private val isProPurchased: () -> Boolean,
    private val onPromptPurchase: () -> Unit
) {
    companion object {
        // REPLACE WITH YOUR REAL ADMOB INTERSTITIAL AD UNIT ID FROM eas2012@gmail.com ACCOUNT
        const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        private const val USAGE_INTERVAL_MS = 15_000L // 15 seconds usage between ad series
        private const val ADS_PER_SERIES = 3 // 3 ads in a row per series
    }

    private var interstitialAd: InterstitialAd? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isAdLoading = false
    private var adsInCurrentSeriesCount = 0

    private val usageTimerRunnable = Runnable {
        if (isProPurchased()) {
            stopAdLoop()
            return@Runnable
        }
        adsInCurrentSeriesCount = 0
        playNextAdInSeries()
    }

    fun startAdLoop() {
        if (isProPurchased()) return
        preloadAd()
        // Play 3-ad series IMMEDIATELY on app open!
        adsInCurrentSeriesCount = 0
        handler.postDelayed({ playNextAdInSeries() }, 1_000L)
    }

    fun stopAdLoop() {
        handler.removeCallbacks(usageTimerRunnable)
        interstitialAd = null
        adsInCurrentSeriesCount = 0
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
            Log.i(TAG, "Finished series of $ADS_PER_SERIES ads. Starting 15s usage timer.")
            preloadAd()
            handler.postDelayed(usageTimerRunnable, USAGE_INTERVAL_MS)
            return
        }

        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    super.onAdShowedFullScreenContent()
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            "⭐ Tap 'Remove Ads ($5)' anytime to stop all ads permanently!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    adsInCurrentSeriesCount++
                    preloadAd()
                    playNextAdInSeries()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    interstitialAd = null
                    adsInCurrentSeriesCount++
                    preloadAd()
                    playNextAdInSeries()
                }
            }
            ad.show(activity)
        } else {
            preloadAd()
            handler.postDelayed({ playNextAdInSeries() }, 2_000L)
        }
    }
}
