package com.kendang.realpads.skin

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Klien repository skin (Stage 08).
 * Alur: config/skin-repo.json -> index.json -> skin.json + skin.svg -> cache (filesDir/skin-cache) -> SkinLoader.
 * Skin yang sudah diunduh tetap bisa dipakai offline; skin baru cukup di-upload ke repo + index.json (tanpa update APK).
 * Semua fungsi jaringan BLOKING: panggil dari Dispatchers.IO.
 */
object SkinRepository {
    private const val TAG = "SkinRepository"
    private const val CONFIG_ASSET = "config/skin-repo.json"
    private const val PLACEHOLDER = "RAW_GITHUB_INDEX_URL"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val TIMEOUT_MS = 10_000
    private val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val FILE_REGEX = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val REQUIRED_FILES = listOf("skin.json", "skin.svg")

    data class FileInfo(val sha256: String, val size: Long)

    data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val padMin: Int,
        val padMax: Int,
        val path: String,
        val files: Map<String, FileInfo>
    )

    /** true bila refreshIndex terakhir berhasil mengambil index dari jaringan (false = pakai cache / tidak terkonfigurasi). */
    @Volatile var lastRefreshOnline: Boolean = false
        private set

    fun cacheRoot(context: Context): File = File(context.applicationContext.filesDir, "skin-cache")

    private fun indexCacheFile(context: Context) = File(cacheRoot(context), "index.json")

    /** URL index.json dari config; null bila belum dikonfigurasi (masih placeholder). */
    fun indexUrl(context: Context): String? = try {
        val txt = context.assets.open(CONFIG_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val u = JSONObject(txt).optString("indexUrl", "").trim()
        if (u.isEmpty() || u == PLACEHOLDER || !u.startsWith("https://")) null else u
    } catch (e: Exception) {
        Log.w(TAG, "config skin-repo tidak terbaca: ${e.message}")
        null
    }

    /** Ambil index online; berhasil -> simpan ke cache. Gagal -> kembalikan index cache (offline). */
    fun refreshIndex(context: Context): List<Entry> {
        lastRefreshOnline = false
        val url = indexUrl(context) ?: return cachedEntries(context)
        return try {
            val text = httpGetText(url)
            val entries = parseIndex(text) // lempar bila tidak valid -> cache lama tetap utuh
            cacheRoot(context).mkdirs()
            writeAtomic(indexCacheFile(context), text.toByteArray(Charsets.UTF_8))
            lastRefreshOnline = true
            entries
        } catch (e: Exception) {
            Log.w(TAG, "index online gagal, pakai cache: ${e.message}")
            cachedEntries(context)
        }
    }

    fun cachedEntries(context: Context): List<Entry> = try {
        val f = indexCacheFile(context)
        if (f.isFile) parseIndex(f.readText(Charsets.UTF_8)) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "index cache rusak: ${e.message}")
        emptyList()
    }

    /** Folder skin terunduh bila lengkap (skin.json + skin.svg ada), selain itu null. */
    fun cachedSkinDir(context: Context, id: String): File? {
        if (!ID_REGEX.matches(id)) return null
        val d = File(cacheRoot(context), "skins/$id")
        return if (REQUIRED_FILES.all { File(d, it).isFile }) d else null
    }

    /** Versi skin terunduh (dari skin.json di cache), null bila belum diunduh / tidak terbaca. */
    fun cachedVersion(context: Context, id: String): String? {
        val d = cachedSkinDir(context, id) ?: return null
        return try { JSONObject(File(d, "skin.json").readText(Charsets.UTF_8)).optString("version", "").ifBlank { null } } catch (e: Exception) { null }
    }

    fun isDownloaded(context: Context, id: String): Boolean = cachedSkinDir(context, id) != null

    /** Unduh + verifikasi sha256 + ukuran, lalu pasang atomik ke cache. Cache lama tidak rusak bila gagal. */
    fun download(context: Context, entry: Entry): File {
        val base = indexUrl(context) ?: throw SkinException("indexUrl belum dikonfigurasi")
        val baseDir = base.substringBeforeLast('/') + "/"
        val root = File(cacheRoot(context), "skins").also { it.mkdirs() }
        val tmp = File(root, "${entry.id}.tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        try {
            for (name in REQUIRED_FILES) {
                val info = entry.files[name] ?: throw SkinException("index tidak memuat $name untuk ${entry.id}")
                val bytes = httpGetBytes(baseDir + entry.path + name)
                if (info.size > 0 && bytes.size.toLong() != info.size) {
                    throw SkinException("ukuran $name tidak cocok (${bytes.size} != ${info.size})")
                }
                if (!sha256(bytes).equals(info.sha256, ignoreCase = true)) {
                    throw SkinException("sha256 $name tidak cocok")
                }
                File(tmp, name).writeBytes(bytes)
            }
            // coba parse dulu: skin rusak tidak boleh masuk cache
            val check = SkinLoader.loadFromDir(tmp)
            val v = SkinValidator.validate(check)
            if (!v.ok) throw SkinException("skin ditolak validator: ${v.summary()}")
            val dest = File(root, entry.id)
            val old = File(root, "${entry.id}.old")
            old.deleteRecursively()
            if (dest.exists() && !dest.renameTo(old)) throw SkinException("gagal mengganti cache lama")
            if (!tmp.renameTo(dest)) {
                if (old.exists()) old.renameTo(dest)
                throw SkinException("gagal memasang skin ke cache")
            }
            old.deleteRecursively()
            return dest
        } catch (e: SkinException) {
            tmp.deleteRecursively(); throw e
        } catch (e: Exception) {
            tmp.deleteRecursively(); throw SkinException("unduh ${entry.id} gagal: ${e.message}", e)
        }
    }

    // ---- parsing & util ----

    internal fun parseIndex(text: String): List<Entry> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("skins") ?: throw SkinException("index.json tanpa 'skins'")
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val path = o.optString("path")
            if (!ID_REGEX.matches(id)) { Log.w(TAG, "id skin tidak valid dilewati: $id"); continue }
            if (path.contains("..") || path.startsWith("/") || path.contains("\\") || !path.endsWith("/")) {
                Log.w(TAG, "path skin tidak valid dilewati: $path"); continue
            }
            val files = LinkedHashMap<String, FileInfo>()
            val fo = o.optJSONObject("files")
            if (fo != null) {
                for (k in fo.keys()) {
                    if (!FILE_REGEX.matches(k)) continue
                    val fi = fo.optJSONObject(k) ?: continue
                    files[k] = FileInfo(fi.optString("sha256"), fi.optLong("size", 0L))
                }
            }
            if (REQUIRED_FILES.any { it !in files }) { Log.w(TAG, "skin $id tanpa file wajib, dilewati"); continue }
            out.add(Entry(id, o.optString("name", id), o.optString("version", "?"), o.optString("author", ""),
                o.optInt("padMin", 0), o.optInt("padMax", 0), path, files))
        }
        return out
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun writeAtomic(f: File, data: ByteArray) {
        val t = File(f.parentFile, f.name + ".tmp")
        t.writeBytes(data)
        if (!t.renameTo(f)) { f.delete(); if (!t.renameTo(f)) throw SkinException("gagal menulis ${f.name}") }
    }

    private fun httpGetText(url: String): String = httpGetBytes(url).toString(Charsets.UTF_8)

    private fun httpGetBytes(url: String): ByteArray {
        if (!url.startsWith("https://")) throw SkinException("hanya https yang diizinkan")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) throw SkinException("HTTP ${c.responseCode} untuk $url")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192); var total = 0
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw SkinException("respons terlalu besar")
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }
}
