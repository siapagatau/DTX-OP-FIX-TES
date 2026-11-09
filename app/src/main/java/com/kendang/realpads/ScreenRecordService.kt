package com.kendang.realpads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

// Rekam layar SENDIRI di dalam app (bukan screen recorder pihak ketiga):
//
//  - VIDEO: MediaProjection -> VirtualDisplay -> Surface -> MediaCodec (H.264).
//  - AUDIO: diambil LANGSUNG dari buffer PCM internal mixer native (sama seperti REC
//    audio-only), bukan AudioPlaybackCapture. Jalur audio exclusive tidak disentuh.
//    Encoding AAC dikerjakan AacPcmPump (dipakai bersama REC audio-only).
//  - Video+audio dimux jadi SATU .mp4 lewat MediaMuxer (lihat MuxerSink).
//
// PERBAIKAN "rekaman gagal / file kosong" (ringkasan akar masalah versi sebelumnya):
//
//  1. Thread audio tidak pernah berhenti sendiri saat STOP (drain native hampir tidak
//     pernah kosong selama recording_ masih true) -> join timeout -> muxer di-stop
//     paksa saat thread masih menulis -> file kosong/korup. Sekarang AacPcmPump
//     menutup dirinya sendiri secara deterministik (ambil ekor PCM + EOS).
//  2. Muxer butuh KEDUA track, tapi track audio hanya muncul kalau ada PCM baru. Saat
//     mulai/berhenti screen capture, sistem menarik paksa stream audio exclusive
//     (di-restart oleh PadEngine) -> PCM berhenti sesaat -> track audio tak muncul ->
//     muxer tidak start -> SEMUA frame video dibuang setelah 3 detik -> file kosong.
//     Sekarang audio diisi hening kalau native ketinggalan, dan MuxerSink MENAHAN
//     sample yang datang sebelum muxer start (bukan membuangnya).
//  3. Kalau salah satu track memang tidak pernah muncul, MuxerSink tetap menutup file
//     dengan track yang ada (bukan file kosong).
//  4. Resolusi video dibatasi (maks 1920x1080) + fallback ke 1280x720 kalau
//     encoder menolak konfigurasinya - HP dengan layar >1080p sering gagal configure
//     dan makin berat saat musik + pad + encoder jalan bersamaan.
//  5. startForegroundService() WAJIB diikuti startForeground() apa pun yang terjadi;
//     jalur error dulu lupa memanggilnya -> app crash.
//
// TRADE-OFF: REC audio-only dan REC LAYAR memakai flag recording_ tunggal di native,
// jadi tidak bisa jalan bersamaan (MainActivity yang menjaga).
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.kendang.realpads.action.START_SCREEN_RECORD"
        const val ACTION_STOP = "com.kendang.realpads.action.STOP_SCREEN_RECORD"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"

        private const val TAG = "ScreenRecordService"
        private const val NOTIF_CHANNEL_ID = "screen_record_channel"
        private const val NOTIF_ID = 4242
        private const val TIMEOUT_US = 10_000L
        private const val MAX_PENDING_BYTES = 32L * 1024 * 1024

        // Callback ke MainActivity. SELALU dipanggil di main thread.
        var onFinished: ((file: File, durationMs: Long) -> Unit)? = null
        var onError: ((message: String) -> Unit)? = null
        var onTick: ((elapsedMs: Long) -> Unit)? = null

        // Hasil yang selesai saat Activity tidak sedang mendengarkan (mis. Activity
        // di-recreate/dihancurkan sewaktu rekaman diproses) - TIDAK dibuang; diambil
        // MainActivity begitu callback terpasang lagi.
        private var undelivered: Pair<File, Long>? = null

        @Synchronized
        fun consumeUndelivered(): Pair<File, Long>? {
            val r = undelivered
            undelivered = null
            return r
        }

        @Synchronized
        private fun storeUndelivered(file: File, durationMs: Long) {
            undelivered?.first?.delete()
            undelivered = Pair(file, durationMs)
        }

        @Volatile
        var isSessionActive: Boolean = false

        private fun fitSize(w: Int, h: Int, maxLong: Int, maxShort: Int): Pair<Int, Int> {
            val longSide = maxOf(w, h).toDouble()
            val shortSide = minOf(w, h).toDouble()
            val scale = minOf(1.0, minOf(maxLong / longSide, maxShort / shortSide))
            var nw = (w * scale).toInt()
            var nh = (h * scale).toInt()
            nw -= nw % 2
            nh -= nh % 2
            return Pair(maxOf(nw, 2), maxOf(nh, 2))
        }
    }

    // ------------------------------------------------------------------
    // MuxerSink: satu-satunya pintu menulis ke MediaMuxer.
    //  - thread-safe (video & audio thread menulis bersamaan)
    //  - menahan sample yang datang sebelum muxer start (tidak dibuang)
    //  - menjaga timestamp per track selalu naik
    //  - finish() tetap menutup file walau salah satu track tidak pernah muncul
    // ------------------------------------------------------------------
    private class MuxerSink(path: String) {
        private class Pending(val video: Boolean, val data: ByteArray, val ptsUs: Long, val flags: Int)

        private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val lock = Any()
        private var videoTrack = -1
        private var audioTrack = -1
        private var started = false
        private var finished = false
        private var wrote = false
        private var pendingBytes = 0L
        private val pending = ArrayList<Pending>()
        private var lastVideoPts = -1L
        private var lastAudioPts = -1L

        fun addVideoTrack(format: MediaFormat) {
            synchronized(lock) {
                if (videoTrack < 0 && !started && !finished) {
                    videoTrack = muxer.addTrack(format)
                    maybeStartLocked()
                }
            }
        }

        fun addAudioTrack(format: MediaFormat) {
            synchronized(lock) {
                if (audioTrack < 0 && !started && !finished) {
                    audioTrack = muxer.addTrack(format)
                    maybeStartLocked()
                }
            }
        }

        private fun maybeStartLocked() {
            if (!started && videoTrack >= 0 && audioTrack >= 0) startLocked()
        }

        private fun startLocked() {
            muxer.start()
            started = true
            for (p in pending) {
                writeNowLocked(p.video, ByteBuffer.wrap(p.data), 0, p.data.size, p.ptsUs, p.flags)
            }
            pending.clear()
            pendingBytes = 0L
        }

        private fun writeNowLocked(video: Boolean, buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
            val track = if (video) videoTrack else audioTrack
            if (track < 0) return
            val info = MediaCodec.BufferInfo()
            info.set(offset, size, ptsUs, flags)
            muxer.writeSampleData(track, buf, info)
            wrote = true
        }

        // src: position/limit sudah menunjuk ke data sample (offset..offset+size).
        fun write(video: Boolean, src: ByteBuffer, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                if (finished) return
                var pts = info.presentationTimeUs
                val last = if (video) lastVideoPts else lastAudioPts
                if (pts <= last) pts = last + 1
                if (video) lastVideoPts = pts else lastAudioPts = pts

                if (started) {
                    writeNowLocked(video, src, info.offset, info.size, pts, info.flags)
                } else {
                    // Muxer belum start (menunggu track satunya): TAHAN, jangan dibuang.
                    if (pendingBytes + info.size > MAX_PENDING_BYTES) return
                    val copy = ByteArray(info.size)
                    src.position(info.offset)
                    src.get(copy, 0, info.size)
                    pending.add(Pending(video, copy, pts, info.flags))
                    pendingBytes += info.size
                }
            }
        }

        // Tutup file. Kalau muxer belum start (ada track yang tidak pernah muncul),
        // start dengan track yang ada supaya data yang sudah ditahan tetap tersimpan.
        // Return true kalau file berisi sample.
        fun finish(): Boolean = synchronized(lock) {
            if (finished) return@synchronized wrote
            finished = true
            Log.i(TAG, "finish: videoTrack=$videoTrack audioTrack=$audioTrack started=$started pending=${pending.size}")
            try {
                if (!started) {
                    if (videoTrack >= 0 || audioTrack >= 0) startLocked() else return@synchronized false
                }
                if (!wrote) return@synchronized false
                muxer.stop()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "muxer finish gagal", t)
                false
            }
        }

        fun release() {
            runCatching { muxer.release() }
        }
    }

    private class VideoSetup(val codec: MediaCodec, val surface: Surface, val width: Int, val height: Int)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    @Volatile private var sink: MuxerSink? = null
    private var pump: AacPcmPump? = null

    private var videoPtsBaseUs = Long.MIN_VALUE
    @Volatile private var lastVideoPtsUs = 0L

    private val running = AtomicBoolean(false)
    // True dari STOP sampai file selesai ditutup & dilaporkan ke UI.
    private val finishing = AtomicBoolean(false)
    private var startTimeNs = 0L

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private lateinit var outputFile: File

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun reportError(message: String) {
        postMain { onError?.invoke(message) }
    }

    // Error dari thread encoder: hentikan bersih (sekali saja). Apa yang sudah sempat
    // tertulis tetap dipakai kalau file-nya valid (lihat stopRecordingInternal).
    private fun handleEncoderFailure(label: String, t: Throwable) {
        Log.e(TAG, "$label gagal", t)
        if (running.get()) {
            stopRecordingInternal(errorMessage = "$label error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // Timestamp frame VirtualDisplay itu clock sistem (uptime), bukan dari 0; audio
    // dihitung dari 0. Normalisasi video ke titik nol yang sama dengan audio.
    private fun normalizeVideoPts(rawUs: Long): Long {
        if (videoPtsBaseUs == Long.MIN_VALUE) {
            val startUs = startTimeNs / 1000L
            videoPtsBaseUs = if (kotlin.math.abs(rawUs - startUs) < 5_000_000L) startUs else rawUs
        }
        return (rawUs - videoPtsBaseUs).coerceAtLeast(0L)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User cabut izin lewat notifikasi sistem -> berhenti bersih & simpan
            // apa yang sudah terekam.
            stopRecordingInternal(errorMessage = null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AudioFileRecorder.purgeStale(cacheDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // WAJIB dipanggil lebih dulu apa pun yang terjadi setelahnya: sistem
                // mengharuskan startForeground() setelah startForegroundService(),
                // kalau tidak app di-crash ("did not then call startForeground").
                if (!startForegroundNotification()) {
                    reportError("Gagal menampilkan notifikasi rekam layar")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (running.get()) return START_NOT_STICKY
                if (finishing.get()) {
                    reportError("Rekaman sebelumnya masih diproses, coba lagi sebentar")
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val width = intent.getIntExtra(EXTRA_WIDTH, 0)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
                val density = intent.getIntExtra(EXTRA_DENSITY, DisplayMetrics.DENSITY_DEFAULT)
                if (resultData == null || width <= 0 || height <= 0) {
                    reportError("Izin rekam layar tidak lengkap")
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRecording(resultCode, resultData, width, height, density)
            }
            ACTION_STOP -> {
                if (running.get()) {
                    stopRecordingInternal(errorMessage = null)
                } else if (!finishing.get()) {
                    // Tidak ada yang direkam (mis. service sempat dimatikan sistem).
                    // Kabari UI supaya tombol tidak nyangkut di "MEMPROSES".
                    reportError("Rekaman layar sudah tidak berjalan")
                    stopSelf()
                }
            }
            else -> if (!running.get() && !finishing.get()) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // SETUP
    // ---------------------------------------------------------------------

    private fun createVideoEncoder(width: Int, height: Int): VideoSetup {
        val candidates = listOf(
            fitSize(width, height, 1920, 1080),
            fitSize(width, height, 1280, 720),
        ).distinct()
        var lastError: Throwable? = null
        for ((w, h) in candidates) {
            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * 3).coerceIn(2_000_000L, 8_000_000L).toInt())
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    // VirtualDisplay hanya mengirim frame kalau layar BERUBAH. Opsi ini
                    // mengulang frame sebelumnya (~30 fps) supaya track video tidak
                    // lebih pendek dari audio saat layar diam.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 33_333L)
                }
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                created = codec
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                Log.i(TAG, "video encoder ${w}x$h (layar ${width}x$height)")
                return VideoSetup(codec, surface, w, h)
            } catch (t: Throwable) {
                Log.w(TAG, "video encoder ${w}x$h gagal, coba ukuran lain", t)
                lastError = t
                runCatching { created?.release() }
            }
        }
        throw lastError ?: IllegalStateException("Tidak ada encoder video yang bisa dipakai")
    }

    private fun startRecording(resultCode: Int, resultData: Intent, width: Int, height: Int, densityDpi: Int) {
        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Gagal membuat MediaProjection")
            mediaProjection = projection
            // Callback WAJIB terdaftar sebelum createVirtualDisplay (Android 14+).
            projection.registerCallback(projectionCallback, mainHandler)

            outputFile = File(cacheDir, "screenrec_${System.currentTimeMillis()}.mp4")
            val muxerSink = MuxerSink(outputFile.absolutePath)
            sink = muxerSink

            // ---- VIDEO ----
            val v = createVideoEncoder(width, height)
            videoEncoder = v.codec
            inputSurface = v.surface

            virtualDisplay = projection.createVirtualDisplay(
                "KendangScreenRecord",
                v.width, v.height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                v.surface, null, null
            ) ?: throw IllegalStateException("Gagal membuat VirtualDisplay")

            // ---- AUDIO ----
            val audioPump = AacPcmPump(
                isRunning = { running.get() },
                onFormat = { muxerSink.addAudioTrack(it) },
                onSample = { data, info -> muxerSink.write(false, data, info) },
            )
            audioPump.prepare()
            pump = audioPump

            videoPtsBaseUs = Long.MIN_VALUE
            lastVideoPtsUs = 0L

            running.set(true)
            isSessionActive = true
            // startTimeNs SEBELUM nativeStartRecording: PCM pertama native = titik nol audio.
            startTimeNs = System.nanoTime()
            AudioEngine.nativeStartRecording()

            videoThread = Thread({ runVideoEncoderLoop(v.codec) }, "ScreenRec-Video").also { it.start() }
            audioThread = Thread({
                try {
                    audioPump.run(startTimeNs)
                } catch (t: Throwable) {
                    handleEncoderFailure("Audio encoder", t)
                }
            }, "ScreenRec-Audio").also { it.start() }

            tickerJob = serviceScope.launch {
                while (running.get()) {
                    delay(500)
                    val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L
                    postMain { onTick?.invoke(elapsedMs) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording gagal", t)
            reportError(t.message ?: "Gagal memulai rekam layar")
            running.set(false)
            runCatching { AudioEngine.nativeStopRecording() }
            releaseAllQuietly()
            runCatching { if (::outputFile.isInitialized) outputFile.delete() }
            isSessionActive = false
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------
    // VIDEO LOOP
    // ---------------------------------------------------------------------

    private fun runVideoEncoderLoop(encoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var eos = false
        try {
            while (!eos) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink?.addVideoTrack(encoder.outputFormat)
                } else if (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        val data = encoder.getOutputBuffer(outIndex)
                        if (data != null) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            bufferInfo.presentationTimeUs = normalizeVideoPts(bufferInfo.presentationTimeUs)
                            lastVideoPtsUs = bufferInfo.presentationTimeUs
                            sink?.write(true, data, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
        } catch (t: Throwable) {
            handleEncoderFailure("Video encoder", t)
        }
    }

    // ---------------------------------------------------------------------
    // STOP
    // ---------------------------------------------------------------------

    private fun joinQuietly(t: Thread?, timeoutMs: Long) {
        try {
            t?.join(timeoutMs)
        } catch (_: InterruptedException) {
        }
    }

    private fun stopRecordingInternal(errorMessage: String?) {
        if (!running.getAndSet(false)) return // cegah double-stop
        finishing.set(true)
        isSessionActive = false
        tickerJob?.cancel()

        val vThread = videoThread
        val aThread = audioThread

        serviceScope.launch {
            // 1. Tunggu thread audio selesai: AacPcmPump melihat running=false, mengambil
            //    ekor PCM terakhir, kirim EOS, dan menulis sisa sample. Selama menunggu,
            //    video tetap dapat frame (repeat-frame) sehingga durasinya menutup audio.
            joinQuietly(aThread, 10_000)

            // 2. Baru sinyal EOS video, tunggu thread video menulis tail-nya.
            try {
                videoEncoder?.signalEndOfInputStream()
            } catch (_: Throwable) {
            }
            joinQuietly(vThread, 8_000)

            // Jaring pengaman: kalau thread audio mati karena error sebelum sempat
            // mematikan recording_ native.
            runCatching { AudioEngine.nativeStopRecording() }

            // 3. Tutup file (aman terhadap thread yang masih menulis: lihat MuxerSink).
            val wroteData = sink?.finish() ?: false
            val pumpFrames = pump?.framesSubmitted ?: 0L
            releaseAllQuietly()

            val durationMs = (System.nanoTime() - startTimeNs) / 1_000_000L
            Log.i(
                TAG,
                "selesai: total=${durationMs}ms video=${lastVideoPtsUs / 1000}ms " +
                    "audio=${pumpFrames * 1000L / AacPcmPump.SAMPLE_RATE}ms data=$wroteData err=$errorMessage"
            )

            withContext(Dispatchers.Main) {
                val fileOk = wroteData && outputFile.exists() && outputFile.length() > 0L
                if (fileOk) {
                    // Error di tengah jalan pun tidak membuang rekaman yang sudah valid.
                    val cb = onFinished
                    if (cb != null) cb(outputFile, durationMs) else storeUndelivered(outputFile, durationMs)
                } else {
                    runCatching { outputFile.delete() }
                    onError?.invoke(errorMessage ?: "Rekaman layar gagal disimpan (tidak ada data yang terekam)")
                }
                runCatching { stopForegroundCompat() }
                finishing.set(false)
                stopSelf()
            }
        }
    }

    private fun releaseAllQuietly() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null
        pump?.release()
        pump = null
        sink?.release()
        sink = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    override fun onDestroy() {
        // Jaring pengaman kalau service dimatikan sistem di luar alur stop normal.
        if (running.getAndSet(false)) {
            isSessionActive = false
            runCatching { AudioEngine.nativeStopRecording() }
            releaseAllQuietly()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // NOTIFIKASI (wajib buat foreground service tipe mediaProjection)
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "Rekam Layar", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifikasi selagi rekam layar+audio kendang berjalan"
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundNotification(): Boolean {
        return try {
            val notification: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("Merekam layar")
                .setContentText("RealPads Kendang sedang merekam layar & audio")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground gagal", t)
            false
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
