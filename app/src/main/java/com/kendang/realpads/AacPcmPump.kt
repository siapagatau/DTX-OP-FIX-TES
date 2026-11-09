package com.kendang.realpads

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Pompa PCM -> AAC yang dipakai BERSAMA oleh REC audio-only (AudioFileRecorder) dan
// REC LAYAR (ScreenRecordService). Sumber PCM: mixdown internal native
// (AudioEngine.nativeDrainRecording / nativeStopRecording), persis seperti dulu.
//
// Kenapa dibuat ulang (akar masalah "rekaman gagal / file kosong"):
//
//  1. STOP TIDAK PERNAH SELESAI. Dulu thread audio baru kirim EOS ke encoder kalau
//     nativeDrainRecording() balikin array KOSONG. Padahal selama recording_ native
//     masih true, callback audio terus mengisi buffer tiap beberapa ms, jadi drain
//     hampir tidak pernah kosong -> thread audio tidak pernah berhenti sendiri ->
//     join() timeout -> muxer di-stop paksa sementara thread masih nulis -> file
//     kosong/korup. Sekarang saat stop diminta, pompa ini SENDIRI memanggil
//     nativeStopRecording() (mematikan recording_ + mengambil ekor PCM terakhir),
//     meng-encode ekornya, lalu kirim EOS. Berhenti dijamin.
//
//  2. TRACK AUDIO BISA TIDAK PERNAH MUNCUL. Dulu audio hanya di-feed kalau ada PCM baru.
//     Kalau stream audio native lagi di-restart sistem (terjadi persis saat mulai/
//     berhenti screen capture: stream exclusive ditarik paksa), atau musik/pad lagi
//     diam tapi callback tertahan, encoder tidak dapat input -> tidak pernah
//     mengeluarkan format -> muxer (butuh SEMUA track) tidak pernah start -> file
//     kosong. Sekarang pompa ini "clock-driven": kalau PCM native ketinggalan dari
//     waktu nyata lebih dari ~100ms, sisanya diisi HENING. Track audio pasti jalan
//     dan durasinya selalu sinkron dengan video.
class AacPcmPump(
    private val isRunning: () -> Boolean,
    private val onFormat: (MediaFormat) -> Unit,
    private val onSample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        private const val BIT_RATE = 192_000
        private const val TIMEOUT_US = 10_000L
        // Ketertinggalan PCM native dari waktu nyata yang masih dianggap wajar.
        private const val SILENCE_GAP_FRAMES = SAMPLE_RATE / 10 // 100ms
        private const val TAG = "AacPcmPump"
    }

    private var codec: MediaCodec? = null

    // Jumlah frame stereo yang sudah dimasukkan ke encoder (dasar timestamp audio,
    // sample-accurate - bukan wall-clock).
    @Volatile
    var framesSubmitted = 0L
        private set

    @Volatile
    var lastPtsUs = 0L
        private set

    // Antrean PCM yang belum masuk encoder.
    private val queue = ArrayDeque<ShortArray>()
    private var queueOffset = 0 // posisi baca (dalam sample) di queue.first()
    private var queuedShorts = 0L

    // Dipanggil dari thread yang akan menjalankan run() sebelum start - boleh throw.
    fun prepare() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (t: Throwable) {
            runCatching { c.release() }
            throw t
        }
        codec = c
    }

    fun release() {
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun enqueue(pcm: ShortArray?) {
        if (pcm == null || pcm.isEmpty()) return
        queue.addLast(pcm)
        queuedShorts += pcm.size
    }

    // Hanya dipanggil dari thread run().
    // startNs = System.nanoTime() saat AudioEngine.nativeStartRecording() dipanggil.
    fun run(startNs: Long) {
        val c = codec ?: throw IllegalStateException("AacPcmPump belum di-prepare()")
        val info = MediaCodec.BufferInfo()
        var stopping = false
        var eosSent = false
        var outputDone = false

        // Isi hening kalau PCM yang sudah terkumpul ketinggalan dari jam nyata.
        fun padSilenceIfBehind() {
            val elapsedNs = System.nanoTime() - startNs
            val expectedFrames = elapsedNs * SAMPLE_RATE / 1_000_000_000L
            val haveFrames = framesSubmitted + queuedShorts / CHANNELS
            val missing = expectedFrames - haveFrames
            if (missing > SILENCE_GAP_FRAMES) {
                enqueue(ShortArray((missing * CHANNELS).toInt()))
            }
        }

        while (!outputDone) {
            // ---- 1. KUMPULKAN PCM ----
            if (!stopping) {
                if (!isRunning()) {
                    // Stop diminta: ambil ekor PCM terakhir + matikan recording_ native.
                    stopping = true
                    val tail = try {
                        AudioEngine.nativeStopRecording()
                    } catch (t: Throwable) {
                        Log.e(TAG, "nativeStopRecording gagal", t)
                        null
                    }
                    enqueue(tail)
                    padSilenceIfBehind() // supaya durasi audio tetap menutup durasi video
                } else {
                    val fresh = try {
                        AudioEngine.nativeDrainRecording()
                    } catch (t: Throwable) {
                        Log.e(TAG, "nativeDrainRecording gagal", t)
                        null
                    }
                    enqueue(fresh)
                    padSilenceIfBehind()
                }
            }

            // ---- 2. FEED INPUT ----
            if (!eosSent) {
                while (queuedShorts > 0) {
                    val inIndex = c.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buf = c.getInputBuffer(inIndex)
                        ?: throw IllegalStateException("input buffer encoder audio null")
                    buf.clear()
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val sb = buf.asShortBuffer()
                    var capacity = buf.capacity() / 2
                    // Selalu kelipatan penuh frame stereo.
                    capacity -= capacity % CHANNELS
                    var written = 0
                    while (written < capacity && queue.isNotEmpty()) {
                        val head = queue.first()
                        val take = minOf(capacity - written, head.size - queueOffset)
                        sb.put(head, queueOffset, take)
                        queueOffset += take
                        written += take
                        if (queueOffset >= head.size) {
                            queue.removeFirst()
                            queueOffset = 0
                        }
                    }
                    queuedShorts -= written
                    val pts = framesSubmitted * 1_000_000L / SAMPLE_RATE
                    c.queueInputBuffer(inIndex, 0, written * 2, pts, 0)
                    framesSubmitted += (written / CHANNELS).toLong()
                }
                if (stopping && queuedShorts == 0L) {
                    val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        c.queueInputBuffer(
                            inIndex, 0, 0,
                            framesSubmitted * 1_000_000L / SAMPLE_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosSent = true
                    }
                }
            }

            // ---- 3. DRAIN OUTPUT ----
            // Panggilan pertama menunggu sampai TIMEOUT_US (sekaligus jadi "tidur"
            // loop ini supaya gak busy-loop), berikutnya langsung tanpa nunggu.
            var timeout = TIMEOUT_US
            while (true) {
                val outIndex = c.dequeueOutputBuffer(info, timeout)
                timeout = 0
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onFormat(c.outputFormat)
                    continue
                }
                if (outIndex < 0) continue // INFO_OUTPUT_BUFFERS_CHANGED (deprecated)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (info.size > 0) {
                    val data = c.getOutputBuffer(outIndex)
                    if (data != null) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        lastPtsUs = info.presentationTimeUs
                        onSample(data, info)
                    }
                }
                val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                c.releaseOutputBuffer(outIndex, false)
                if (isEos) {
                    outputDone = true
                    break
                }
            }
        }
    }
}
