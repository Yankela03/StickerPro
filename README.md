# StickerPro

Turn short videos and images into WhatsApp stickers — entirely on-device, no
accounts, no cloud upload.

See **SETUP.md** for full setup instructions (Android Studio, Firebase, AppLovin MAX,
building an APK/AAB, and publishing to Google Play).

## Quick start
1. Open this folder in Android Studio.
2. Replace `app/google-services.json.SAMPLE` with a real `google-services.json`
   from your Firebase project (see SETUP.md §3).
3. Add your AppLovin SDK key, ad-unit IDs, and public privacy-policy URL to your
   private Gradle properties (see SETUP.md §4).
4. Run on a device with WhatsApp installed to test the full "Add to WhatsApp" flow.
