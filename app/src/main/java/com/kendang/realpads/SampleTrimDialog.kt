package com.kendang.realpads

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Suara hasil decode (lokal / online) yang lagi nunggu user atur pemotongannya
// sebelum beneran disimpan ke pad. `wav` = PCM siap pakai (48kHz, sudah
// dinormalisasi), `wavBytes` = bytes WAV kanonik yang akan disimpan kalau TIDAK
// dipotong (jalur lama, tidak ada perubahan apa pun pada suaranya).
// Sengaja class biasa (bukan data class) karena isinya array besar.
class PendingTrim(
    val pad: Int,
    val preset: Int,
    val wav: WavData,
    val wavBytes: ByteArray,
    val fromOnline: Boolean
)

object SampleTrimmer {
    // Panjang minimal bagian yang dipilih - biar gak bisa dipotong jadi kosong.
    const val MIN_TRIM_MS = 10

    // Fade super pendek di sisi yang DIPOTONG (bukan di ujung asli file) supaya
    // tidak ada bunyi "klik" akibat memotong gelombang di tengah ayunan.
    // Fade-in sengaja lebih pendek dari fade-out supaya attack pukulan tetap tajam.
    private const val FADE_IN_MS = 2
    private const val FADE_OUT_MS = 8

    fun frameCount(w: WavData): Int = if (w.channels > 0) w.pcm.size / w.channels else 0

    // Potong [startFrame, endFrame) -> WAV kanonik baru. Hasilnya SELALU lewat
    // WavLoader.parse lagi (bukan PCM potongan langsung), supaya level suara
    // sekarang PERSIS sama dengan yang akan dimuat ulang saat app dibuka lagi
    // (WavLoader.parse menormalisasi volume tiap load). Return null kalau gagal.
    fun trim(src: WavData, startFrame: Int, endFrame: Int): Pair<WavData, ByteArray>? {
        val ch = src.channels
        if (ch <= 0) return null
        val total = src.pcm.size / ch
        if (total <= 0) return null
        val s = startFrame.coerceIn(0, total - 1)
        val e = endFrame.coerceIn(s + 1, total)
        val len = e - s
        val out = src.pcm.copyOfRange(s * ch, e * ch)

        val fadeIn = if (s > 0) min(src.sampleRate * FADE_IN_MS / 1000, len / 4) else 0
        val fadeOut = if (e < total) min(src.sampleRate * FADE_OUT_MS / 1000, len / 4) else 0
        for (i in 0 until fadeIn) {
            val g = i.toFloat() / fadeIn
            for (c in 0 until ch) {
                val idx = i * ch + c
                out[idx] = (out[idx] * g).toInt().toShort()
            }
        }
        for (i in 0 until fadeOut) {
            val g = i.toFloat() / fadeOut
            val frame = len - 1 - i
            for (c in 0 until ch) {
                val idx = frame * ch + c
                out[idx] = (out[idx] * g).toInt().toShort()
            }
        }

        val bytes = CompressedSampleLoader.encodeToWavBytes(out, ch, src.sampleRate)
        val parsed = WavLoader.parse(bytes) ?: return null
        return parsed to bytes
    }

    // Cari bagian yang benar-benar berbunyi (di atas -40 dB dari puncak), dengan
    // sedikit ruang di awal & akhir. Dipakai tombol "BUANG HENING".
    fun detectSoundRange(src: WavData): Pair<Int, Int>? {
        val ch = src.channels
        if (ch <= 0) return null
        val frames = src.pcm.size / ch
        if (frames <= 0) return null
        var peak = 0
        for (s in src.pcm) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak == 0) return null
        val thr = max(1, (peak * 0.01).toInt())

