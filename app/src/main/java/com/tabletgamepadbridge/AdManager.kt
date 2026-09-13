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
 * Preloads a queue of 3 Interstitial Ads and plays them sequentially (Ad 1 -> Ad 2 -> Ad 3)
 * without closing back to the app until all 3 ads in the queue have completed.
 * Followed by 30 seconds of free usage time.
 */
class AdManager(
    private val activity: Activity,
    private val isProPurchased: () -> Boolean
) {
    companion object {
        // REPLACE WITH YOUR REAL ADMOB INTERSTITIAL AD UNIT ID FROM eas2012@gmail.com ACCOUNT
        const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        private const val USAGE_INTERVAL_MS = 30_000L // 30 seconds of free usage between ad queues
        private const val ADS_PER_SERIES = 3 // 3 ads per queue
    }

    private val adQueue = mutableListOf<InterstitialAd>()
    private val handler = Handler(Looper.getMainLooper())
    private var isAdLoading = false
    private var isSeriesPlaying = false
    private var adsInCurrentSeriesCount = 0

    private val usageTimerRunnable = Runnable {
        if (isProPurchased() || isSeriesPlaying) return@Runnable
        Log.i(TAG, "30-second usage timer ended. Starting 3-ad queue.")
        startAdSeries()
    }

    fun startAdLoop() {
        if (isProPurchased()) return
        fillAdQueue()
        handler.postDelayed({ startAdSeries() }, 1_500L)
    }

    fun onConnectTapped() {
        if (isProPurchased() || isSeriesPlaying) return
        Log.i(TAG, "Connect tapped - triggering 3-ad queue")
        startAdSeries()
    }

    fun stopAdLoop() {
        handler.removeCallbacks(usageTimerRunnable)
        adQueue.clear()
        isSeriesPlaying = false
        adsInCurrentSeriesCount = 0
    }

    fun startAdSeries() {
        if (isProPurchased() || isSeriesPlaying) return
        handler.removeCallbacks(usageTimerRunnable)
        isSeriesPlaying = true
        adsInCurrentSeriesCount = 0
        playNextInQueue()
    }

    private fun fillAdQueue() {
        if (isProPurchased() || isAdLoading || adQueue.size >= ADS_PER_SERIES) return

        isAdLoading = true
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            activity,
            INTERSTITIAL_AD_UNIT_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    adQueue.add(ad)
                    isAdLoading = false
                    Log.i(TAG, "Loaded ad into queue (total: ${adQueue.size}/$ADS_PER_SERIES)")
                    if (adQueue.size < ADS_PER_SERIES) {
                        fillAdQueue()
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isAdLoading = false
                    Log.w(TAG, "Ad queue load failed: ${error.message}")
                }
            }
        )
    }

    private fun playNextInQueue() {
        if (isProPurchased()) {
            stopAdLoop()
            return
        }

        if (adsInCurrentSeriesCount >= ADS_PER_SERIES) {
            Log.i(TAG, "Completed 3-ad queue series. User gets 30 seconds of free usage.")
            isSeriesPlaying = false
            adsInCurrentSeriesCount = 0
            fillAdQueue()
            handler.postDelayed(usageTimerRunnable, USAGE_INTERVAL_MS)
            return
        }

        val ad = if (adQueue.isNotEmpty()) adQueue.removeAt(0) else null
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    adsInCurrentSeriesCount++
                    fillAdQueue()
                    playNextInQueue()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    adsInCurrentSeriesCount++
                    fillAdQueue()
                    playNextInQueue()
                }
            }
            ad.show(activity)
        } else {
            fillAdQueue()
            handler.postDelayed({ playNextInQueue() }, 1_500L)
        }
    }
}
