# Keep sticker content provider models (accessed via reflection-free but keep names for stability)
-keep class com.stickermaker.whatsapp.StickerPro.** { *; }
-keepattributes *Annotation*

# AdMob / Firebase recommended keep rules are applied automatically via their consumer proguard files.
