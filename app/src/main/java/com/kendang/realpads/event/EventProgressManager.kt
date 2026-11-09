package com.kendang.realpads.event

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

data class EventRecord(
    val joined: Boolean,
    val status: EventStatus,
    /** skin_id menurut definisi event terakhir yang dikenal (informasi + penanda "ini skin event"). */
    val skinId: String,
    /** Skin yang BENAR-BENAR dibuka saat klaim. Gerbang SkinManager memakai ini. */
    val claimedSkinId: String? = null,
    val joinedAtMs: Long = 0L,
    /** Hari terakhir (yyyy-MM-dd) yang dihitung untuk misi ACTIVE_DAYS event ini. */
    val lastActiveDay: String? = null,
    /** Judul event terakhir yang dikenal. Dipakai Koleksi supaya skin yang sudah dibuka tetap tampil walau event hilang dari repo. */
    val title: String = ""
)

data class ProgressState(
    val events: Map<String, EventRecord> = emptyMap(),
    /** quest_id -> progres (mentah, <= target saat ditulis). Quest yang belum/tidak ada di daftar TETAP disimpan. */
    val quests: Map<String, Int> = emptyMap(),
    val lastSessionMs: Long = 0L
)

/** Hasil baca backup yang sudah divalidasi (lihat EventBackupSerializer). */
data class RestoreData(
    val events: Map<String, EventRecord>,
    val quests: Map<String, Int>,
    val selectedSkin: String?
)

/**
 * Progres event LOKAL. State = satu objek immutable di StateFlow (update CAS -> thread-safe tanpa lock di jalur pukul).
 *  - Jalur pukul pad: HANYA [onPadHit] = satu AtomicInteger.incrementAndGet(). Digabung tiap [FLUSH_MS] di thread terpisah.
 *  - Disimpan sebagai SATU string JSON ber-schemaVersion di SharedPreferences (realpads_event / event_progress), di-debounce di IO.
 *  - Rekonsiliasi tiap daftar event berubah. Progres event yang hilang dari repo TIDAK dibuang.
 *
 * Aturan end_at: setelah end_at progres berhenti bertambah; event yang sudah READY_TO_CLAIM tetap boleh diklaim
 * (selama definisinya masih ada di daftar); IS_LOCKED yang berakhir tidak bisa diikuti/dilanjutkan; UNLOCKED permanen.
 */
object EventProgressManager {
    private const val TAG = "EventProgress"
    const val PREFS = "realpads_event"            // -> shared_prefs/realpads_event.xml (dirujuk backup_rules.xml)
    private const val KEY = "event_progress"
    private const val KEY_FUTURE = "event_progress_future_backup"
    const val SCHEMA = 1
    private const val FLUSH_MS = 2_000L
    private const val PERSIST_DEBOUNCE_MS = 1_000L
    private const val SESSION_MIN_GAP_MS = 5 * 60_000L

    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state.asStateFlow()

    private val _readyEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** event_id yang baru saja jadi READY_TO_CLAIM (untuk toast ringan). */
    val readyEvents: SharedFlow<String> = _readyEvents

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val padHits = AtomicInteger(0)
    @Volatile private var app: Context? = null
    @Volatile private var defs: List<GameEvent> = emptyList()
    @Volatile private var persistJob: Job? = null

    /** Idempotent. Baca prefs kecil sinkron (sekali), lalu jalankan loop di thread terpisah. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val a = context.applicationContext
        app = a
        _state.value = readFromPrefs(a)
        scope.launch {
            launch { EventRepository.snapshot.collect { snap -> defs = snap.events; mutate { normalize(it) } } }
            launch { while (isActive) { delay(FLUSH_MS); flushHits() } }
        }
    }

    // ------------------------------------------------------------------ hook (jalur panas)

    /** Dipanggil dari onPadDown. HANYA counter atomik: tanpa lock, tanpa I/O, tanpa alokasi. */
    fun onPadHit() { padHits.incrementAndGet() }

    fun onRecordingSaved() = addProgressByType(QuestType.RECORDINGS, 1)
    fun onPresetSaved() = addProgressByType(QuestType.PRESETS_SAVED, 1)

    /** Panggil sekali per start app (savedInstanceState == null). Jeda minimal 5 menit antar sesi. */
    fun onSessionStart() {
        val now = System.currentTimeMillis()
        var counted = false
        mutate { s ->
            counted = now - s.lastSessionMs >= SESSION_MIN_GAP_MS
            if (counted) applyType(s.copy(lastSessionMs = now), QuestType.SESSIONS, 1, now, null) else s
        }
    }

