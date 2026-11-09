#include <jni.h>
#include <memory>
#include <algorithm>
#include "PadEngine.h"

static std::unique_ptr<PadEngine> g_engine;

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeInit(JNIEnv*, jobject) {
    // v1.9.0: cuma membuat engine. Stream audio dibuka terpisah lewat nativeStartOutput()
    // (dipanggil AudioBackendManager) supaya backend pilihan user sudah terpasang dulu.
    g_engine = std::make_unique<PadEngine>();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeLoadSample(JNIEnv *env, jobject, jint preset, jint pad, jshortArray pcm, jint channels) {
    if (!g_engine) return;
    jsize len = env->GetArrayLength(pcm);
    jshort* data = env->GetShortArrayElements(pcm, nullptr);
    int numFrames = len / (channels > 0 ? channels : 1);
    g_engine->loadSample(preset, pad, reinterpret_cast<int16_t*>(data), numFrames, channels);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeClearPad(JNIEnv*, jobject, jint preset, jint pad) {
    if (g_engine) g_engine->clearPad(preset, pad);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeTrigger(JNIEnv*, jobject, jint preset, jint pad) {
    if (g_engine) g_engine->trigger(preset, pad);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeTriggerVelocity(JNIEnv*, jobject, jint preset, jint pad, jfloat velocity) {
    if (g_engine) g_engine->trigger(preset, pad, velocity);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeShutdown(JNIEnv*, jobject) {
    if (g_engine) {
        g_engine->stop();
        g_engine.reset();
    }
}

// ---------------------------------------------------------------------------
// Output audio (v1.9.0). Menggantikan nativePause/nativeResume lama.
// Dipanggil dari AudioBackendManager (Kotlin) di onCreate/onPause/onResume dan saat
// user mengganti backend di Pengaturan. Sample yang sudah di-load ke pad TIDAK ikut
// hilang saat stream ditutup/dibuka lagi (engine tetap hidup).
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeConfigureOutput(JNIEnv*, jobject, jint mode, jint sampleRateHint, jint framesHint) {
    if (!g_engine) return;
    g_engine->setBackendMode(mode);
    g_engine->setOutputHints(sampleRateHint, framesHint);
}

// Return: backend yang aktif (1=Oboe, 2=AAudio, 3=OpenSL ES, 4=AudioTrack/Kotlin),
// atau 0 kalau SEMUA backend native gagal dibuka.
extern "C" JNIEXPORT jint JNICALL
Java_com_kendang_realpads_AudioEngine_nativeStartOutput(JNIEnv*, jobject) {
    if (!g_engine) return 0;
    g_engine->start();
    return (jint)g_engine->activeBackend();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeStopOutput(JNIEnv*, jobject) {
    if (g_engine) g_engine->stop();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetActiveBackend(JNIEnv*, jobject) {
    return g_engine ? (jint)g_engine->activeBackend() : 0;
}

// Kalau lagi rekam, jangan lepas output di onPause -> lebih penting jaga rekaman gak
// keputus daripada ngalah ke app lain yang minta jalur exclusive.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeIsRecording(JNIEnv*, jobject) {
    return (g_engine && g_engine->isRecording()) ? JNI_TRUE : JNI_FALSE;
}

// Dipakai backend AudioTrack (Kotlin): minta engine merender `frames` frame stereo float
// langsung ke direct ByteBuffer (tanpa copy JNI). Tidak ada engine / buffer kekecilan -> hening.
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeRenderBlock(JNIEnv *env, jobject, jobject buffer, jint frames, jint sampleRate) {
    if (buffer == nullptr || frames <= 0) return;
    void *addr = env->GetDirectBufferAddress(buffer);
    jlong cap = env->GetDirectBufferCapacity(buffer);
    if (addr == nullptr || cap < (jlong)frames * 2 * (jlong)sizeof(float)) return;
    float *out = static_cast<float*>(addr);
    if (!g_engine) {
        std::fill(out, out + (size_t)frames * 2, 0.0f);
        return;
    }
    g_engine->render(out, frames, 2, sampleRate);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeStartRecording(JNIEnv*, jobject) {
    if (g_engine) g_engine->startRecording();
}

// Dipakai ScreenRecordService buat nyicil narik PCM tiap ~100-200ms selagi
// rekam layar jalan, TANPA menghentikan recording_ (lihat catatan panjang di
// PadEngine::drainRecordBuffer). Sengaja duplikat pola OOM-safety yang sama
// kayak nativeStopRecording() di bawah - biarpun chunk-nya jauh lebih kecil per
// panggilan (jadi OOM di sini jauh lebih kecil kemungkinannya), tetap dijaga
// biar pending exception gak nyangkut ke JNI call berikutnya.
extern "C" JNIEXPORT jshortArray JNICALL
Java_com_kendang_realpads_AudioEngine_nativeDrainRecording(JNIEnv *env, jobject) {
    if (!g_engine) return env->NewShortArray(0);
    std::vector<int16_t> pcm = g_engine->drainRecordBuffer();
    jshortArray result = env->NewShortArray((jsize)pcm.size());
    if (result == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return env->NewShortArray(0);
    }
    if (!pcm.empty()) {
        env->SetShortArrayRegion(result, 0, (jsize)pcm.size(), reinterpret_cast<const jshort*>(pcm.data()));
    }
    return result;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_kendang_realpads_AudioEngine_nativeStopRecording(JNIEnv *env, jobject) {
    if (!g_engine) return env->NewShortArray(0);
    std::vector<int16_t> pcm = g_engine->stopRecording();
    jshortArray result = env->NewShortArray((jsize)pcm.size());
    // FIX: kalau rekamannya sangat panjang, NewShortArray bisa gagal alokasi di sisi
    // JVM (heap kehabisan) dan balikin nullptr sambil nyimpen PENDING EXCEPTION
    // (OutOfMemoryError) di JNIEnv. Kalau exception itu dibiarin nempel lalu kita
    // masih manggil JNI lain (SetShortArrayRegion) sebelum di-clear, perilakunya jadi
    // undefined menurut spesifikasi JNI - bisa nyangkut/crash yang susah dilacak,
    // bukan sekadar "gagal nyimpen" yang rapi. Makanya exception itu wajib di-clear
    // dulu di sini sebelum return, biar sisi Kotlin cuma nerima array kosong/null
    // (ditangani jadi toast "gagal", bukan sesuatu yang lebih parah).
    if (result == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    if (!pcm.empty()) {
        env->SetShortArrayRegion(result, 0, (jsize)pcm.size(), reinterpret_cast<jshort*>(pcm.data()));
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeLoadMusic(JNIEnv *env, jobject, jshortArray pcm, jint channels) {
    if (!g_engine || pcm == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(pcm);
    if (len <= 0) return JNI_FALSE;
    // FIX efisiensi: GetShortArrayElements() diganti GetPrimitiveArrayCritical().
    // Bedanya: GetShortArrayElements pada banyak implementasi JVM (termasuk ART)
    // BISA nyalin seluruh isi array Java ke heap native dulu (terutama saat GC
    // sedang aktif/array besar) sebelum kita pakai - buat lagu panjang itu artinya
    // ada salinan penuh PCM yang gak perlu, pas justru lagi butuh headroom memori
    // paling besar. GetPrimitiveArrayCritical umumnya ngasih pointer LANGSUNG ke
    // memori array (tanpa salin) selama critical section-nya sesingkat mungkin dan
    // gak manggil balik ke JNI/Java lain di dalamnya - persis kondisi di sini
    // (loadMusic() cuma loop baca + copy sekali ke buffer internal engine, gak ada
    // panggilan JNI lain di tengah). Teknik ini standar & terdokumentasi resmi di
    // JNI spec buat transfer array besar seperti ini.
    jshort* data = static_cast<jshort*>(env->GetPrimitiveArrayCritical(pcm, nullptr));
    if (data == nullptr) return JNI_FALSE; // JNI gagal alokasi/pin -> gagal dengan rapi, bukan crash
    int numFrames = len / (channels > 0 ? channels : 1);
    bool ok = g_engine->loadMusic(reinterpret_cast<int16_t*>(data), numFrames, channels);
    env->ReleasePrimitiveArrayCritical(pcm, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativePlayMusic(JNIEnv*, jobject) {
    if (g_engine) g_engine->playMusic();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeStopMusic(JNIEnv*, jobject) {
    if (g_engine) g_engine->stopMusic();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSeekMusic(JNIEnv*, jobject, jdouble seconds) {
    if (g_engine) g_engine->seekMusic(seconds);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetMusicPosition(JNIEnv*, jobject) {
    return g_engine ? g_engine->getMusicPositionSeconds() : 0.0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetMusicDuration(JNIEnv*, jobject) {
    return g_engine ? g_engine->getMusicDurationSeconds() : 0.0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeIsMusicPlaying(JNIEnv*, jobject) {
    return g_engine ? (jboolean)g_engine->isMusicPlaying() : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetKendangVolume(JNIEnv*, jobject, jfloat v) {
    if (g_engine) g_engine->setKendangVolume(v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetMusicVolume(JNIEnv*, jobject, jfloat v) {
    if (g_engine) g_engine->setMusicVolume(v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetPadVolume(JNIEnv*, jobject, jint preset, jint pad, jfloat v) {
    if (g_engine) g_engine->setPadVolume(preset, pad, v);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetPadVolume(JNIEnv*, jobject, jint preset, jint pad) {
    return g_engine ? g_engine->getPadVolume(preset, pad) : 1.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetPadTune(JNIEnv*, jobject, jint preset, jint pad, jfloat semitones) {
    if (g_engine) g_engine->setPadTune(preset, pad, semitones);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetPadTune(JNIEnv*, jobject, jint preset, jint pad) {
    return g_engine ? g_engine->getPadTune(preset, pad) : 0.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetPadChoke(JNIEnv*, jobject, jint preset, jint pad, jboolean enabled) {
    if (g_engine) g_engine->setPadChoke(preset, pad, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetPadChoke(JNIEnv*, jobject, jint preset, jint pad) {
    return g_engine ? (jboolean)(g_engine->getPadChoke(preset, pad) ? JNI_TRUE : JNI_FALSE) : JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetPadCrossChokeEnabled(JNIEnv*, jobject, jint preset, jint pad, jboolean enabled) {
    if (g_engine) g_engine->setPadCrossChokeEnabled(preset, pad, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetPadCrossChokeEnabled(JNIEnv*, jobject, jint preset, jint pad) {
    return g_engine ? (jboolean)(g_engine->getPadCrossChokeEnabled(preset, pad) ? JNI_TRUE : JNI_FALSE) : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetPadCrossChokeMask(JNIEnv*, jobject, jint preset, jint pad, jint mask) {
    if (g_engine) g_engine->setPadCrossChokeMask(preset, pad, mask);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetPadCrossChokeMask(JNIEnv*, jobject, jint preset, jint pad) {
    return g_engine ? (jint)g_engine->getPadCrossChokeMask(preset, pad) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetPadLoop(JNIEnv*, jobject, jint preset, jint pad, jboolean enabled) {
    if (g_engine) g_engine->setPadLoop(preset, pad, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetPadLoop(JNIEnv*, jobject, jint preset, jint pad) {
    return g_engine ? (jboolean)(g_engine->getPadLoop(preset, pad) ? JNI_TRUE : JNI_FALSE) : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetPadLoopTempo(JNIEnv*, jobject, jint preset, jint pad, jfloat rate) {
    if (g_engine) g_engine->setPadLoopTempo(preset, pad, rate);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetPadLoopTempo(JNIEnv*, jobject, jint preset, jint pad) {
    return g_engine ? g_engine->getPadLoopTempo(preset, pad) : 1.0f;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeIsPadLooping(JNIEnv*, jobject, jint preset, jint pad) {
    return g_engine ? (jboolean)(g_engine->isPadLooping(preset, pad) ? JNI_TRUE : JNI_FALSE) : JNI_FALSE;
}
