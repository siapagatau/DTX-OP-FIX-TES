package com.kendang.realpads

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

// REC audio-only (tombol REC biasa). Beda dari versi lama yang menimbun SELURUH PCM di
// RAM (native deque -> vector -> ShortArray JVM = 3 salinan, baru di-encode setelah
// user pilih lokasi simpan): itu penyebab "gagal menyimpan" di rekaman panjang, dan
// hasilnya hilang total kalau Activity/proses keburu di-kill saat dialog simpan terbuka.
//
// Sekarang PCM ditarik terus (~tiap 10-30ms) dan langsung di-encode ke file .m4a di
// cache selama rekaman berjalan (lewat AacPcmPump, yang sama dengan REC LAYAR). Waktu
// STOP tinggal menutup file -> cepat, memori konstan berapa pun durasinya, dan file
// sudah ada di disk sebelum user memilih menyimpan.
class AudioFileRecorder(private val cacheDir: File) {

    class Output(val file: File, val durationMs: Long)

    companion object {
        private const val TAG = "AudioFileRecorder"

        @Volatile
        var current: AudioFileRecorder? = null
            private set

        // Dipakai MainActivity.onDestroy: hentikan & buang rekaman yang masih jalan
        // SEBELUM engine native dibongkar (thread pompa masih menarik PCM dari engine).
        fun cancelCurrent() {
            current?.cancel()
        }

        // Hapus sisa file rekaman sementara yang sudah lama (>24 jam) di cache - mis. dari
        // sesi yang app-nya keburu di-kill sebelum sempat disimpan/dibuang.
        fun purgeStale(dir: File) {
            val cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L
            dir.listFiles()?.forEach { f ->
                val n = f.name
                if ((n.startsWith("screenrec_") || n.startsWith("rec_")) && f.lastModified() < cutoff) {
                    f.delete()
                }
            }
        }
    }

    private val file = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var pump: AacPcmPump? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var wroteSample = false

    @Volatile
    private var failure: Throwable? = null

    // Mulai rekam. Return false kalau gagal (encoder/muxer tidak bisa dibuat).
    fun start(): Boolean {
        purgeStale(cacheDir)
        return try {
            val p = AacPcmPump(
                isRunning = { running },
                onFormat = { format -> onFormat(format) },
                onSample = { data, info -> onSample(data, info) },
            )
            p.prepare()
            pump = p
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            running = true
            val startNs = System.nanoTime()
            AudioEngine.nativeStartRecording()
            current = this
            thread = Thread({
                try {
                    p.run(startNs)
                } catch (t: Throwable) {
                    Log.e(TAG, "pompa audio gagal", t)
                    failure = t
                }
            }, "AudioRec").also { it.start() }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start gagal", t)
            running = false
            runCatching { AudioEngine.nativeStopRecording() }
            cleanup()
            current = null
            false
        }
    }

    private fun onFormat(format: MediaFormat) {
        val m = muxer ?: return
        if (muxerStarted) return
        trackIndex = m.addTrack(format)
        m.start()
        muxerStarted = true
    }

    private fun onSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!muxerStarted) return
        muxer?.writeSampleData(trackIndex, data, info)
        wroteSample = true
    }

    // BLOCKING (sampai beberapa ratus ms) -> panggil dari background thread.
    // Return null kalau tidak ada audio yang berhasil ditulis.
    fun stop(): Output? {
        running = false
        val t = thread
        try {
            t?.join(15_000)
        } catch (_: InterruptedException) {
        }
        if (t != null && t.isAlive) {
            // Thread macet - jangan tutup muxer dari thread lain (bisa korup), menyerah.
            Log.e(TAG, "thread audio tidak selesai dalam 15 detik")
            runCatching { AudioEngine.nativeStopRecording() }
            cleanup()
            file.delete()
            current = null
            return null
        }
        runCatching { AudioEngine.nativeStopRecording() } // jaring pengaman
        val durationMs = (pump?.framesSubmitted ?: 0L) * 1000L / AacPcmPump.SAMPLE_RATE
        val ok = finishMuxer()
        cleanup()
        current = null
        if (failure != null) Log.w(TAG, "selesai dengan error", failure)
        return if (ok && file.exists() && file.length() > 0L) {
            Output(file, durationMs)
        } else {
            file.delete()
            null
        }
    }

    // Batalkan tanpa menyimpan apa pun (mis. Activity dihancurkan).
    fun cancel() {
        running = false
        val t = thread
        try {
            t?.join(2_000)
        } catch (_: InterruptedException) {
        }
        runCatching { AudioEngine.nativeStopRecording() }
        if (t == null || !t.isAlive) {
            finishMuxer()
            cleanup()
        }
        file.delete()
        current = null
    }

    private fun finishMuxer(): Boolean {
        val m = muxer ?: return false
        if (!muxerStarted || !wroteSample) return false
        return try {
            m.stop()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "muxer.stop gagal", t)
            false
        }
    }

    private fun cleanup() {
        runCatching { muxer?.release() }
        muxer = null
        pump?.release()
        pump = null
    }
}
