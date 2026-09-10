package com.stickermaker.whatsapp.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdFormat
import com.applovin.mediation.MaxAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.MaxReward
import com.applovin.mediation.MaxRewardedAdListener
import com.applovin.mediation.ads.MaxAdView
import com.applovin.mediation.ads.MaxInterstitialAd
import com.applovin.mediation.ads.MaxRewardedAd
import com.stickermaker.whatsapp.BuildConfig

/** AppLovin MAX ads. Empty IDs disable ads in local/debug builds. */
object AdManager {
    private const val TAG = "AdManager"

    private var rewardedAd: MaxRewardedAd? = null
    private var interstitialAd: MaxInterstitialAd? = null
    private var rewardedResult: ((Boolean) -> Unit)? = null
    private var interstitialClosed: (() -> Unit)? = null
    private var rewardEarned = false

    private fun configured(id: String) = BuildConfig.APPLOVIN_SDK_KEY.isNotBlank() && id.isNotBlank()

    fun loadBanner(activity: Activity, container: FrameLayout) {
        container.removeAllViews()
        val id = BuildConfig.APPLOVIN_BANNER_AD_UNIT_ID
        if (!configured(id)) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        val adView = MaxAdView(id, activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, MaxAdFormat.BANNER.getAdaptiveSize(activity).height)
            )
            setPlacement("sticker_editor")
        }
        container.addView(adView)
        adView.loadAd()
    }

    fun preloadRewardedAd(context: Context) {
        val id = BuildConfig.APPLOVIN_REWARDED_AD_UNIT_ID
        if (!configured(id)) return
        val ad = rewardedAd ?: MaxRewardedAd.getInstance(id, context).also {
            rewardedAd = it
            it.setListener(rewardedListener)
        }
        if (!ad.isReady) ad.loadAd()
    }

    fun preloadInterstitialAd(context: Context) {
        val id = BuildConfig.APPLOVIN_INTERSTITIAL_AD_UNIT_ID
        if (!configured(id)) return
        val ad = interstitialAd ?: MaxInterstitialAd(id, context).also {
            interstitialAd = it
            it.setListener(interstitialListener)
        }
        if (!ad.isReady) ad.loadAd()
    }

    fun isRewardedAdReady(): Boolean = rewardedAd?.isReady == true
    fun isInterstitialAdReady(): Boolean = interstitialAd?.isReady == true

    fun showRewardedAd(activity: Activity, onResult: (Boolean) -> Unit) {
        val ad = rewardedAd
        if (ad?.isReady != true) {
            onResult(false)
            return
        }
        rewardEarned = false
        rewardedResult = onResult
        ad.showAd("save_sticker", activity)
    }

    fun showInterstitialAd(activity: Activity, onClosed: () -> Unit) {
        val ad = interstitialAd
        if (ad?.isReady != true) {
            onClosed()
            return
        }
        interstitialClosed = onClosed
        ad.showAd("creation_limit", activity)
    }

    private val rewardedListener = object : MaxRewardedAdListener {
        override fun onUserRewarded(ad: MaxAd, reward: MaxReward) { rewardEarned = true }
        override fun onAdLoaded(ad: MaxAd) = Unit
        override fun onAdDisplayed(ad: MaxAd) = Unit
        override fun onAdClicked(ad: MaxAd) = Unit
        override fun onAdLoadFailed(adUnitId: String, error: MaxError) {
            Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
        }
        override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) {
            finishRewarded(false)
            rewardedAd?.loadAd()
        }
        override fun onAdHidden(ad: MaxAd) {
            finishRewarded(rewardEarned)
            rewardedAd?.loadAd()
        }
    }

    private val interstitialListener = object : MaxAdListener {
        override fun onAdLoaded(ad: MaxAd) = Unit
        override fun onAdDisplayed(ad: MaxAd) = Unit
        override fun onAdClicked(ad: MaxAd) = Unit
        override fun onAdLoadFailed(adUnitId: String, error: MaxError) {
            Log.w(TAG, "Interstitial ad failed to load: ${error.message}")
        }
        override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) {
            finishInterstitial()
            interstitialAd?.loadAd()
        }
        override fun onAdHidden(ad: MaxAd) {
            finishInterstitial()
            interstitialAd?.loadAd()
        }
    }

    private fun finishRewarded(result: Boolean) {
        val callback = rewardedResult ?: return
        rewardedResult = null
        callback(result)
    }

    private fun finishInterstitial() {
        val callback = interstitialClosed ?: return
        interstitialClosed = null
        callback()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
