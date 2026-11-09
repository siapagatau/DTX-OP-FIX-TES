package com.kendang.realpads

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Pengelola "sistem audio" (v1.9.0). Mixer pad (PadEngine, C++) tetap SATU; yang bisa
// dipilih hanya jalur keluarnya ke hardware:
//
//   OTOMATIS   Oboe dulu; kalau gagal dibuka -> AAudio -> OpenSL ES -> AudioTrack  (bawaan)
//   OBOE       Oboe (memilih AAudio/OpenSL sendiri sesuai versi Android)
//   AAUDIO     NDK AAudio langsung (Android 8.0+; sebagian HP Android 8 kurang stabil)
//   OPENSL     NDK OpenSL ES langsung (paling tua, paling kompatibel)
//   AUDIOTRACK android.media.AudioTrack dari Kotlin (paling kompatibel, latensi paling besar)
//
// Pilihan user disimpan dan tetap berlaku walau app ditutup. Kalau pilihan user gagal
// dibuka di HP tertentu, engine otomatis pindah ke sistem lain (urutan di atas) dan user
// diberi tahu lewat toast + label "Sedang dipakai" di Pengaturan.
object AudioBackendManager {
    const val AUTO = 0
    const val OBOE = 1
    const val AAUDIO = 2
    const val OPENSL = 3
    const val AUDIOTRACK = 4

    private const val PREFS = "realpads_audio_backend"
    private const val KEY_MODE = "mode"

    // Pilihan user (0..4). Dibaca UI (Compose).
    var mode by mutableStateOf(AUTO)
        private set

    // Sistem yang BENAR-BENAR jalan sekarang (1..4), 0 = belum/tidak ada.
    var active by mutableStateOf(0)
        private set

    private var appCtx: Context? = null
    private val lock = Any()
    private val ui = Handler(Looper.getMainLooper())

    fun nameOf(id: Int): String = when (id) {
        AUTO -> "Otomatis"
        OBOE -> "Oboe"
        AAUDIO -> "AAudio"
        OPENSL -> "OpenSL ES"
        AUDIOTRACK -> "AudioTrack"
        else -> "-"
    }

    fun descriptionOf(id: Int): String = when (id) {
        AUTO -> "Oboe dulu, otomatis pindah kalau HP tidak mendukung"
        OBOE -> "Pustaka audio Google (bawaan)"
        AAUDIO -> "API audio native Android 8.0+"
        OPENSL -> "API audio lama, kompatibel di semua HP"
        AUDIOTRACK -> "Paling kompatibel, delay sedikit lebih besar"
        else -> ""
    }

    // Dipanggil sekali di MainActivity.onCreate. Membuat engine + membuka output.
    fun init(context: Context) {
        val c = context.applicationContext
        appCtx = c
        mode = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, AUTO).coerceIn(AUTO, AUDIOTRACK)
        synchronized(lock) {
            JavaAudioOutput.stop()          // aman kalau activity dibuat ulang (rotasi dsb)
            AudioEngine.nativeInit()
            startLocked(announce = true)
        }
    }

    // onPause: lepas jalur audio supaya app lain (mis. FREE <-> Premium) bisa dapat jalur
    // exclusive. Sample pad tetap ada. Lagi rekam -> jangan dilepas (jaga rekaman utuh).
    fun pause() {
        synchronized(lock) {
            if (AudioEngine.nativeIsRecording()) return
            stopLocked()
        }
    }

    // onResume: ambil lagi kalau belum jalan (idempoten).
    fun resume() {
        synchronized(lock) {
            val a = AudioEngine.nativeGetActiveBackend()
            val running = (a in OBOE..OPENSL) || (a == AUDIOTRACK && JavaAudioOutput.isRunning)
            if (running) active = a else startLocked(announce = false)
        }
    }

    // onDestroy: harus menghentikan thread AudioTrack SEBELUM engine native dibongkar.
    fun shutdown() {
        synchronized(lock) {
            JavaAudioOutput.stop()
            AudioEngine.nativeShutdown()
            active = 0
        }
    }

    // Dari Pengaturan. Ganti sistem langsung (tanpa restart app). Berjalan di background
    // karena membuka stream bisa memakan ratusan ms.
    fun setMode(context: Context, newMode: Int) {
        val c = context.applicationContext
        val m = newMode.coerceIn(AUTO, AUDIOTRACK)
        if (m == mode) return
        if (AudioEngine.nativeIsRecording()) {
            toast(c, "Hentikan rekaman dulu sebelum ganti sistem audio")
            return
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, m).apply()
        mode = m
        CoroutineScope(Dispatchers.Default).launch {
            synchronized(lock) {
                appCtx = c
                stopLocked()
                startLocked(announce = true)
            }
        }
    }

    // ---- internal (lock dipegang pemanggil) ----

    private fun stopLocked() {
        JavaAudioOutput.stop()
        AudioEngine.nativeStopOutput()
        active = 0
    }

    private fun startLocked(announce: Boolean) {
        val c = appCtx ?: return
        val (rate, frames) = deviceHints(c)

        AudioEngine.nativeConfigureOutput(mode, rate, frames)
        var result = AudioEngine.nativeStartOutput()
        if (result == 0) {
            // Semua sistem native gagal -> AudioTrack = jaring pengaman terakhir.
            AudioEngine.nativeConfigureOutput(AUDIOTRACK, rate, frames)
            result = AudioEngine.nativeStartOutput()
        }
        if (result == AUDIOTRACK && !JavaAudioOutput.start(rate, frames)) {
            AudioEngine.nativeStopOutput()
            result = 0
        }
        active = result

        if (!announce) return
        val wanted = if (mode == AUTO) OBOE else mode
        when {
            result == 0 -> toast(c, "Audio gagal dibuka di HP ini. Coba pilih sistem audio lain di Pengaturan.")
            result != wanted -> toast(c, "${nameOf(wanted)} tidak bisa dipakai di HP ini, otomatis memakai ${nameOf(result)}")
            mode != AUTO -> toast(c, "Sistem audio: ${nameOf(result)}")
        }
    }

    private fun deviceHints(c: Context): Pair<Int, Int> {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val rate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val frames = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        return rate to frames
    }

    private fun toast(c: Context, msg: String) {
        ui.post { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }
    }
}

