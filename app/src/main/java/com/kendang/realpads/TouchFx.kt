package com.kendang.realpads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

private const val FX_PREFS = "realpads_touchfx"
private const val KEY_SIZE_DP = "size_dp"
private const val KEY_FADE_MS = "fade_ms"
private const val FX_FILENAME = "touch_fx.img"
const val TOUCH_FX_MIN_DP = 16f
const val TOUCH_FX_MAX_DP = 240f
private const val TOUCH_FX_DEFAULT_DP = 72f
const val TOUCH_FX_MAX_FADE_MS = 1000
private const val TOUCH_FX_DEFAULT_FADE_MS = 220

// Visual sentuhan (gambar ketukan) GLOBAL. Gambar dipilih user (PNG transparan didukung,
// alpha dipertahankan), disalin ke internal storage, ditampilkan di titik jari:
// multi touch, ditahan = tetap tampil, digeser = ikut jari. Ukuran bisa diatur.
//
// Desain anti-delay: event sentuh HANYA DIBACA (Activity.dispatchTouchEvent, setelah
// super sehingga jalur pad/audio jalan lebih dulu & tidak pernah dikonsumsi/diubah).
// Penggambaran lewat View biasa (bukan Compose) -> tidak memicu recomposition, dan
// invalidate hanya berjalan selama ada efek aktif (idle = nol biaya).
object TouchFxState {
    // Bitmap untuk overlay (thread-safe baca; ditulis dari main thread).
    @Volatile var bitmap: Bitmap? = null
        private set
    // Preview untuk dialog Tema.
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var sizeDp by mutableStateOf(TOUCH_FX_DEFAULT_DP)
        private set
    // Kecepatan kedip = durasi memudar setelah jari lepas (ms). 0 = INSTAN: hilang tepat
    // saat jari lepas. Dibaca overlay tiap frame -> perubahan slider langsung berlaku.
    var fadeMs by mutableStateOf(TOUCH_FX_DEFAULT_FADE_MS)
        private set

    private fun file(c: Context) = File(c.filesDir, FX_FILENAME)

    data class Loaded(val bmp: Bitmap?, val sizeDp: Float, val fadeMs: Int)

    // IO thread.
    fun loadFromDisk(c: Context): Loaded {
        val prefs = c.getSharedPreferences(FX_PREFS, Context.MODE_PRIVATE)
        val size = prefs.getFloat(KEY_SIZE_DP, TOUCH_FX_DEFAULT_DP)
        val fade = prefs.getInt(KEY_FADE_MS, TOUCH_FX_DEFAULT_FADE_MS)
        val f = file(c)
        return Loaded(if (f.exists()) decode(f) else null, size, fade)
    }

    // Main thread.
    fun applyLoaded(l: Loaded) {
        sizeDp = l.sizeDp.coerceIn(TOUCH_FX_MIN_DP, TOUCH_FX_MAX_DP)
        fadeMs = l.fadeMs.coerceIn(0, TOUCH_FX_MAX_FADE_MS)
        setBitmap(l.bmp)
    }

    // Maks sisi 512px: cukup tajam untuk ukuran sentuhan, ringan di memori & gambar.
    // ARGB_8888 supaya alpha PNG tanpa latar tetap utuh.
    private fun decode(f: File): Bitmap? = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) null else {
            var s = 1
            while (b.outWidth / s > 512 || b.outHeight / s > 512) s *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = s
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    } catch (e: Throwable) { null }

    // IO thread. Simpan byte asli lalu decode; gagal -> null & file lama dikembalikan bersih.
    fun decodeAndSave(c: Context, bytes: ByteArray): Bitmap? = try {
        val f = file(c)
        f.writeBytes(bytes)
        val bmp = decode(f)
        if (bmp == null) f.delete()
        bmp
    } catch (e: Throwable) { null }

    // Main thread.
    fun applyImage(bmp: Bitmap) = setBitmap(bmp)

    fun clearImage(c: Context) {
        file(c).delete()
        setBitmap(null)
    }

    fun setSize(c: Context, dp: Float) {
        sizeDp = dp.coerceIn(TOUCH_FX_MIN_DP, TOUCH_FX_MAX_DP)
        c.getSharedPreferences(FX_PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SIZE_DP, sizeDp).apply()
    }

    fun setFade(c: Context, ms: Int) {
        fadeMs = ms.coerceIn(0, TOUCH_FX_MAX_FADE_MS)
        c.getSharedPreferences(FX_PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_FADE_MS, fadeMs).apply()
    }

    private fun setBitmap(b: Bitmap?) {
        bitmap = b
        preview = b?.asImageBitmap()
    }
}

