package com.kendang.realpads.event

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Definisi event + misi dari repo GitHub (raw, read-only, HTTP GET biasa). Meniru pola SkinRepository.
 * Urutan sumber: online -> cache (filesDir/event-cache/events.json) -> bawaan APK (assets/config/events.json) -> kosong.
 * Semua fungsi jaringan jalan di Dispatchers.IO, timeout 10 dtk, tidak pernah memblok UI/audio.
 */
object EventRepository {
    private const val TAG = "EventRepository"
    private const val CONFIG_ASSET = "config/event-repo.json"
    private const val BUNDLED_ASSET = "config/events.json"
    private const val PLACEHOLDER = "RAW_GITHUB_EVENTS_URL"
    private const val PREFS = "realpads_event_repo"
    private const val KEY_ETAG = "etag"
    private const val KEY_LAST_OK = "last_ok_ms"

    const val STARTUP_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000   // maks 1x / ~6 jam saat app mulai
    const val TAB_MIN_INTERVAL_MS = 60_000L                   // buka tab berkali-kali tidak membanjiri jaringan
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_BANNER_BYTES = 300 * 1024
    private const val MAX_REDIRECTS = 3
    private const val MAX_BANNER_DIM = 4096
    private const val BANNER_REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    enum class Outcome { UPDATED, NOT_MODIFIED, THROTTLED, NOT_CONFIGURED, FAILED }

    private val _snapshot = MutableStateFlow(EventsSnapshot(emptyList(), DataSource.NONE))
    val snapshot: StateFlow<EventsSnapshot> = _snapshot.asStateFlow()

