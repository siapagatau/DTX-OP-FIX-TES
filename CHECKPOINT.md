# APK PROJECT CHECKPOINT (Skin Engine)
Status: Stage 05 — kode Skin Engine ditulis dan diintegrasikan; BELUM dikompilasi (sandbox tanpa Android SDK/kotlinc/jaringan).
Detail arsitektur: docs/skin-engine.md. Root CHECKPOINT.md = checkpoint proyek keseluruhan.
Default skin (fallback offline): app/src/main/assets/skins/default/{skin.svg,skin.json} = salinan skins/skin-01 v1.1.
Dependency baru: com.caverock:androidsvg-aar:1.4 (app/build.gradle.kts).
Cek pertama oleh user: build lewat GitHub Actions (.github/workflows/build-apk.yml), salin log error kompilasi ke AI berikutnya bila ada.

Update Stage 08: tambah skin/SkinRepository.kt + integrasi SkinManager (unduh/cache offline), assets/config/skin-repo.json, docs/skin-repo.md. Belum dikompilasi. Lihat root CHECKPOINT.md.

Update Stage 09: validator diperluas + rantai fallback di SkinManager; docs/validation.md. Belum dikompilasi.

Update Stage 10: PROMPT_SKIN.md lengkap di skins-repo/. Tidak ada perubahan kode APK.

Update Stage 11: README + docs dirapikan (architecture, mapping-table, unsupported-elements); default skin tanpa c2pa.

Update Stage 12-13: validasi final (validation/stage12-final.md di arsip proyek). Proyek siap dibuild; lihat root CHECKPOINT.md di arsip.

Update Stage 14: pemilih skin (SkinPickerDialog, menu melayang baris SKIN). Belum dibuild.

Update Stage 15 (Skin v2): renderer ditulis ulang (skala via Canvas, root SVG dinormalisasi), SkinHost ditulis ulang (sentuhan tidak restart, tanpa inset ganda, tanpa menu melayang), NativeFloatingMenu DIHAPUS, 8 tombol skin-01 yang dulu dekoratif jadi kontrol (skin v1.2), FunctionRegistry + dispatcher diperluas, MusicPanelDialog baru. Lihat docs/skin-v2.md. BELUM dikompilasi.

Update Stage 16 (Skin v3): default = UI klasik (bukan skin), dialog buatan dihapus (pakai panel asli APK), layout 2 zona (pad melar, panel skin bisa disembunyikan), tab kanan 3 tombol, skin.json v1.3 (layout). Lihat docs/skin-v3.md. BELUM dikompilasi.

Update Stage 17 (Skin v3.1): latar panel melar + isi seragam (tinggi maks 150dp), render tidak lagi bergantung status panel (bug panel hilang), render bertahap + cache disk, skin.json v1.4. Lihat docs/skin-v3.1.md. BELUM dikompilasi.

Update Stage 18 (Skin v3.2): SELURUH skin di-stretch (satu gambar, tanpa pemisahan zona/latar panel), knob diputar lalu di-stretch, skin.json v1.5. Lihat docs/skin-v3.2.md. BELUM dikompilasi.

Update Skin v3.2.1: pengaturan lebar skin (slider Asli..Penuh di pemilih skin; SkinManager.widthPercent, SkinLayout.widthFraction). Lihat docs/scaling.md. BELUM dikompilasi.

Update Skin v3.2.2: pemilih skin tidak lagi berkedip (daftar awal dari index tersimpan, baris bawaan tidak muncul-lalu-diganti, status dipindah ke bawah, ikon refresh ukuran tetap), isi dialog bisa di-scroll (pesan/slider tidak terpotong); dialog preset/suara online langsung tampil dari cache tanpa spinner sesaat. BELUM dikompilasi.

Update Skin v3.2.3: tombol preset.prev/preset.next (DEC/INC) kini berputar (terakhir -> 1, 1 -> terakhir; versi FREE berputar di preset yang tidak terkunci). BELUM dikompilasi.