// Backend AudioTrack. Tidak ada mixer di sini: thread ini hanya meminta engine native
// merender satu blok (nativeRenderBlock, lewat direct buffer - tanpa copy) lalu
// menyerahkannya ke AudioTrack. Mixing, rekam, loop, choke dll sama persis dengan backend lain.
internal object JavaAudioOutput {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    val isRunning: Boolean get() = running && thread?.isAlive == true

    fun start(sampleRateHint: Int, framesHint: Int): Boolean {
        stop()
        val rate = if (sampleRateHint in 8000..192000) sampleRateHint else 48000
        val chunk = if (framesHint in 64..2048) framesHint else 256
        val t = createTrack(rate, chunk) ?: return false
        try {
            t.play()
        } catch (_: Throwable) {
            try { t.release() } catch (_: Throwable) {}
            return false
        }
        synchronized(this) { track = t }
        running = true
        val th = Thread({ loop(rate, chunk) }, "AudioTrackOut")
        thread = th
        th.start()
        return true
    }

    fun stop() {
        running = false
        val th = thread
        thread = null
        // pause() membuat write() yang sedang menunggu langsung kembali.
        synchronized(this) {
            try { track?.pause(); track?.flush() } catch (_: Throwable) {}
        }
        try { th?.join(1000) } catch (_: InterruptedException) {}
        synchronized(this) {
            try { track?.release() } catch (_: Throwable) {}
            track = null
        }
    }

    private fun createTrack(rate: Int, chunk: Int): AudioTrack? {
        return try {
            val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            if (minBytes <= 0) return null
            val bufBytes = maxOf(minBytes, chunk * 2 * 4 * 2)   // minimal 2 blok
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                t.release()
                null
            } else t
        } catch (_: Throwable) {
            null
        }
    }

    private fun loop(rate: Int, chunk: Int) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) {}
        val bytes = chunk * 2 * 4
        val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        var failures = 0
        while (running) {
            val t = synchronized(this) { track } ?: break
            AudioEngine.nativeRenderBlock(buf, chunk, rate)
            buf.limit(bytes)
            buf.position(0)
            val w = try { t.write(buf, bytes, AudioTrack.WRITE_BLOCKING) } catch (_: Throwable) { -1 }
            if (w >= 0) { failures = 0; continue }
            if (!running) break
            // Track mati (mis. ganti headset/route): coba buat ulang beberapa kali.
            if (++failures > 3) { running = false; break }
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            val fresh = createTrack(rate, chunk) ?: continue
            try { fresh.play() } catch (_: Throwable) { try { fresh.release() } catch (_: Throwable) {}; continue }
            synchronized(this) {
                if (running) {
                    try { track?.release() } catch (_: Throwable) {}
                    track = fresh
                } else {
                    try { fresh.release() } catch (_: Throwable) {}
                }
            }
        }
    }
}