    @Volatile private var inited = false
    @Volatile private var lastAttemptMs = 0L
    private val mutex = Mutex()

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "event-cache")
    private fun cacheFile(context: Context) = File(dir(context), "events.json")
    private fun bannerDir(context: Context) = File(dir(context), "banners")
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** URL events.json dari config; null bila belum dikonfigurasi (placeholder / bukan https). */
    fun eventsUrl(context: Context): String? = try {
        val txt = context.applicationContext.assets.open(CONFIG_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val u = JSONObject(txt).optString("eventsUrl", "").trim()
        if (u.isEmpty() || u == PLACEHOLDER || !u.startsWith("https://")) null else u
    } catch (e: Exception) {
        Log.w(TAG, "config event-repo tidak terbaca: ${e.message}"); null
    }

    /** Muat cache/bawaan (baca file kecil). Aman dipanggil berulang; panggil dari IO. */
    fun init(context: Context) {
        if (inited) return
        inited = true
        _snapshot.value = loadLocal(context.applicationContext)
    }

    /** Dipanggil saat app mulai: hanya jika sudah >= 6 jam sejak refresh sukses terakhir. */
    suspend fun refreshIfDue(context: Context): Outcome {
        val last = prefs(context).getLong(KEY_LAST_OK, 0L)
        if (System.currentTimeMillis() - last < STARTUP_MIN_INTERVAL_MS) return Outcome.THROTTLED
        return refresh(context, force = false)
    }

    /** Ambil dari jaringan. force=true (tombol muat ulang) tetap dibatasi TAB_MIN_INTERVAL_MS antar percobaan, kecuali [ignoreThrottle]. */
    suspend fun refresh(context: Context, force: Boolean, ignoreThrottle: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val app = context.applicationContext
                init(app)
                val url = eventsUrl(app) ?: return@withLock Outcome.NOT_CONFIGURED
                val now = System.currentTimeMillis()
                if (!ignoreThrottle && now - lastAttemptMs < TAB_MIN_INTERVAL_MS) return@withLock Outcome.THROTTLED
                lastAttemptMs = now
                try {
                    val cf = cacheFile(app)
                    val etag = if (cf.isFile) prefs(app).getString(KEY_ETAG, null) else null
                    val r = httpGet(url, etag, MAX_BYTES)
                    if (r.code == 304 && cf.isFile) {
                        prefs(app).edit().putLong(KEY_LAST_OK, now).apply()
                        _snapshot.value = _snapshot.value.copy(source = DataSource.ONLINE, lastError = null)
                        Outcome.NOT_MODIFIED
                    } else {
                        val bytes = r.bytes ?: throw IOException("respons kosong")
                        val parsed = EventParser.parse(bytes.toString(Charsets.UTF_8))   // lempar bila skema tak dikenal -> cache lama tetap utuh
                        dir(app).mkdirs()
                        writeAtomic(cf, bytes)
                        prefs(app).edit().putString(KEY_ETAG, r.etag).putLong(KEY_LAST_OK, now).apply()
                        _snapshot.value = EventsSnapshot(parsed.events, DataSource.ONLINE, null, parsed.skipped)
                        Outcome.UPDATED
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "refresh gagal, pakai cache/bawaan: ${e.message}")
                    _snapshot.value = loadLocal(app).copy(lastError = e.message ?: e.javaClass.simpleName)
                    Outcome.FAILED
                }
            }
        }

    private fun loadLocal(app: Context): EventsSnapshot {
        try {
            val f = cacheFile(app)
            if (f.isFile && f.length() <= MAX_BYTES) {
                val p = EventParser.parse(f.readText(Charsets.UTF_8))
                return EventsSnapshot(p.events, DataSource.CACHE, null, p.skipped)
            }
        } catch (e: Exception) { Log.w(TAG, "cache event rusak: ${e.message}") }
        try {
            val txt = app.assets.open(BUNDLED_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val p = EventParser.parse(txt)
            return EventsSnapshot(p.events, DataSource.BUNDLED, null, p.skipped)
        } catch (e: Exception) { Log.w(TAG, "salinan bawaan event tidak terbaca: ${e.message}") }
        return EventsSnapshot(emptyList(), DataSource.NONE)
    }

    /** skin_id semua event yang dikenal (cache/bawaan) - dibaca sinkron; dipakai gerbang SkinManager saat startup. */
    fun peekSkinIds(context: Context): Set<String> {
        val snap = if (inited) _snapshot.value else loadLocal(context.applicationContext)
        return snap.events.mapTo(HashSet()) { it.skinId }
    }

    // ---------------- banner ----------------

    private fun bannerUrl(app: Context, banner: String): String? {
        if (banner.startsWith("https://")) return banner
        val base = eventsUrl(app) ?: return null
        return base.substringBeforeLast('/') + "/banners/" + banner
    }

    private fun bannerFile(app: Context, eventId: String) = File(bannerDir(app), "$eventId.img")

    /** File banner di cache (unduh bila belum ada / sudah lama / URL berubah). null = tanpa banner. Panggil dari IO. */
    suspend fun ensureBanner(context: Context, ev: GameEvent): File? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val name = ev.banner ?: return@withContext null
        val f = bannerFile(app, ev.id)
        val url = bannerUrl(app, name)
        val keyUrl = "banner_url_${ev.id}"
        val fresh = f.isFile && prefs(app).getString(keyUrl, null) == url &&
            System.currentTimeMillis() - f.lastModified() < BANNER_REFRESH_MS
        if (fresh || url == null) return@withContext if (f.isFile) f else null
        try {
            val r = httpGet(url, null, MAX_BANNER_BYTES)
            val bytes = r.bytes ?: throw IOException("banner kosong")
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > MAX_BANNER_DIM || o.outHeight > MAX_BANNER_DIM ||
                (o.outMimeType != "image/png" && o.outMimeType != "image/jpeg")) throw IOException("banner bukan PNG/JPG wajar")
            bannerDir(app).mkdirs()
            writeAtomic(f, bytes)
            prefs(app).edit().putString(keyUrl, url).apply()
            f
        } catch (e: Exception) {
            Log.w(TAG, "banner ${ev.id} gagal: ${e.message}")
            if (f.isFile) f else null
        }
    }

    /** Banner yang sudah ada di cache (tanpa jaringan) - untuk Koleksi saat event sudah hilang dari repo. */
    fun cachedBanner(context: Context, eventId: String): File? =
        bannerFile(context.applicationContext, eventId).takeIf { it.isFile }

    /** Decode aman (kecil, sample size, tangkap OOM). */
    fun decodeBanner(file: File, maxWidthPx: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxWidthPx) s *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) { Log.w(TAG, "decode banner gagal: ${e.message}"); null }

    // ---------------- HTTP ----------------

    private class Http(val code: Int, val bytes: ByteArray?, val etag: String?)

    /** GET https-only; redirect hanya ke https (maks 3); batas ukuran; tanpa cookie/header rahasia. Boleh If-None-Match. */
    private fun httpGet(url0: String, etag: String?, maxBytes: Int): Http {
        var url = url0
        for (hop in 0..MAX_REDIRECTS) {
            if (!url.startsWith("https://")) throw IOException("hanya https yang diizinkan")
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
                c.instanceFollowRedirects = false; c.useCaches = false
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", "DTXKendang-Event/1")
                if (etag != null) c.setRequestProperty("If-None-Match", etag)
                when (val code = c.responseCode) {
                    200 -> {
                        if (c.contentLengthLong > maxBytes) throw IOException("respons terlalu besar")
                        val out = ByteArrayOutputStream()
                        c.inputStream.use { ins ->
                            val buf = ByteArray(8192); var total = 0
                            while (true) {
                                val n = ins.read(buf); if (n < 0) break
                                total += n
                                if (total > maxBytes) throw IOException("respons terlalu besar")
                                out.write(buf, 0, n)
                            }
                        }
                        return Http(200, out.toByteArray(), c.getHeaderField("ETag"))
                    }
                    304 -> return Http(304, null, etag)
                    301, 302, 303, 307, 308 -> {
                        val loc = c.getHeaderField("Location") ?: throw IOException("redirect tanpa Location")
                        url = URL(URL(url), loc).toString()
                    }
                    else -> throw IOException("HTTP $code")
                }
            } finally { c.disconnect() }
        }
        throw IOException("terlalu banyak redirect")
    }

    private fun writeAtomic(f: File, data: ByteArray) {
        val t = File(f.parentFile, f.name + ".tmp")
        t.writeBytes(data)
        if (!t.renameTo(f)) { f.delete(); if (!t.renameTo(f)) throw IOException("gagal menulis ${f.name}") }
    }
}