Update Skin v3.3 (lampu indikator pad): skin.json bisa punya "indicators" (id, pad, on) -> elemen SVG `led-pad-N-on` (display="none") digambar sebagai state menyala di atas dasar saat pad N dipukul. Dipakai keypad 12 tombol skin DTX-Multi 12 (skin-01/03/04/05/06 v+1). SkinModel.SkinIndicator, SkinParser, SkinRenderer.buildDetails (kunci layer "ind:<id>"), SkinHost (loop frame `lamp`, padam 240 ms, sumber = padFlash sehingga sentuh/MIDI/keyboard sama-sama menyalakan lampu), SkinValidator (peringatan saja), SkinRenderCache RENDER_VERSION 33. Default skin asset = salinan skin-01 v1.6. Lihat docs/skin-indicators.md. BELUM dikompilasi.

Update v1.5 (versionCode 6): tombol sembunyikan menu bawah kini juga ada di UI klasik (tema default). Tab kanan klasik = ClassicToggleTab (atas: panel samping, bawah: strip tombol utama), state classicBarVisible (uiFlag "classic_bar_visible", default tampil, disimpan). Strip bawah klasik tidak lagi ikut tersembunyi saat panel samping disembunyikan; target tutorial LOAD klasik hanya bila strip tampil. BELUM dikompilasi.

Update v1.6 (versionCode 7): VISUAL SENTUHAN global (dialog Tema -> bagian "Visual Sentuhan"). Gambar kustom (PNG transparan didukung) tampil di tiap titik jari: multi touch, ditahan tetap tampil, digeser ikut jari, memudar 220 ms setelah dilepas (tap cepat tampil min 90 ms), ukuran 16-240dp (slider). File baru TouchFx.kt (TouchFxState + TouchFxOverlay). Overlay = View biasa di atas konten (addContentView), input hanya DIBACA di MainActivity.dispatchTouchEvent SETELAH super -> jalur pad/audio tidak berubah & tidak ada delay; tanpa recomposition; frame loop hanya saat ada efek memudar. Catatan: sentuhan di dalam jendela dialog (AlertDialog dll) tidak tertangkap karena dialog = window terpisah. BELUM dikompilasi.

Update v1.6.1 (versionCode 8): Visual Sentuhan -> (1) slider "Kedip" (kecepatan memudar 0-1000 ms, 0 = Instan/langsung hilang saat jari lepas, berlaku live, disimpan; TouchFxState.fadeMs); (2) efek kini juga tampil DI DALAM dialog: TouchFxDialogHook() dipasang di title/content 9 dialog (membungkus Window.Callback dialog = hanya baca event, plus overlay di PopupWindow layar-penuh non-touchable; ketukan di luar window dialog = kedip singkat via ACTION_OUTSIDE). Tap lebih singkat dari 1 frame tetap tergambar 1x. BELUM dikompilasi.

Update v1.6.2 (versionCode 9): fix build error v1.6.1 (SkinPickerDialog di package .skin kurang import TouchFxDialogHook). BELUM dikompilasi ulang.

Update v1.6.3 (versionCode 10): efek sentuhan di dialog tidak lagi terpotong batas jendela dialog (PopupWindow: attachedInDecor=false, layoutInScreen=true, ukuran layar eksplisit). BELUM dikompilasi/diuji di perangkat.

Update v1.6.4 (versionCode 11): overlay dialog diganti dari PopupWindow (memakai API tersembunyi isLayoutInScreenEnabled -> error kompilasi v1.6.3) ke WindowManager.addView TYPE_APPLICATION_PANEL + token dialog + LAYOUT_IN_SCREEN/NO_LIMITS (API publik). BELUM dikompilasi.

Update v1.6.5: FIX keyboard (soft keyboard/IME) tidak bisa diketuk setelah Visual Sentuhan di dialog. Penyebab: overlay dialog (TYPE_APPLICATION_PANEL, NOT_TOUCHABLE, alpha jendela 1.0) menutupi jendela keyboard dan diblokir aturan untrusted-touch Android 12+ (overlay NOT_TOUCHABLE dgn opasitas > 0.8 memblokir sentuhan ke jendela app lain). Perbaikan: lp.alpha = 0.79f di TouchFxDialogHook (TouchFx.kt). BELUM dikompilasi.

