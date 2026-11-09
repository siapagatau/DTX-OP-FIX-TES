#pragma once
#include <cstdint>
#include <memory>

// ---------------------------------------------------------------------------
// Lapisan backend audio (v1.9.0).
//
// Dulu PadEngine langsung mewarisi callback Oboe. Sekarang mixer (PadEngine)
// hanya tahu satu hal: "ada yang minta N frame audio" -> render(). Siapa yang
// meminta (Oboe, AAudio langsung, OpenSL ES langsung, atau AudioTrack di sisi
// Kotlin) tidak berpengaruh ke logika mixing sama sekali, jadi semua backend
// berbunyi identik dan bisa ditukar saat runtime.
// ---------------------------------------------------------------------------

// Penerima audio. Diimplementasi PadEngine.
class AudioRenderer {
public:
    virtual ~AudioRenderer() = default;
    // Dipanggil dari audio thread milik backend. `out` = float interleaved,
    // numFrames * channels elemen. HARUS real-time safe (tanpa malloc/lock panjang).
    virtual void render(float *out, int32_t numFrames, int channels, int sampleRate) = 0;
    // Dipanggil backend kalau stream-nya mati karena error (mis. dicabut paksa sistem
    // saat screen recording mulai). Boleh dipanggil dari thread backend; implementasi
    // TIDAK boleh menutup stream di dalam pemanggilan ini (spawn thread sendiri).
    virtual void onBackendDisconnected() = 0;
};

// ID backend. Nilai ini ikut disimpan di SharedPreferences dan dipakai Kotlin
// (AudioBackendManager) - jangan diubah urutannya.
enum BackendId {
    BACKEND_AUTO       = 0,  // Oboe dulu, kalau gagal otomatis AAudio -> OpenSL ES -> AudioTrack
    BACKEND_OBOE       = 1,
    BACKEND_AAUDIO     = 2,  // NDK AAudio langsung (API 26+)
    BACKEND_OPENSL     = 3,  // NDK OpenSL ES langsung (semua versi Android)
    BACKEND_AUDIOTRACK = 4   // android.media.AudioTrack, dijalankan dari Kotlin (tidak ada stream native)
};

class AudioBackend {
public:
    virtual ~AudioBackend() = default;
    // Buka stream DAN mulai memutar. false = gagal (tidak ada yang perlu dibersihkan).
    virtual bool open() = 0;
    // Aman dipanggil berkali-kali, dan aman dipanggil setelah stream mati sendiri karena error.
    virtual void close() = 0;
    virtual int id() const = 0;
};

// Pabrik backend native (OBOE / AAUDIO / OPENSL). rateHint & framesHint = nilai native
// perangkat dari AudioManager (0 = tidak diketahui); dipakai OpenSL ES supaya masuk
// jalur "fast mixer".
std::unique_ptr<AudioBackend> createNativeBackend(int id, AudioRenderer *renderer,
                                                  int rateHint, int framesHint);
