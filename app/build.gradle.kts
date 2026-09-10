plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val appLovinSdkKey = providers.gradleProperty("APPLOVIN_SDK_KEY").orElse("").get()
val appLovinBannerAdUnitId = providers.gradleProperty("APPLOVIN_BANNER_AD_UNIT_ID").orElse("").get()
val appLovinInterstitialAdUnitId = providers.gradleProperty("APPLOVIN_INTERSTITIAL_AD_UNIT_ID").orElse("").get()
val appLovinRewardedAdUnitId = providers.gradleProperty("APPLOVIN_REWARDED_AD_UNIT_ID").orElse("").get()
val privacyPolicyUrl = providers.gradleProperty("PRIVACY_POLICY_URL").orElse("").get()

android {
    namespace = "com.stickermaker.whatsapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stickermaker.whatsapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "APPLOVIN_SDK_KEY", appLovinSdkKey.asBuildConfigString())
        buildConfigField("String", "APPLOVIN_BANNER_AD_UNIT_ID", appLovinBannerAdUnitId.asBuildConfigString())
        buildConfigField("String", "APPLOVIN_INTERSTITIAL_AD_UNIT_ID", appLovinInterstitialAdUnitId.asBuildConfigString())
        buildConfigField("String", "APPLOVIN_REWARDED_AD_UNIT_ID", appLovinRewardedAdUnitId.asBuildConfigString())
        buildConfigField("String", "PRIVACY_POLICY_URL", privacyPolicyUrl.asBuildConfigString())

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-ui:1.4.0")

    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // Kotlin coroutines for background processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Firebase (BoM manages versions)
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")

    // ML Kit Selfie Segmentation
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    // AppLovin MAX and its automated Google UMP consent flow.
    implementation("com.applovin:applovin-sdk:13.6.3")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.google.android.gms:play-services-ads-identifier:18.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val missing = linkedMapOf(
            "APPLOVIN_SDK_KEY" to appLovinSdkKey,
            "APPLOVIN_BANNER_AD_UNIT_ID" to appLovinBannerAdUnitId,
            "APPLOVIN_INTERSTITIAL_AD_UNIT_ID" to appLovinInterstitialAdUnitId,
            "APPLOVIN_REWARDED_AD_UNIT_ID" to appLovinRewardedAdUnitId,
            "PRIVACY_POLICY_URL" to privacyPolicyUrl
        ).filterValues { it.isBlank() }.keys
        check(missing.isEmpty()) {
            "Release blocked: add these values to ~/.gradle/gradle.properties: ${missing.joinToString()}"
        }
        check(privacyPolicyUrl.startsWith("https://")) {
            "Release blocked: PRIVACY_POLICY_URL must be a public HTTPS URL."
        }
    }
}
