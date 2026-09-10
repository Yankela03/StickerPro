# StickerPro — Setup Guide

A privacy-first Android app (Kotlin) that converts locally-picked/shared images and
short videos into WhatsApp-compatible stickers. No accounts, no backend, no upload
of user media — everything happens on-device.

---

## 1. Project structure

```
WhatsAppStickerMaker/
├── settings.gradle.kts
├── build.gradle.kts                 (root)
├── gradle.properties
├── app/
│   ├── build.gradle.kts             (app module)
│   ├── google-services.json.SAMPLE  (rename → google-services.json, see §3)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/stickermaker/whatsapp/
│       │   ├── StickerMakerApp.kt          Consent, AppLovin, and Firebase init
│       │   ├── ui/                         Activities + RecyclerView adapter
│       │   ├── ui/widget/VideoTrimView.kt  Custom trim range-slider
│       │   ├── media/                      Validation, frame extraction, bitmap scaling
│       │   ├── sticker/                    WebP conversion (static + animated muxer)
│       │   ├── whatsapp/                   Sticker pack model, ContentProvider, exporter
│       │   ├── ads/AdManager.kt            AppLovin MAX ad wrapper
│       │   ├── analytics/AnalyticsManager.kt Firebase Analytics/Crashlytics wrapper
│       │   └── utils/FileUtils.kt          Private-storage temp file management
│       └── res/                            Layouts, drawables, strings, themes
```

Package name / applicationId: `com.stickermaker.whatsapp` (change to your own reverse-domain
name before publishing — see §6).

---

## 2. Prerequisites

- Android Studio Koala (2024.1.1) or newer
- JDK 17 (bundled with recent Android Studio)
- Android SDK Platform 34, Build-Tools 34+
- A physical device or emulator with **WhatsApp installed** to test the "Add to
  WhatsApp" flow (WhatsApp isn't installable on most emulators without extra
  work — a real device is easiest)

### Opening the project
1. `File → Open` and select the `WhatsAppStickerMaker/` folder.
2. Let Gradle sync. If prompted, use the Gradle version referenced in
   `gradle/wrapper/gradle-wrapper.properties` (8.7).

---

## 3. Firebase setup (Analytics + Crashlytics)