    /** onPause: gabungkan sisa pukulan + tulis segera (proses bisa dimatikan sistem kapan saja). */
    fun flushNow() {
        scope.launch(Dispatchers.IO) { flushHits(); persistNow() }
    }

    private fun flushHits() {
        val n = padHits.getAndSet(0)
        if (n <= 0) return
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        mutate { s -> applyType(applyType(s, QuestType.PAD_HITS, n, now, null), QuestType.ACTIVE_DAYS, 1, now, today) }
    }

    private fun addProgressByType(type: QuestType, delta: Int) {
        if (delta <= 0) return
        val now = System.currentTimeMillis()
        mutate { applyType(it, type, delta, now, null) }
    }

    // ------------------------------------------------------------------ API

    fun joinEvent(eventId: String): Boolean {
        val ev = defs.firstOrNull { it.id == eventId } ?: return false
        val now = System.currentTimeMillis()
        if (ev.hasEnded(now) && _state.value.events[eventId]?.joined != true) return false
        mutate { s ->
            val cur = s.events[eventId]
            if (cur?.joined == true) s
            else normalize(s.copy(events = s.events + (eventId to (cur?.copy(joined = true)
                ?: EventRecord(true, EventStatus.IS_LOCKED, ev.skinId, null, now, null, ev.title)))))
        }
        return _state.value.events[eventId]?.joined == true
    }

    /** Progres tidak pernah turun: nilai efektif = max(lama, [value]) lalu di-clamp ke target (bila quest dikenal). */
    fun setProgress(questId: String, value: Int) {
        if (!EventIds.ID_REGEX.matches(questId)) return
        mutate { s ->
            val cap = targetOf(questId) ?: EventIds.MAX_TARGET
            val next = min(cap, max(s.quests[questId] ?: 0, value.coerceIn(0, EventIds.MAX_TARGET)))
            if (next == (s.quests[questId] ?: 0)) s else normalize(s.copy(quests = s.quests + (questId to next)))
        }
    }

    fun addProgress(questId: String, delta: Int) {
        if (delta <= 0) return
        setProgress(questId, (_state.value.quests[questId] ?: 0).let { (it.toLong() + delta).coerceAtMost(EventIds.MAX_TARGET.toLong()).toInt() })
    }

    fun getProgress(questId: String): Int {
        val v = _state.value.quests[questId] ?: 0
        val cap = targetOf(questId)
        return if (cap != null) min(v, cap) else v
    }

    fun recomputeStatus() = mutate { normalize(it) }

    fun statusOf(eventId: String): EventStatus = _state.value.events[eventId]?.status ?: EventStatus.IS_LOCKED

    /**
     * READY_TO_CLAIM -> UNLOCKED. Dipanggil TEPAT sebelum skin dipasang (lihat SkinManager.claimEventSkin).
     * true bila berhasil; status lain -> false dan tidak ada yang berubah.
     */
    fun claim(eventId: String, skinId: String): Boolean {
        var ok = false
        mutate { s ->
            val r = s.events[eventId]
            ok = r != null && r.status == EventStatus.READY_TO_CLAIM
            if (ok) s.copy(events = s.events + (eventId to r!!.copy(status = EventStatus.UNLOCKED, claimedSkinId = skinId))) else s
        }
        return ok
    }

    /**
     * reset(null) = hapus SEMUA (hanya untuk uji/debug; tidak ada tombolnya di UI).
     * reset(id)   = hapus progres satu event, KECUALI yang sudah UNLOCKED (tidak pernah diturunkan).
     */
    fun reset(eventId: String? = null) {
        if (eventId == null) { mutate { ProgressState() }; return }
        mutate { s ->
            val r = s.events[eventId] ?: return@mutate s
            if (r.status == EventStatus.UNLOCKED) return@mutate s
            val ids = defs.firstOrNull { it.id == eventId }?.quests?.map { it.id }?.toSet() ?: emptySet()
            s.copy(events = s.events - eventId, quests = s.quests.filterKeys { it !in ids })
        }
    }