// View overlay transparan di atas seluruh konten Activity. TIDAK menangani sentuhan
// (non-clickable -> onTouchEvent false); semua input datang lewat onTouch() yang dipanggil
// Activity secara read-only.
class TouchFxOverlay(context: Context) : View(context) {
    private class Fx(val id: Int, var x: Float, var y: Float, val downAt: Long) {
        var releaseAt = Long.MAX_VALUE   // kapan mulai memudar
        var drawn = false                // sudah tergambar minimal 1 frame (tap kilat tetap terlihat)
    }

    private val fx = ArrayList<Fx>(10)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val loc = IntArray(2)
    private val density = context.resources.displayMetrics.density

    // Tap cepat tetap kelihatan minimal sekian ms, lalu pudar. Mode instan (fade 0):
    // tanpa tahan, hilang tepat saat jari lepas (tetap digambar min. 1 frame).
    private fun minShowMs(): Long = if (TouchFxState.fadeMs <= 0) 0L else 90L

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    // Dipanggil dari Activity.dispatchTouchEvent SETELAH super (read-only, tanpa alokasi
    // kecuali saat jari baru menyentuh).
    fun onTouch(e: MotionEvent) {
        if (TouchFxState.bitmap == null) {
            if (fx.isNotEmpty()) { fx.clear(); invalidate() }
            return
        }
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                fx.removeAll { it.id == id }
                fx.add(Fx(id, e.getRawXCompat(i), e.getRawYCompat(i), now))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    for (f in fx) if (f.id == id && f.releaseAt == Long.MAX_VALUE) {
                        f.x = e.getRawXCompat(i); f.y = e.getRawYCompat(i)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                for (f in fx) if (f.id == id && f.releaseAt == Long.MAX_VALUE) {
                    f.x = e.getRawXCompat(e.actionIndex); f.y = e.getRawYCompat(e.actionIndex)
                    f.releaseAt = maxOf(now, f.downAt + minShowMs())
                }
            }
            MotionEvent.ACTION_OUTSIDE -> {
                // Sentuhan di luar jendela dialog: hanya 1 event (tanpa lanjutan) -> kedip singkat.
                fx.removeAll { it.id == -1 }
                fx.add(Fx(-1, e.rawX, e.rawY, now).also { it.releaseAt = now + minShowMs() })
            }
            MotionEvent.ACTION_CANCEL -> {
                for (f in fx) if (f.releaseAt == Long.MAX_VALUE) f.releaseAt = maxOf(now, f.downAt + minShowMs())
            }
        }
        invalidate()
    }

    // getRawX(pointerIndex) baru ada di API 29; di bawahnya hanya pointer 0 yang raw.
    // Fallback: koordinat lokal window + offset window di layar (akurat untuk fullscreen).
    private fun MotionEvent.getRawXCompat(i: Int): Float =
        if (android.os.Build.VERSION.SDK_INT >= 29) getRawX(i) else getX(i) + (rawX - x)
    private fun MotionEvent.getRawYCompat(i: Int): Float =
        if (android.os.Build.VERSION.SDK_INT >= 29) getRawY(i) else getY(i) + (rawY - y)

    override fun onDraw(canvas: Canvas) {
        val bmp = TouchFxState.bitmap
        if (bmp == null || fx.isEmpty()) { fx.clear(); return }
        val now = SystemClock.uptimeMillis()
        getLocationOnScreen(loc)
        val ratio = bmp.height.toFloat() / bmp.width.toFloat()
        val w = TouchFxState.sizeDp * density
        val h = w * ratio
        val fade = TouchFxState.fadeMs
        val it = fx.iterator()
        while (it.hasNext()) {
            val f = it.next()
            var a = 1f
            if (now >= f.releaseAt) {
                a = if (fade <= 0) 0f else 1f - (now - f.releaseAt).toFloat() / fade
                if (a <= 0f) {
                    if (f.drawn) { it.remove(); continue }
                    a = 1f   // belum pernah tergambar (tap lebih singkat dari 1 frame): tampilkan sekali
                }
            }
            f.drawn = true
            val cx = f.x - loc[0]
            val cy = f.y - loc[1]
            dst.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            paint.alpha = (a * 255f).toInt()
            canvas.drawBitmap(bmp, null, dst, paint)
        }
        // Lanjut animasi hanya kalau masih ada efek yang memudar; jari yang masih
        // menempel tidak perlu frame loop (gerak digambar ulang oleh onTouch -> invalidate).
        for (f in fx) if (f.releaseAt != Long.MAX_VALUE) { postInvalidateOnAnimation(); break }
    }
}