1. Go to the [Firebase Console](https://console.firebase.google.com/) → **Add project**.
2. Inside the project, click **Add app → Android**, and register the package name
   `com.stickermaker.whatsapp` (or whatever you changed it to).
3. Download the generated **`google-services.json`**.
4. Delete `app/google-services.json.SAMPLE` and place the real file at
   `app/google-services.json`.
5. In the Firebase console, enable **Google Analytics** for the project (prompted
   during app registration) and enable **Crashlytics** under Build → Crashlytics.
6. Re-sync Gradle. The `com.google.gms.google-services` and
   `com.google.firebase.crashlytics` plugins (already wired in the Gradle files) will
   pick up the config automatically.

**What gets sent to Firebase:** only the six anonymous events listed in the spec
(`app_opened`, `video_selected`, `image_selected`, `sticker_created`,
`sticker_exported`, `rewarded_ad_completed`) plus standard crash reports. No file
paths, filenames, or media content are ever included — see `AnalyticsManager.kt`.

---

## 4. AppLovin MAX setup

1. Create the Android app in the AppLovin dashboard using the exact package name
   `com.stickermaker.whatsapp` (or your final package name).
2. Create **Banner**, **Interstitial**, and **Rewarded** MAX ad units.
3. Host a complete privacy policy on a public HTTPS webpage. It must name the app
   and developer, provide a privacy contact, and accurately explain AppLovin/Firebase
   data collection, sharing, security, retention, and deletion.
4. Put these values in your user-level `~/.gradle/gradle.properties` file so they
   are not committed to source control:

```properties
APPLOVIN_SDK_KEY=your_sdk_key
APPLOVIN_BANNER_AD_UNIT_ID=your_banner_ad_unit_id
APPLOVIN_INTERSTITIAL_AD_UNIT_ID=your_interstitial_ad_unit_id
APPLOVIN_REWARDED_AD_UNIT_ID=your_rewarded_ad_unit_id
PRIVACY_POLICY_URL=https://yankela03.github.io/StickerPro/
```

### Publishing the included privacy policy on GitHub Pages

1. Replace both placeholders in `docs/index.html` with your legal developer name
   and monitored privacy-contact email.
2. Push the project to a GitHub repository whose default branch is `main`.
3. In the repository, open **Settings → Pages** and select **GitHub Actions** as the
   source. The included workflow publishes `docs/` automatically.
4. After the workflow succeeds, open the displayed Pages URL and confirm it is
   publicly accessible while signed out. Use that exact HTTPS URL for
   `PRIVACY_POLICY_URL` and in Play Console.

Debug builds run safely with ads hidden when these values are absent. Release builds
are deliberately blocked until all five values exist and the privacy URL uses HTTPS.
The app enables MAX's Terms and Privacy Policy Flow and Google UMP before initializing
Firebase. Configure and publish the GDPR message and every mediated ad partner in the
MAX/Google privacy dashboard before release. Use MAX test mode on test devices; never
click live ads during development.

**Ad strategy already implemented (per spec, kept minimal):**
- Banner ads use the existing app placements and adaptive MAX sizing.
- One rewarded ad opportunity right before "Add to WhatsApp" — if the ad fails to
  load, the app **still lets the user save the sticker** (see
  `error_ad_not_ready` fallback) rather than blocking them.

---

## 5. How stickers are built (technical overview)

- **Static image → sticker:** decode → scale to fit 512×512 → pad to an exact
  512×512 transparent square → encode as WebP, stepping quality down until the file
  is ≤100 KB (`StickerConverter.compressStaticWithSizeLimit`).
- **Video → animated sticker:** trim window is capped at 6 seconds
  (`StickerSpec.MAX_ANIMATION_DURATION_MS`) and sampled at 10 fps
  (`StickerSpec.TARGET_FPS`), each frame is padded to a 512×512 square, then muxed
  into a single **animated WebP** file entirely in Kotlin
  (`sticker/AnimatedWebPEncoder.kt` — builds the RIFF/`VP8X`/`ANIM`/`ANMF` container
  per the [WebP container spec](https://developers.google.com/speed/webp/docs/riff_container),
  reusing Android's built-in per-frame WebP encoder). If the result exceeds 500 KB,
  quality is lowered and then frames are progressively dropped until it fits.
- **Video → static sticker:** if the user checks "make static sticker", only the
  first frame of the trimmed window is used.
- All of this runs on a background dispatcher (`Dispatchers.Default`) via Kotlin
  coroutines, so the UI thread and low-end devices stay responsive.
- Every intermediate file lives under the app's private `cacheDir/temp` or
  `filesDir/stickers` — never public/shared storage — and temp files are deleted:
  - on every app cold start (`StickerMakerApp.onCreate`),
  - immediately after a successful conversion,
  - and when the editor screen is closed without converting.

## 6. "Add to WhatsApp" flow

This follows WhatsApp's official 3rd-party sticker integration contract
(same shape as https://github.com/WhatsApp/stickers):
- `whatsapp/StickerContentProvider.kt` exposes pack/sticker metadata and file bytes
  to WhatsApp only, via a **read-only**, `com.whatsapp.sticker.READ`-permission-gated
  ContentProvider — declared in `AndroidManifest.xml`.
- `whatsapp/WhatsAppStickerExporter.kt` fires the
  `com.whatsapp.intent.action.ENABLE_STICKER_PACK` intent, which opens WhatsApp's own
  "Add sticker pack" screen.
- If neither `com.whatsapp` nor `com.whatsapp.w4b` (Business) is installed, the user
  sees a friendly "WhatsApp isn't installed" message instead of a crash.

---

## 7. Building an APK / AAB

From Android Studio: **Build → Generate Signed Bundle / APK**, choose APK or Android
App Bundle, and either create a new keystore or use an existing one.

From the command line:
```bash
# Debug APK (unsigned, for testing)
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (requires a signing config - see below)
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk

# Release Android App Bundle (required for Play Store)
./gradlew bundleRelease
# output: app/build/outputs/bundle/release/app-release.aab
```

To sign release builds via Gradle (instead of Android Studio's wizard), add a
signing config to `app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("your-release-key.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "your-key-alias"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ...existing isMinifyEnabled / proguardFiles config
        }
    }
}
```
Never commit your keystore or passwords to version control.

---

## 8. Publishing to Google Play

1. Update `app/build.gradle.kts`:
   - Change `applicationId` to your own unique ID (must match what you registered
     in Firebase/AppLovin if you want those integrations to line up).
   - Bump `versionCode` / `versionName` for each release.
2. Replace the placeholder launcher icon (`res/drawable/ic_launcher_*.xml`) with your
   final branding (Android Studio's Image Asset tool: right-click `res` → New → Image Asset).
3. Build a signed **AAB**: `./gradlew bundleRelease` (see §7).
4. Create a Play Console listing (https://play.google.com/console):
   - App category, screenshots, feature graphic, short/long description.
   - **Data safety form**: declare what the app collects. Based on this app's actual
     behavior: no personal data collected; only anonymous product-analytics events
     (Firebase Analytics) and crash diagnostics (Crashlytics); no data shared with
     third parties except the data disclosed for Firebase and AppLovin ad serving.
     Include device/advertising identifiers and any other types reported by the
     final SDK/adapter set; answer from the final release bundle, not this summary.
   - **Ads declaration**: mark the app as containing ads (banner, interstitial, rewarded).
   - **Content rating** questionnaire.
   - **Privacy Policy URL**: required because the app uses Analytics/Crashlytics/Ads —
     host a policy describing exactly what this README/§3 describes (on-device
     processing, Firebase analytics/crash reporting, and AppLovin ad serving).
   - **Advertising ID declaration**: declare that the app uses Advertising ID for ads.
   - **Target audience**: AppLovin may not be initialized for children. Do not select
     child age groups unless the monetization implementation and SDK choice are changed.
   - **Consent/CMP**: publish Google UMP, include every enabled mediation partner, and
     verify consent in AppLovin's Mediation Debugger from an EEA/UK test device.
5. Upload the `.aab` to a testing track first (Internal testing), verify the "Add
   to WhatsApp" flow on a real device with WhatsApp installed, then promote to
   Production when ready.
6. WhatsApp itself doesn't require any registration/whitelisting for the 3rd-party
   sticker `ENABLE_STICKER_PACK` intent — it works for any app implementing the
   contract correctly, as validated by WhatsApp's own sticker sample repo.

---

## 9. Performance / robustness notes already built in

- All picked/shared media is validated (`media/MediaValidator.kt`) before any
  processing — checks MIME type, video duration, and a 200 MB size ceiling — so
  malformed or unsupported files fail with a message instead of crashing.
- Video frame extraction uses `MediaMetadataRetriever` (works across the wide range
  of codecs/devices Android supports out of the box) rather than a raw `MediaCodec`
  pipeline, trading a little precision for much broader device compatibility.
- Animation length, frame count, and frame resolution are all capped
  (`StickerSpec.kt`) specifically to keep memory/CPU use bounded on low-RAM phones.
- Conversion runs entirely off the main thread via coroutines; the editor screen
  shows a progress overlay for the duration.
- Runtime permission requests are handled with graceful fallback messaging on
  denial (`error_permission_denied`).

## 10. What this app intentionally does NOT do

Per the product spec: no user accounts/login, no cloud storage, no server-side
processing, no scraping of TikTok/other social apps, no downloading of remote
social-media content — the app only ever processes media the user explicitly
shares into it or picks from their own device.
