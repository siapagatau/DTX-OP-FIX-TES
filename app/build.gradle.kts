plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kendang.realpads"
    compileSdk = 36
    // NDK r28+ otomatis meng-compile .so dengan alignment 16 KB, termasuk
    // libc++_shared.so (dipakai karena ANDROID_STL=c++_shared). NDK 26 TIDAK
    // 16 KB-aligned -> penyebab peringatan Play Console "tidak mendukung ukuran
    // halaman memori 16 KB". Versi ini = r28c.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // Base applicationId TANPA suffix ".free"/".premium" di sini - suffix-nya
        // dikasih lewat productFlavors di bawah (applicationIdSuffix), jadi APK FREE
        // jadi com.kendang.realpads.gler.free dan APK PREMIUM jadi
        // com.kendang.realpads.gler.premium. Dua applicationId yang beda ini yang
        // bikin kedua versi bisa diinstall BERBARENGAN di HP yang sama tanpa saling
        // timpa (Android nganggep keduanya app yang beda sepenuhnya).
        applicationId = "com.dtxpads.kendang"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.9.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    // 1 dimensi flavor "tier": free (preset P3-P8 & TEMA dikunci, ikon "FREE") vs
    // premium (semua fitur kebuka, ikon "DTX") - dibangun dari SATU source tree yang
    // sama (app/src/main), cuma beda applicationId/nama/ikon (lewat source set
    // app/src/free/res & app/src/premium/res) dan 1 flag BuildConfig.IS_PREMIUM yang
    // dibaca MainActivity buat nentuin fitur mana yang dikunci.
    flavorDimensions += "tier"
    productFlavors {
        create("free") {
            dimension = "tier"
            // TANPA applicationIdSuffix - biar applicationId persis "com.dtxpads.kendang",
            // sesuai package yang sudah terdaftar di Play Console (listing "DTX Pad
            // Kendang Elektrik"). Kalau nanti premium juga mau dirilis terpisah,
            // suffix ".premium" di bawah tetap bikin dia app yang beda.
            versionNameSuffix = "-free"
            resValue("string", "app_name", "DTX Pad Kendang Elektrik")
            buildConfigField("boolean", "IS_PREMIUM", "false")
        }
        create("premium") {
            dimension = "tier"
            applicationIdSuffix = ".premium"
            versionNameSuffix = "-premium"
            resValue("string", "app_name", "APK DTX PREMIUM")
            buildConfigField("boolean", "IS_PREMIUM", "true")
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
        // AGP versi baru men-nonaktifkan resValues secara default. Project ini
        // pakai resValue("string", "app_name", ...) di productFlavors (buat
        // nama aplikasi beda antara FREE & PREMIUM), jadi wajib diaktifkan manual.
        resValues = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        release {
            // R8: shrink + obfuscate + optimize kode DEX. Sebelumnya false -> Play Console
            // melaporkan "Obfuscation 0%" (di bawah minimum 25%). Mapping file otomatis ikut
            // ke dalam AAB, jadi Play bisa men-deobfuscate laporan crash/ANR.
            isMinifyEnabled = true
            // Buang resource yang tidak terpakai (wajib berpasangan dengan isMinifyEnabled).
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Simbol native (libpadengine.so, libc++_shared.so, liboboe.so) ikut diunggah ke
            // Play -> crash native terbaca. Menghilangkan peringatan "tidak ada simbol debug".
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes.add("META-INF/*")
        // .so disimpan TIDAK terkompres + di-zipalign 16 KB oleh AGP (>= 8.5.1).
        // Jangan di-set true: itu mengembalikan perilaku lama (ekstrak ke disk).
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // androidx.graphics:graphics-path (ditarik transitif oleh Compose) versi
    // <= 1.0.1 membawa libandroidx.graphics.path.so yang gagal cek RELRO 16 KB.
    // Constraint ini TIDAK menambah library baru, hanya menaikkan versi minimum
    // kalau library-nya memang ikut terbawa.
    constraints {
        implementation("androidx.graphics:graphics-path") {
            version { require("1.1.0") }
            because("16 KB page size: versi lama tidak lolos cek RELRO")
        }
    }

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Ikon vektor (record/play/switch) buat gantiin emoji & label teks di tombol
    // strip kontrol, biar tampilannya konsisten di semua device/font (emoji bisa
    // beda-beda gambarnya tergantung vendor, ikon vektor selalu sama persis).
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.oboe:oboe:1.9.3")
    // Skin Engine: renderer SVG (gradien/pattern/path/text) untuk layer visual skin. Lihat skin/SkinRenderer.kt.
    implementation("com.caverock:androidsvg-aar:1.4")
    // TAndroidLame (LAME MP3 encoder) DIHAPUS: library-nya sudah lama tidak di-maintain
    // dan libandroidlame.so bawaannya dikompilasi tanpa dukungan 16 KB page size, yang
    // sekarang wajib buat rilis baru di Play Console. Fitur rekam sekarang pakai
    // MediaCodec (AAC/M4A) bawaan Android -> lihat AacEncoder.kt.
}