        var first = -1
        var f = 0
        while (f < frames && first < 0) {
            for (c in 0 until ch) {
                if (abs(src.pcm[f * ch + c].toInt()) >= thr) { first = f; break }
            }
            f++
        }
        if (first < 0) return null
        var last = first
        f = frames - 1
        var found = false
        while (f > first && !found) {
            for (c in 0 until ch) {
                if (abs(src.pcm[f * ch + c].toInt()) >= thr) { last = f; found = true; break }
            }
            f--
        }
        val padStart = src.sampleRate * 5 / 1000
        val padEnd = src.sampleRate * 30 / 1000
        return max(0, first - padStart) to min(frames, last + 1 + padEnd)
    }
}

// Pemutar preview KHUSUS dialog ini (AudioTrack stream), terpisah total dari
// native engine/Oboe yang dipakai pad - jadi tidak mengganggu suara pad.
class TrimPreviewPlayer {
    private class Session(val track: AudioTrack, val startFrame: Int) {
        @Volatile var cancelled = false
        @Volatile var finished = false
    }

    @Volatile
    private var session: Session? = null

    val isActive: Boolean
        get() = session?.let { !it.finished && !it.cancelled } ?: false

    fun headFrame(): Int {
        val s = session ?: return 0
        return try {
            s.startFrame + s.track.playbackHeadPosition
        } catch (e: Exception) {
            s.startFrame
        }
    }