Update v1.6.6 (versionCode 13): Visual Sentuhan TIDAK lagi tampil di dalam dialog (konsisten; hanya di layar utama). TouchFxDialogHook dihapus dari TouchFx.kt dan dari semua dialog; fix alpha v1.6.5 jadi tidak relevan karena overlay window dialog sudah tidak ada. Catatan v1.6.1-v1.6.4 soal efek di dialog tidak berlaku lagi. BELUM dikompilasi.

Update v1.7.0 (versionCode 14): FITUR POTONG SUARA di alur SET SUARA. Setelah user memilih suara (lokal via file picker ATAU online dari GitHub) dan suaranya berhasil di-decode, muncul SampleTrimDialog (file baru SampleTrimDialog.kt) sebelum suara disimpan ke pad: gelombang suara + 2 garis penanda awal/akhir (drag horizontal), tombol -10/+10 ms untuk awal & akhir, DENGAR (preview bagian terpilih lewat AudioTrack terpisah dari engine pad), BUANG HENING (auto-trim di bawah -40 dB), RESET, BATAL, SET SUARA. MainActivity: state pendingTrim (PendingTrim), filePicker & setSoundFromOnline tidak lagi menyimpan langsung melainkan mengisi pendingTrim; penyimpanan + nativeLoadSample dipindah ke commitPendingSample. Kalau rentang tidak diubah -> jalur simpan lama (bytes asli). Kalau dipotong -> SampleTrimmer.trim: fade-in 2 ms / fade-out 8 ms hanya di sisi yang dipotong, encode WAV kanonik, lalu di-parse ulang lewat WavLoader.parse supaya level volume sekarang = level saat app dibuka ulang. Minimal panjang pilihan 10 ms. Browser online tetap terbuka di belakang dialog potong (BATAL = balik ke daftar). Lihat docs/sample-trim.md. BELUM dikompilasi/diuji di perangkat.

Update 1.8.0 (code 15): tab Event multi event + Koleksi Skin Event permanen, status jelas (Sedang dipakai / Belum diunduh / Ada update / Siap dipakai). Belum dibuild.

Update 1.8.1 (code 16): fix spinner loading saat unduh/klaim skin event (ActionButton di EventTab.kt) yang berwarna ungu default Material. Kini warnanya ikut tema seperti spinner lain: accentColor di tombol biasa, CtrlPanelColor di tombol utama (latar accentColor) supaya tetap terlihat. Belum dibuild.

Update 1.9.0 (code 17): (1) SISTEM AUDIO BISA DIPILIH: Otomatis / Oboe / AAudio / OpenSL ES / AudioTrack (Pengaturan -> "Sistem Audio", berlaku langsung, disimpan). Otomatis = Oboe dulu, kalau gagal pindah sendiri AAudio -> OpenSL ES -> AudioTrack; pilihan manual yang gagal juga jatuh ke rantai yang sama + toast. PadEngine tidak lagi mewarisi callback Oboe: mixer = PadEngine::render(), backend di AudioBackend.h + AudioBackends.cpp (CMake: +AudioBackends.cpp, link aaudio & OpenSLES), AudioTrack dijalankan Kotlin (AudioBackendManager.kt, JavaAudioOutput) lewat nativeRenderBlock. API native berubah: nativeInit hanya membuat engine; nativePause/nativeResume diganti nativeStartOutput/nativeStopOutput/nativeConfigureOutput (+nativeGetActiveBackend, nativeIsRecording). (2) JALUR SENTUH PAD DISEDERHANAKAN: hit-test dipindah dari PadGrid ke PadTouchRouter (PadTouch.kt), Rect window disimpan sekali, tanpa LayoutCoordinates per event; perilaku sama. Lihat docs/audio-backend.md. Sintaks C++ (PadEngine.cpp, AudioBackends.cpp dengan header stub) dicek dengan g++; Kotlin/Gradle BELUM dikompilasi, belum diuji di perangkat.