    /** Gabung hasil restore: nilai TERTINGGI per quest; UNLOCKED tidak diturunkan; quest yang belum ada di daftar tetap disimpan. */
    fun mergeFrom(r: RestoreData) {
        mutate { s ->
            val ev = HashMap(s.events)
            for ((id, incoming) in r.events) {
                val cur = ev[id]
                ev[id] = if (cur == null) incoming else cur.copy(
                    joined = cur.joined || incoming.joined,
                    status = if (incoming.status > cur.status) incoming.status else cur.status,
                    skinId = cur.skinId.ifEmpty { incoming.skinId },
                    claimedSkinId = cur.claimedSkinId ?: incoming.claimedSkinId,
                    joinedAtMs = listOf(cur.joinedAtMs, incoming.joinedAtMs).filter { it > 0 }.minOrNull() ?: 0L,
                    lastActiveDay = listOfNotNull(cur.lastActiveDay, incoming.lastActiveDay).maxOrNull(),
                    title = cur.title.ifEmpty { incoming.title }
                )
            }
            val q = HashMap(s.quests)
            for ((id, v) in r.quests) q[id] = max(q[id] ?: 0, min(v, targetOf(id) ?: EventIds.MAX_TARGET))
            normalize(s.copy(events = ev, quests = q))
        }
    }

    // ------------------------------------------------------------------ gerbang skin (dipakai SkinManager / picker)

    /** true bila [skinId] adalah skin hadiah event (dikenal dari progres lokal ATAU daftar cache/bawaan). */
    fun isEventSkin(context: Context, skinId: String): Boolean {
        val st = currentOrDisk(context)
        if (st.events.values.any { it.skinId == skinId || it.claimedSkinId == skinId }) return true
        return EventRepository.peekSkinIds(context).contains(skinId)
    }

    /** Skin biasa -> true. Skin event -> true HANYA bila sudah UNLOCKED (diklaim). */
    fun isSkinAllowed(context: Context, skinId: String): Boolean {
        val st = currentOrDisk(context)
        if (st.events.values.any { it.status == EventStatus.UNLOCKED && it.claimedSkinId == skinId }) return true
        return !isEventSkin(context, skinId)
    }

    private fun currentOrDisk(context: Context): ProgressState =
        if (started.get()) _state.value else readFromPrefs(context.applicationContext)

    // ------------------------------------------------------------------ inti (murni, tanpa efek samping)

    private fun targetOf(questId: String): Int? {
        for (e in defs) for (q in e.quests) if (q.id == questId) return q.target
        return null
    }

    /** Tambah progres semua quest bertipe [type] pada event yang diikuti, belum UNLOCKED, dan sedang berjalan. */
    private fun applyType(s: ProgressState, type: QuestType, delta: Int, now: Long, today: String?): ProgressState {
        var quests = s.quests
        var events = s.events
        for (ev in defs) {
            val rec = events[ev.id] ?: continue
            if (!rec.joined || rec.status == EventStatus.UNLOCKED || !ev.isRunning(now)) continue
            var recNew = rec
            for (q in ev.quests) {
                if (q.type != type) continue
                var d = delta
                if (type == QuestType.ACTIVE_DAYS) {
                    if (today == null || rec.lastActiveDay == today) continue
                    recNew = recNew.copy(lastActiveDay = today); d = 1
                }
                val cur = quests[q.id] ?: 0
                val next = min(q.target.toLong(), cur.toLong() + d).toInt()
                if (next != cur) quests = quests + (q.id to next)
            }
            if (recNew != rec) events = events + (ev.id to recNew)
        }
        return normalize(s.copy(events = events, quests = quests))
    }

    /**
     * Rekonsiliasi dengan definisi terkini [defs]:
     *  - UNLOCKED tidak pernah diturunkan; selain itu READY_TO_CLAIM <-> IS_LOCKED mengikuti progres vs target
     *    (quest baru -> mulai 0 -> READY_TO_CLAIM kembali IS_LOCKED; target naik/turun -> dibandingkan dengan nilai ter-clamp)
     *  - skin_id berubah: dicatat; event UNLOCKED tetap memakai claimedSkinId
     *  - event yang tidak ada di [defs]: dibiarkan apa adanya (progres disimpan, muncul lagi bila event kembali)
     */
    private fun normalize(s: ProgressState): ProgressState {
        var events = s.events
        for (ev in defs) {
            val rec = events[ev.id] ?: continue
            if (!rec.joined && rec.status != EventStatus.UNLOCKED) continue
            val done = ev.quests.all { (s.quests[it.id] ?: 0) >= it.target }
            val status = when {
                rec.status == EventStatus.UNLOCKED -> EventStatus.UNLOCKED
                done -> EventStatus.READY_TO_CLAIM
                else -> EventStatus.IS_LOCKED
            }
            val upd = rec.copy(status = status, skinId = ev.skinId, title = ev.title)
            if (upd != rec) events = events + (ev.id to upd)
        }
        return if (events === s.events) s else s.copy(events = events)
    }