    fun start(wav: WavData, startFrame: Int, endFrame: Int): Boolean {
        stop()
        val ch = wav.channels
        if (ch != 1 && ch != 2) return false
        if (endFrame <= startFrame) return false
        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(wav.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(wav.sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return false
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            try { track.release() } catch (e: Exception) { }
            return false
        }

        val s = Session(track, startFrame)
        session = s
        val pcm = wav.pcm
        Thread {
            try {
                track.play()
                var pos = startFrame
                val chunk = 2048
                while (pos < endFrame && !s.cancelled) {
                    val n = min(chunk, endFrame - pos)
                    val w = track.write(pcm, pos * ch, n * ch)
                    if (w <= 0) break
                    pos += w / ch
                }
                // Tunggu sisa buffer selesai diputar (maks ~4 detik).
                val target = endFrame - startFrame
                var guard = 0
                while (!s.cancelled && track.playbackHeadPosition < target && guard < 400) {
                    Thread.sleep(10)
                    guard++
                }
            } catch (e: Exception) {
                // abaikan: track sudah dilepas / dibatalkan
            } finally {
                s.finished = true
                try { track.stop() } catch (e: Exception) { }
                try { track.release() } catch (e: Exception) { }
            }
        }.start()
        return true
    }

    fun stop() {
        val s = session ?: return
        s.cancelled = true
        try {
            s.track.pause()
            s.track.flush()
        } catch (e: Exception) {
            // track sudah dilepas thread-nya
        }
        session = null
    }
}

private fun computePeaks(wav: WavData, buckets: Int): FloatArray {
    val ch = wav.channels.coerceAtLeast(1)
    val frames = wav.pcm.size / ch
    val out = FloatArray(buckets)
    if (frames <= 0) return out
    var gmax = 1
    for (b in 0 until buckets) {
        val f0 = (b.toLong() * frames / buckets).toInt()
        val f1 = max(f0 + 1, ((b + 1).toLong() * frames / buckets).toInt()).coerceAtMost(frames)
        var m = 0
        for (i in f0 * ch until f1 * ch) {
            val v = abs(wav.pcm[i].toInt())
            if (v > m) m = v
        }
        out[b] = m.toFloat()
        if (m > gmax) gmax = m
    }
    for (b in out.indices) out[b] = out[b] / gmax
    return out
}

private fun fmtSec(frames: Int, sampleRate: Int): String =
    String.format(Locale.US, "%.3f s", frames.toDouble() / sampleRate.coerceAtLeast(1))

// Dialog potong suara: muncul setelah user memilih file suara (lokal ATAU
// online) di alur SET SUARA, sebelum suaranya disimpan ke pad.
// Frame yang dikembalikan lewat onConfirm: [startFrame, endFrame) dalam frame
// (bukan sample) pada sample rate wav.sampleRate. Kalau tidak diubah, hasilnya
// (0, totalFrames) dan pemanggil memakai jalur simpan lama tanpa memotong.
@Composable
fun SampleTrimDialog(
    pad: Int,
    wav: WavData,
    onConfirm: (startFrame: Int, endFrame: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val total = SampleTrimmer.frameCount(wav).coerceAtLeast(1)
    val sr = wav.sampleRate.coerceAtLeast(1)
    val minFrames = min(total, max(1, sr * SampleTrimmer.MIN_TRIM_MS / 1000))
    val minFrac = minFrames.toDouble() / total
    val nudgeFrac = (sr / 100).toDouble() / total // 10 ms

    var startFrac by remember { mutableStateOf(0.0) }
    var endFrac by remember { mutableStateOf(1.0) }

    val startFrame = (startFrac * total).roundToInt().coerceIn(0, total - 1)
    val endFrame = (endFrac * total).roundToInt().coerceIn(startFrame + 1, total)

    val peaks by produceState<FloatArray?>(null, wav) {
        value = withContext(Dispatchers.Default) { computePeaks(wav, 320) }
    }

    // --- preview ---
    val player = remember { TrimPreviewPlayer() }
    var playing by remember { mutableStateOf(false) }
    var playFrame by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(playing) {
        while (playing) {
            playFrame = player.headFrame()
            if (!player.isActive) playing = false
            delay(16)
        }
    }
    fun stopPreview() {
        player.stop()
        playing = false
    }

    fun setStart(v: Double) {
        startFrac = v.coerceIn(0.0, (endFrac - minFrac).coerceAtLeast(0.0))
    }
    fun setEnd(v: Double) {
        endFrac = v.coerceIn((startFrac + minFrac).coerceAtMost(1.0), 1.0)
    }

    val accent = ThemeState.accentColor
    val accentText = ThemeState.accentTextColor

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = PanelColor,
        title = {
            Text("Potong Suara - PAD $pad", color = PadTextColor, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Geser garis kiri/kanan untuk memilih bagian suara yang dipakai. " +
                        "Kalau tidak perlu dipotong, langsung tekan SET SUARA.",
                    color = TextDim,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                TrimWaveform(
                    peaks = peaks,
                    startFrac = startFrac,
                    endFrac = endFrac,
                    playFrac = if (playing) playFrame.toDouble() / total else null,
                    accent = accent,
                    onGrab = { stopPreview() },
                    onDragHandle = { isStart, d ->
                        if (isStart) setStart(startFrac + d) else setEnd(endFrac + d)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CtrlPanelColor)
                )

                Text(
                    "Awal ${fmtSec(startFrame, sr)}  •  Akhir ${fmtSec(endFrame, sr)}\n" +
                        "Hasil ${fmtSec(endFrame - startFrame, sr)} (dari ${fmtSec(total, sr)})",
                    color = PadTextColor,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                NudgeRow(
                    label = "AWAL",
                    onMinus = { stopPreview(); setStart(startFrac - nudgeFrac) },
                    onPlus = { stopPreview(); setStart(startFrac + nudgeFrac) }
                )
                NudgeRow(
                    label = "AKHIR",
                    onMinus = { stopPreview(); setEnd(endFrac - nudgeFrac) },
                    onPlus = { stopPreview(); setEnd(endFrac + nudgeFrac) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (playing) {
                                stopPreview()
                            } else {
                                val ok = player.start(wav, startFrame, endFrame)
                                if (ok) {
                                    playFrame = startFrame
                                    playing = true
                                } else {
                                    Toast.makeText(context, "Preview tidak tersedia untuk suara ini", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accentText)
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (playing) "STOP" else "PLAY", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                    MiniButton("BUANG HENING", Modifier.weight(1.3f)) {
                        stopPreview()
                        val r = SampleTrimmer.detectSoundRange(wav)
                        if (r == null) {
                            Toast.makeText(context, "Tidak ada bagian hening yang bisa dibuang", Toast.LENGTH_SHORT).show()
                        } else {
                            startFrac = r.first.toDouble() / total
                            endFrac = (r.second.toDouble() / total).coerceAtMost(1.0)
                        }
                    }
                    MiniButton("RESET", Modifier.weight(0.8f)) {
                        stopPreview()
                        startFrac = 0.0
                        endFrac = 1.0
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    stopPreview()
                    onConfirm(startFrame, endFrame)
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = accentText)
            ) {
                Text("SET SUARA", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            Button(
                onClick = {
                    stopPreview()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
            ) {
                Text("BATAL", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    )
}

@Composable
private fun MiniButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun NudgeRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(54.dp)
        )
        MiniButton("-10 ms", Modifier.weight(1f), onMinus)
        MiniButton("+10 ms", Modifier.weight(1f), onPlus)
    }
}

// Gambar gelombang + dua garis penanda (awal/akhir) yang bisa digeser. Bagian di
// luar pilihan digelapkan. Garis yang digeser = yang paling dekat dengan jari
// saat mulai drag. Pakai drag HORIZONTAL saja supaya tidak rebutan dengan
// scroll vertikal isi dialog.
@Composable
private fun TrimWaveform(
    peaks: FloatArray?,
    startFrac: Double,
    endFrac: Double,
    playFrac: Double?,
    accent: Color,
    onGrab: () -> Unit,
    onDragHandle: (isStart: Boolean, deltaFrac: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val curStart by rememberUpdatedState(startFrac)
    val curEnd by rememberUpdatedState(endFrac)
    val curGrab by rememberUpdatedState(onGrab)
    val curDrag by rememberUpdatedState(onDragHandle)
    var activeIsStart by remember { mutableStateOf(true) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { off ->
                    val w = size.width.toFloat()
                    val sx = (curStart * w).toFloat()
                    val ex = (curEnd * w).toFloat()
                    val dS = abs(off.x - sx)
                    val dE = abs(off.x - ex)
                    activeIsStart = if (dS == dE) off.x < sx else dS < dE
                    curGrab()
                },
                onDragEnd = {},
                onDragCancel = {},
                onHorizontalDrag = { change, dx ->
                    change.consume()
                    val w = size.width.toFloat()
                    if (w > 0f) curDrag(activeIsStart, (dx / w).toDouble())
                }
            )
        }
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val sx = (startFrac * w).toFloat()
        val ex = (endFrac * w).toFloat()

        val p = peaks
        if (p != null && p.isNotEmpty()) {
            val barW = w / p.size
            for (i in p.indices) {
                val x = (i + 0.5f) * barW
                val half = max(1f, p[i] * h * 0.46f)
                drawLine(
                    color = PadTextColor,
                    start = Offset(x, mid - half),
                    end = Offset(x, mid + half),
                    strokeWidth = max(1f, barW * 0.8f)
                )
            }
        }
        drawLine(color = TextDim.copy(alpha = 0.5f), start = Offset(0f, mid), end = Offset(w, mid), strokeWidth = 1f)

        val dim = Color(0xB3000000)
        if (sx > 0f) drawRect(color = dim, topLeft = Offset(0f, 0f), size = Size(sx, h))
        if (ex < w) drawRect(color = dim, topLeft = Offset(ex, 0f), size = Size(w - ex, h))

        val hw = 3.dp.toPx()
        drawLine(color = accent, start = Offset(sx, 0f), end = Offset(sx, h), strokeWidth = hw)
        drawLine(color = accent, start = Offset(ex, 0f), end = Offset(ex, h), strokeWidth = hw)
        drawCircle(color = accent, radius = 7.dp.toPx(), center = Offset(sx, 9.dp.toPx()))
        drawCircle(color = accent, radius = 7.dp.toPx(), center = Offset(ex, h - 9.dp.toPx()))

        if (playFrac != null) {
            val px = (playFrac * w).toFloat().coerceIn(0f, w)
            drawLine(color = Color.White, start = Offset(px, 0f), end = Offset(px, h), strokeWidth = 2.dp.toPx())
        }
    }
}
