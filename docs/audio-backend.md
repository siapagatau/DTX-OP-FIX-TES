# Sistem Audio & Jalur Sentuh (v1.9.0)

## Sistem audio (bisa dipilih di Pengaturan -> "Sistem Audio")

Mixer pad (`PadEngine`, C++) hanya ada satu dan tidak tahu siapa yang memintanya merender audio
(`PadEngine::render`). Yang bisa diganti hanya jalur keluar ke hardware:

| Pilihan | Implementasi | Catatan |
|---|---|---|
| Otomatis (bawaan) | Oboe -> AAudio -> OpenSL ES -> AudioTrack | pindah sendiri kalau yang di depan gagal dibuka |
| Oboe | `AudioBackends.cpp` (OboeBackend) | perilaku sama dengan versi sebelumnya |
| AAudio | `AudioBackends.cpp` (AAudioBackend), NDK langsung | Android 8.0+; sebagian HP Android 8 kurang stabil |
| OpenSL ES | `AudioBackends.cpp` (OpenSLBackend), NDK langsung | paling kompatibel di antara yang native |
| AudioTrack | `AudioBackendManager.kt` (JavaAudioOutput) | thread Kotlin menarik audio lewat `nativeRenderBlock`; delay terbesar |

Alur: `MainActivity` -> `AudioBackendManager` (pilihan tersimpan di prefs `realpads_audio_backend`)
-> `AudioEngine.nativeConfigureOutput/StartOutput` -> `PadEngine::openStreamLocked` (urutan percobaan:
pilihan user dulu, lalu sisa rantai native). Kalau semua native gagal, manager menjalankan AudioTrack.
Kalau stream mati di tengah jalan (mis. screen recording mulai), `onBackendDisconnected` membuka ulang
dengan aturan yang sama. Mengganti pilihan berlaku langsung (tanpa restart); ditolak saat sedang rekam.

Perubahan API native: `nativeInit()` sekarang hanya membuat engine; `nativePause/nativeResume`
diganti `nativeStartOutput/nativeStopOutput` (dipanggil `AudioBackendManager.pause()/resume()`).

## Jalur sentuh pad

Sebelum: hit-test ~100 baris di dalam `pointerInput` PadGrid (konversi window->lokal per pad per event).
Sekarang: `jari -> 1 pointerInput di grid -> PadTouchRouter.hit() -> onPadDown() -> nativeTrigger`.
`PadTouchRouter` (PadTouch.kt) menyimpan Rect window pad/chip tempo (diperbarui saat layout berubah),
jadi per event hanya perbandingan angka. Perilaku tidak berubah: tap pertama selalu bunyi, geser
pindah pad hanya bila "Geser Pindah Pad" aktif, chip tempo bukan pukulan, celah antar pad di-snap 12dp.
