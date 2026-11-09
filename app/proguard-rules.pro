# ============================================================================
# R8 / ProGuard rules - APK DTX (com.kendang.realpads)
# Dipakai oleh buildType release (isMinifyEnabled = true) di app/build.gradle.kts.
# Base rules: proguard-android-optimize.txt (otomatis, ditambah consumer rules dari
# Compose / AndroidX / Oboe).
# ============================================================================

# --- Stack trace tetap bisa dibaca di Play Console (mapping.txt ikut di AAB) ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNI: native-lib.cpp mengekspor simbol Java_com_kendang_realpads_AudioEngine_<nama>.
# Nama class + nama method `external fun` TIDAK BOLEH di-rename, kalau tidak
# UnsatisfiedLinkError saat nativeInit() dan app crash/tanpa suara.
# (native-lib.cpp tidak memanggil balik ke Java lewat FindClass/GetMethodID,
# jadi tidak ada field/method lain yang perlu di-keep.)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.kendang.realpads.AudioEngine {
    native <methods>;
}

# --- AndroidSVG (renderer skin). Tidak memakai refleksi, tapi library lama
# punya referensi opsional ke class yang tidak ada di Android.
-dontwarn com.caverock.androidsvg.**

# --- Warning umum yang tidak berbahaya ---
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
