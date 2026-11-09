package com.kendang.realpads.event

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.kendang.realpads.R
import com.kendang.realpads.skin.SkinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status singkat untuk UI. [msgRes]=0 -> tidak ada pesan. */
data class BackupStatus(
    val lastBackupMs: Long = 0L,
    val folder: String = EventBackupManager.BACKUP_FOLDER_NAME,
    val usedFallbackFolder: Boolean = false,
    val autoEnabled: Boolean = true,
    val busy: Boolean = false,
    val msgRes: Int = 0,
    val msgArg: String? = null
)

/**
 * Backup + pemulihan progres event ke Documents/<folder emoji>/ sebagai JSON biasa (TANPA enkripsi).
 *  - API 29+: MediaStore (RELATIVE_PATH "Documents/<folder>/"), tanpa izin.
 *  - API 26-28: File API + WRITE_EXTERNAL_STORAGE (manifest maxSdkVersion=28), diminta oleh UI saat fitur backup pertama dipakai.
 *  - Nama file ASCII DTX_Backup_<yyyy-MM-dd_HHmm>.dtxsave; simpan 3 terakhir; hapus HANYA file berpola ini.
 *  - Tiap perubahan progres -> auto backup (debounce 4 dtk, IO, lewati bila isi sama). Gagal tidak pernah crash.
 */
object EventBackupManager {
    private const val TAG = "EventBackup"

    /** Folder utama + pilihan. Semua WAJIB lolos [isValidFolderName] (emoji 1 code point, tanpa FE0F/ZWJ/skin tone). */
    const val BACKUP_FOLDER_NAME = "\uD83E\uDD41 DTX Kendang \uD83D\uDD25"          // 🥁 DTX Kendang 🔥
    val FOLDER_CHOICES = listOf(
        BACKUP_FOLDER_NAME,
        "\uD83D\uDD25 DTX Save \uD83D\uDD25",                                        // 🔥 DTX Save 🔥
        "\uD83C\uDFB6 DTX Kendang \uD83C\uDFB6"                                      // 🎶 DTX Kendang 🎶
    )
    const val FALLBACK_FOLDER = "DTX Kendang"

    private const val PREFS = "realpads_event_backup"   // SENGAJA tidak ikut Auto Backup Android
    private const val FILE_PREFIX = "DTX_Backup_"
    private const val FILE_EXT = ".dtxsave"
    private val NAME_REGEX = Regex("^DTX_Backup_\\d{4}-\\d{2}-\\d{2}_\\d{4}\\.dtxsave$")
    private const val KEEP = 3
    private const val AUTO_DEBOUNCE_MS = 4_000L
    private const val AUTO_MIN_GAP_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var autoJob: Job? = null
    @Volatile private var lastAutoMs = 0L

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    sealed class Result {
        object Saved : Result()
        object Skipped : Result()
        object NeedPermission : Result()
        data class Failed(val reason: String) : Result()
    }

    sealed class RestoreResult {
        data class Ok(val merged: Int, val skinPending: Boolean) : RestoreResult()
        data class Bad(val resId: Int) : RestoreResult()
        data class Failed(val reason: String) : RestoreResult()
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ folder

