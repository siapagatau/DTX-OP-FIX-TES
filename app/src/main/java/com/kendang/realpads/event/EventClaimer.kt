package com.kendang.realpads.event

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kendang.realpads.BuildConfig
import com.kendang.realpads.R
import com.kendang.realpads.skin.SkinManager
import com.kendang.realpads.skin.SkinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Alur klaim + pakai skin event. Memakai ulang SkinRepository (unduh + sha256 + batas ukuran + validator) dan SkinManager.
 * Hasil berupa [Outcome]: [resId] = string resource pesan, [arg] = argumen format (nama skin / alasan).
 */
object EventClaimer {
    private const val TAG = "EventClaimer"

    class Outcome(val ok: Boolean, val resId: Int, val arg: String? = null)

    private fun isOnline(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }

    /** Pastikan skin [skinId] ada di cache (unduh dari skin repo bila belum / ada versi baru). null = siap; selain itu Outcome gagal. */
    private fun ensureDownloaded(app: Context, skinId: String): Outcome? {
        val have = SkinRepository.isDownloaded(app, skinId)
        var entry = SkinRepository.cachedEntries(app).firstOrNull { it.id == skinId }
        if (entry == null && !have) entry = SkinRepository.refreshIndex(app).firstOrNull { it.id == skinId }
        if (have && (entry == null || SkinRepository.cachedVersion(app, skinId) == entry.version)) return null
        if (entry == null) {
            return if (!isOnline(app)) Outcome(false, R.string.event_claim_need_internet)
            else Outcome(false, R.string.event_claim_no_entry)
        }
        return try {
            SkinRepository.download(app, entry)       // sha256 + ukuran + validator diverifikasi di sini
            null
        } catch (e: Exception) {
            Log.w(TAG, "unduh $skinId gagal: ${e.message}")
            if (have) null                              // gagal update, tetapi cache lama masih utuh -> lanjut pakai itu
            else if (!isOnline(app)) Outcome(false, R.string.event_claim_need_internet)
            else Outcome(false, R.string.event_claim_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Klaim: (1) pastikan terunduh, (2) muat+validasi, (3) sukses -> UNLOCKED + dipakai; gagal -> tetap READY_TO_CLAIM. */
    suspend fun claim(context: Context, ev: GameEvent): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!BuildConfig.IS_PREMIUM) return@withContext Outcome(false, R.string.event_btn_premium)
        if (EventProgressManager.statusOf(ev.id) != EventStatus.READY_TO_CLAIM) return@withContext Outcome(false, R.string.event_claim_not_ready)
        ensureDownloaded(app, ev.skinId)?.let { return@withContext it }
        val ok = SkinManager.claimEventSkin(app, ev.skinId) { EventProgressManager.claim(ev.id, ev.skinId) }
        if (ok) Outcome(true, R.string.event_claim_ok, ev.title) else Outcome(false, R.string.event_claim_failed, "skin ditolak validator")
    }

    /** Tombol "Pakai" (UNLOCKED): unduh ulang bila cache hilang & online, lalu pasang lewat SkinManager.useSkin (gerbang aktif). */
    suspend fun useUnlocked(context: Context, skinId: String, name: String? = null): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!EventProgressManager.isSkinAllowed(app, skinId)) return@withContext Outcome(false, R.string.event_btn_locked)
        // Selalu lewat ensureDownloaded: unduh bila belum ada, update bila repo punya versi baru, tanpa jaringan bila sudah terbaru.
        ensureDownloaded(app, skinId)?.let { return@withContext it }
        if (SkinManager.useSkin(app, skinId)) Outcome(true, R.string.event_use_ok, name ?: skinId)
        else Outcome(false, R.string.event_use_failed, name ?: skinId)
    }

    /**
     * Startup: skin event terpilih tetapi cache hilang (Clear Data) dan event masih UNLOCKED -> unduh ulang saat online.
     * Sementara itu SkinManager sudah memakai klasik (tanpa crash). Panggil dari IO setelah refresh event.
     */
    suspend fun restoreSelectedEventSkin(context: Context) {
        val app = context.applicationContext
        val id = SkinManager.selectedId(app)
        if (id == SkinManager.CLASSIC_ID || id == SkinManager.BUNDLED_ID) return
        if (!EventProgressManager.isEventSkin(app, id) || !EventProgressManager.isSkinAllowed(app, id)) return
        if (SkinManager.state is SkinManager.State.Active) return
        if (!isOnline(app)) return
        useUnlocked(app, id)
    }
}
