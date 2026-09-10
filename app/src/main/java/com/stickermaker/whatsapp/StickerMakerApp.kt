package com.stickermaker.whatsapp

import android.app.Application
import android.net.Uri
import android.util.Log
import com.applovin.sdk.AppLovinMediationProvider
import com.applovin.sdk.AppLovinSdk
import com.applovin.sdk.AppLovinSdkInitializationConfiguration
import com.google.firebase.FirebaseApp
import com.stickermaker.whatsapp.analytics.AnalyticsManager
import com.stickermaker.whatsapp.utils.FileUtils

/**
 * Application entry point.
 *
 * Privacy note: this app never uploads user media anywhere. Firebase is used
 * only for anonymous, aggregate Analytics events and crash reports (Crashlytics).
 * AppLovin MAX is used only to display ads; no user media is attached to ad requests.
 */
class StickerMakerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        initializeAppLovinAndServices()
    }

    private fun initializeAppLovinAndServices() {
        val sdkKey = BuildConfig.APPLOVIN_SDK_KEY
        if (sdkKey.isBlank()) {
            Log.w(TAG, "AppLovin is disabled: configure APPLOVIN_SDK_KEY for ads.")
            initializeFirebase()
            return
        }

        val sdk = AppLovinSdk.getInstance(this)
        if (BuildConfig.PRIVACY_POLICY_URL.startsWith("https://")) {
            sdk.settings.termsAndPrivacyPolicyFlowSettings.apply {
                isEnabled = true
                privacyPolicyUri = Uri.parse(BuildConfig.PRIVACY_POLICY_URL)
                setShowTermsAndPrivacyPolicyAlertInGdpr(true)
            }
        }
        val config = AppLovinSdkInitializationConfiguration.builder(sdkKey)
            .setMediationProvider(AppLovinMediationProvider.MAX)
            .build()
        sdk.initialize(config) { initializeFirebase() }
    }

    private fun initializeFirebase() {
        FirebaseApp.initializeApp(this)
        AnalyticsManager.logAppOpened(this)
    }

    private companion object {
        const val TAG = "StickerMakerApp"
    }
}