    private fun mutate(f: (ProgressState) -> ProgressState) {
        val before = _state.value
        _state.update(f)
        val after = _state.value
        if (after === before || after == before) return
        for ((id, r) in after.events) {
            if (r.status == EventStatus.READY_TO_CLAIM && before.events[id]?.status != EventStatus.READY_TO_CLAIM) _readyEvents.tryEmit(id)
        }
        schedulePersist()
        app?.let { EventBackupManager.onProgressChanged(it) }
    }

    // ------------------------------------------------------------------ penyimpanan

    @Synchronized private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) { delay(PERSIST_DEBOUNCE_MS); persistNow() }
    }

    private fun persistNow() {
        val a = app ?: return
        try {
            a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(_state.value).toString()).apply()
        } catch (e: Throwable) { Log.w(TAG, "simpan progres gagal: ${e.message}") }
    }

    internal fun toJson(s: ProgressState): JSONObject {
        val evs = JSONObject()
        for ((id, r) in s.events.toSortedMap()) {
            evs.put(id, JSONObject().put("joined", r.joined).put("status", r.status.name).put("skinId", r.skinId)
                .put("claimedSkinId", r.claimedSkinId ?: JSONObject.NULL).put("joinedAt", r.joinedAtMs)
                .put("lastActiveDay", r.lastActiveDay ?: JSONObject.NULL).put("title", r.title))
        }
        val qs = JSONObject()
        for ((id, v) in s.quests.toSortedMap()) qs.put(id, v)
        return JSONObject().put("schemaVersion", SCHEMA).put("events", evs).put("quests", qs).put("lastSessionMs", s.lastSessionMs)
    }

    private fun readFromPrefs(a: Context): ProgressState {
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = p.getString(KEY, null) ?: return ProgressState()
        return try {
            val root = JSONObject(text)
            val v = root.optInt("schemaVersion", -1)
            if (v > SCHEMA) {
                // Skema lebih baru (downgrade app): simpan salinan mentah, mulai dari default, jangan crash.
                p.edit().putString(KEY_FUTURE, text).apply()
                return ProgressState()
            }
            fromJson(migrate(root, v))
        } catch (e: Exception) {
            Log.w(TAG, "data progres rusak, pakai default: ${e.message}")
            ProgressState()
        }
    }

    /** Titik migrasi skema lama -> terkini. Saat ini hanya v1 (tidak ada yang perlu diubah). */
    internal fun migrate(root: JSONObject, from: Int): JSONObject {
        require(from in 1..SCHEMA) { "schema $from tidak dikenal" }
        return root
    }

    private fun fromJson(root: JSONObject): ProgressState {
        val events = HashMap<String, EventRecord>()
        root.optJSONObject("events")?.let { eo ->
            for (id in eo.keys()) {
                if (!EventIds.ID_REGEX.matches(id)) continue
                val o = eo.optJSONObject(id) ?: continue
                val claimed = (o.opt("claimedSkinId") as? String)?.takeIf { EventIds.ID_REGEX.matches(it) }
                events[id] = EventRecord(
                    joined = o.optBoolean("joined", false),
                    status = EventStatus.from(o.optString("status", "")),
                    skinId = (o.opt("skinId") as? String)?.takeIf { EventIds.ID_REGEX.matches(it) } ?: "",
                    claimedSkinId = claimed,
                    joinedAtMs = o.optLong("joinedAt", 0L),
                    lastActiveDay = (o.opt("lastActiveDay") as? String)?.takeIf { it.length == 10 },
                    title = ((o.opt("title") as? String) ?: "").trim().take(80)
                )
            }
        }
        val quests = HashMap<String, Int>()
        root.optJSONObject("quests")?.let { qo ->
            for (id in qo.keys()) {
                if (!EventIds.ID_REGEX.matches(id)) continue
                val v = (qo.opt(id) as? Number)?.toInt() ?: continue
                quests[id] = v.coerceIn(0, EventIds.MAX_TARGET)
            }
        }
        return ProgressState(events, quests, root.optLong("lastSessionMs", 0L))
    }
}