    fun isValidFolderName(name: String): Boolean {
        if (name.isEmpty() || name.length > 60) return false
        if (Normalizer.normalize(name, Normalizer.Form.NFC) != name) return false
        if (name.first() == ' ' || name.last() == ' ' || name.last() == '.') return false
        if (name.any { it in "/\\:*?\"<>|" || it.code < 0x20 }) return false
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i); i += Character.charCount(cp)
            if (cp == 0xFE0F || cp == 0x200D || cp in 0x1F3FB..0x1F3FF) return false   // VS16, ZWJ, skin tone
        }
        return true
    }

    fun folderName(c: Context): String {
        val saved = prefs(c).getString("folder", null)
        return if (saved != null && saved in FOLDER_CHOICES && isValidFolderName(saved)) saved else BACKUP_FOLDER_NAME
    }

    fun setFolderName(c: Context, name: String) {
        if (name in FOLDER_CHOICES && isValidFolderName(name)) {
            prefs(c).edit().putString("folder", name).apply()
            _status.value = _status.value.copy(folder = name, usedFallbackFolder = false)
        }
    }

    // ------------------------------------------------------------------ izin & pengaturan

    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun hasPermission(c: Context): Boolean = !needsLegacyPermission() ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun permissionAsked(c: Context) = prefs(c).getBoolean("perm_asked", false)
    fun markPermissionAsked(c: Context) = prefs(c).edit().putBoolean("perm_asked", true).apply()

    fun autoEnabled(c: Context) = prefs(c).getBoolean("auto", true)

    fun setAutoEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("auto", on).apply()
        _status.value = _status.value.copy(autoEnabled = on)
    }

    /** Izin API 26-28 ditolak -> backup otomatis nonaktif + pesan jelas. */
    fun onPermissionDenied(c: Context) {
        setAutoEnabled(c, false)
        _status.value = _status.value.copy(msgRes = R.string.backup_permission_denied, msgArg = null)
    }

    /** Muat status awal (terakhir backup, folder, auto). Ringan; panggil saat UI tab Event dibuka. */
    fun loadStatus(c: Context) {
        val p = prefs(c)
        _status.value = _status.value.copy(
            lastBackupMs = p.getLong("last_ms", 0L), folder = p.getString("last_folder", null) ?: folderName(c),
            usedFallbackFolder = p.getBoolean("last_fallback", false), autoEnabled = autoEnabled(c)
        )
    }

    // ------------------------------------------------------------------ auto backup

    /** Dipanggil EventProgressManager tiap state berubah. Debounce 4 dtk; tidak pernah melempar. */
    fun onProgressChanged(c: Context) {
        val app = c.applicationContext
        if (!autoEnabled(app)) return
        autoJob?.cancel()
        autoJob = scope.launch {
            delay(AUTO_DEBOUNCE_MS)
            val wait = AUTO_MIN_GAP_MS - (System.currentTimeMillis() - lastAutoMs)
            if (wait > 0) delay(wait)
            lastAutoMs = System.currentTimeMillis()
            val r = backupNow(app, force = false)
            if (r is Result.Failed) Log.w(TAG, "auto backup gagal: ${r.reason}")
        }
    }

    // ------------------------------------------------------------------ backup

    suspend fun backupNow(context: Context, force: Boolean = true): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        mutex.withLock {
            if (!hasPermission(app)) {
                _status.value = _status.value.copy(msgRes = R.string.backup_need_permission, msgArg = null)
                return@withLock Result.NeedPermission
            }
            _status.value = _status.value.copy(busy = true)
            try {
                val now = System.currentTimeMillis()
                val core = EventBackupSerializer.core(EventProgressManager.state.value, SkinManager.selectedId(app))
                val h = EventBackupSerializer.hash(core)
                if (!force && h == prefs(app).getString("last_hash", null)) {
                    _status.value = _status.value.copy(busy = false)
                    return@withLock Result.Skipped
                }
                val bytes = EventBackupSerializer.build(core, now).toByteArray(Charsets.UTF_8)
                if (bytes.size > EventBackupSerializer.MAX_BYTES) throw IOException("isi backup terlalu besar")
                val name = FILE_PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(now)) + FILE_EXT

                var folder = folderName(app)
                var fallback = false
                try {
                    write(app, folder, name, bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "folder '$folder' gagal (${e.message}); pakai folder cadangan '$FALLBACK_FOLDER'")
                    folder = FALLBACK_FOLDER; fallback = true
                    write(app, folder, name, bytes)
                }
                try { rotate(app, folder) } catch (e: Exception) { Log.w(TAG, "rotasi gagal: ${e.message}") }

                prefs(app).edit().putString("last_hash", h).putLong("last_ms", now)
                    .putString("last_folder", folder).putBoolean("last_fallback", fallback).apply()
                _status.value = _status.value.copy(lastBackupMs = now, folder = folder, usedFallbackFolder = fallback, busy = false,
                    msgRes = R.string.backup_ok, msgArg = null)
                Result.Saved
            } catch (e: Throwable) {
                Log.w(TAG, "backup gagal", e)
                val why = e.message ?: e.javaClass.simpleName
                _status.value = _status.value.copy(busy = false, msgRes = R.string.backup_failed, msgArg = why)
                Result.Failed(why)
            }
        }
    }

    private fun write(app: Context, folder: String, name: String, bytes: ByteArray) {
        require(isValidFolderName(folder) || folder == FALLBACK_FOLDER) { "nama folder tidak valid" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeMediaStore(app, folder, name, bytes) else writeLegacy(folder, name, bytes)
    }

    // ---- API 29+ : MediaStore

    private fun filesUri(): Uri = MediaStore.Files.getContentUri("external")

    private fun relPath(folder: String) = Environment.DIRECTORY_DOCUMENTS + "/" + folder + "/"

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStore(app: Context, folder: String, name: String, bytes: ByteArray) {
        val r = app.contentResolver
        val rel = relPath(folder)
        // Timpa file bernama sama milik app (hindari duplikat "(1)").
        val existing = ownFiles(app, rel).firstOrNull { it.second == name }?.first
        val uri: Uri
        if (existing != null) {
            uri = existing
            (r.openOutputStream(uri, "wt") ?: throw IOException("tidak bisa membuka file tujuan")).use { it.write(bytes) }
        } else {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                // octet-stream: MIME "application/json" membuat MediaStore menambahkan ".json" di belakang nama.
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = r.insert(filesUri(), cv) ?: throw IOException("MediaStore menolak membuat file")
            try {
                (r.openOutputStream(uri, "w") ?: throw IOException("tidak bisa membuka file tujuan")).use { it.write(bytes) }
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { runCatching { r.delete(uri, null, null) }; throw e }
        }
        // Verifikasi nama + lokasi (emoji bisa ditolak/diubah sistem; nama bisa jadi "(1)") lalu baca-ulang.
        try {
            r.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                if (!c.moveToFirst()) throw IOException("file tidak terbaca setelah ditulis")
                val gotName = c.getString(0); val gotRel = c.getString(1)
                if (gotName != name) throw IOException("nama file berubah menjadi '$gotName'")
                if (!gotRel.equals(rel, ignoreCase = true)) throw IOException("lokasi berubah menjadi '$gotRel'")
            } ?: throw IOException("query verifikasi gagal")
            verifyReadBack(r.openInputStream(uri), bytes.size)
        } catch (e: Exception) { runCatching { r.delete(uri, null, null) }; throw e }
    }

    /** (uri, nama) file DTX_Backup_*.dtxsave milik app di [rel], terbaru dulu. MediaStore hanya menampilkan file milik app sendiri. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ownFiles(app: Context, rel: String): List<Pair<Uri, String>> {
        val out = ArrayList<Pair<Uri, String>>()
        app.contentResolver.query(
            filesUri(), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(rel, "$FILE_PREFIX%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                if (NAME_REGEX.matches(n)) out.add(ContentUris.withAppendedId(filesUri(), c.getLong(0)) to n)
            }
        }
        return out.sortedByDescending { it.second }
    }

    // ---- API 26-28 : File API

    @Suppress("DEPRECATION")
    private fun docsDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

    private fun writeLegacy(folder: String, name: String, bytes: ByteArray) {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) throw IOException("penyimpanan tidak tersedia")
        val dir = File(docsDir(), folder)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("gagal membuat folder")
        if (dir.name != folder || dir.list() == null) throw IOException("folder tidak terverifikasi")
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (f.exists()) f.delete()                      // timpa, bukan "(1)"
        if (!tmp.renameTo(f)) { tmp.delete(); throw IOException("gagal memasang file backup") }
        verifyReadBack(f.inputStream(), bytes.size)
    }

    /** Baca-ulang: ukuran sama + JSON valid. */
    private fun verifyReadBack(ins: InputStream?, expectedSize: Int) {
        val data = (ins ?: throw IOException("tidak bisa membaca ulang")).use { it.readBytes() }
        if (data.size != expectedSize) throw IOException("ukuran hasil tulis tidak cocok (${data.size} != $expectedSize)")
        JSONObject(data.toString(Charsets.UTF_8))      // melempar bila bukan JSON valid
    }

    private fun rotate(app: Context, folder: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for ((uri, _) in ownFiles(app, relPath(folder)).drop(KEEP)) app.contentResolver.delete(uri, null, null)
        } else {
            File(docsDir(), folder).listFiles { f -> f.isFile && NAME_REGEX.matches(f.name) }
                ?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------ restore

    /** Baca lewat ContentResolver (dari ACTION_OPEN_DOCUMENT). Tidak valid -> data lokal TIDAK berubah. */
    suspend fun restoreFrom(context: Context, uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val bytes = app.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                ?: return@withContext RestoreResult.Bad(R.string.backup_err_open)
            val data = EventBackupSerializer.parse(bytes)         // validasi penuh SEBELUM menggabung
            EventProgressManager.mergeFrom(data)
            var pending = false
            val sel = data.selectedSkin
            if (sel != null && sel != SkinManager.CLASSIC_ID && sel != SkinManager.BUNDLED_ID &&
                EventProgressManager.isEventSkin(app, sel) && EventProgressManager.isSkinAllowed(app, sel)) {
                pending = !EventClaimer.useUnlocked(app, sel).ok       // offline & belum terunduh -> dipakai nanti
            }
            RestoreResult.Ok(data.quests.size, pending)
        } catch (e: BackupFormatException) {
            RestoreResult.Bad(e.resId)
        } catch (e: Throwable) {
            Log.w(TAG, "restore gagal", e)
            RestoreResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readLimited(ins: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (true) {
            val n = ins.read(buf); if (n < 0) break
            total += n
            if (total > EventBackupSerializer.MAX_BYTES) throw BackupFormatException(R.string.backup_err_too_big)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
