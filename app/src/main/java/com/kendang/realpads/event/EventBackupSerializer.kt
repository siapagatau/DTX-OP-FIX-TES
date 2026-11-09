package com.kendang.realpads.event

import com.kendang.realpads.BuildConfig
import com.kendang.realpads.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Kesalahan format file backup. [resId] = alasan singkat (string resource), ditampilkan ke pengguna. */
class BackupFormatException(val resId: Int) : Exception("backup format error ($resId)")

/**
 * Format backup = JSON biasa, terbaca manusia, TANPA enkripsi/HMAC/kunci.
 * {
 *   "schemaVersion": 1, "appVersion": "1.7.0", "timestamp": "2026-10-27T10:00:00+0700", "timestampMs": ...,
 *   "events": ["event_id", ...],                 // event yang diikuti
 *   "eventStatus": {"event_id": "UNLOCKED"},     // status per event
 *   "eventSkins":  {"event_id": "skin_id"},      // skin yang dibuka (UNLOCKED) / skin_id terakhir dikenal
 *   "quests": {"quest_id": 120},                 // progres per quest_id
 *   "selectedSkin": "skin_id"                    // skin terpilih saat backup
 * }
 */
object EventBackupSerializer {
    const val SCHEMA = 1
    const val MAX_BYTES = 256 * 1024
    private const val MAX_EVENTS = 200
    private const val MAX_QUESTS = 2000

    /** Bagian ISI (tanpa timestamp/versi) - dipakai untuk hash "lewati bila sama". Urutan kunci stabil. */
    fun core(st: ProgressState, selectedSkin: String?): JSONObject {
        val events = JSONArray(); val status = JSONObject(); val skins = JSONObject()
        for ((id, r) in st.events.toSortedMap()) {
            if (r.joined || r.status != EventStatus.IS_LOCKED) events.put(id)
            status.put(id, r.status.name)
            val sk = r.claimedSkinId ?: r.skinId
            if (sk.isNotEmpty()) skins.put(id, sk)
        }
        val quests = JSONObject()
        for ((id, v) in st.quests.toSortedMap()) quests.put(id, v)
        return JSONObject().put("events", events).put("eventStatus", status).put("eventSkins", skins)
            .put("quests", quests).put("selectedSkin", selectedSkin ?: JSONObject.NULL)
    }

    fun hash(core: JSONObject): String =
        MessageDigest.getInstance("SHA-256").digest(core.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun build(core: JSONObject, nowMs: Long): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(nowMs))
        val out = JSONObject().put("schemaVersion", SCHEMA).put("appVersion", BuildConfig.VERSION_NAME)
            .put("timestamp", iso).put("timestampMs", nowMs)
        for (k in core.keys()) out.put(k, core.get(k))
        return out.toString(2)
    }

    /**
     * Validasi ketat sebelum menyentuh data lokal: <= 256 KB, JSON valid, schema dikenal, tipe benar,
     * progres >= 0 (lalu di-clamp ke target saat digabung). ID berformat salah dibuang; ID berformat benar tetapi belum ada
     * di daftar event saat ini TETAP disimpan (definisi event dinamis - bisa muncul belakangan).
     */
    fun parse(bytes: ByteArray): RestoreData {
        if (bytes.size > MAX_BYTES) throw BackupFormatException(R.string.backup_err_too_big)
        val root = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (e: JSONException) { throw BackupFormatException(R.string.backup_err_json) }
        val v = (root.opt("schemaVersion") as? Number)?.toInt() ?: throw BackupFormatException(R.string.backup_err_schema)
        if (v < 1 || v > SCHEMA) throw BackupFormatException(R.string.backup_err_schema)

        val joined = HashSet<String>()
        root.opt("events")?.let { ev ->
            val arr = ev as? JSONArray ?: throw BackupFormatException(R.string.backup_err_type)
            if (arr.length() > MAX_EVENTS) throw BackupFormatException(R.string.backup_err_too_big)
            for (i in 0 until arr.length()) {
                val id = arr.opt(i) as? String ?: throw BackupFormatException(R.string.backup_err_type)
                if (EventIds.ID_REGEX.matches(id)) joined.add(id)
            }
        }
        val statusMap = HashMap<String, EventStatus>()
        root.opt("eventStatus")?.let { so ->
            val o = so as? JSONObject ?: throw BackupFormatException(R.string.backup_err_type)
            if (o.length() > MAX_EVENTS) throw BackupFormatException(R.string.backup_err_too_big)
            for (id in o.keys()) {
                val s = o.opt(id) as? String ?: throw BackupFormatException(R.string.backup_err_type)
                if (EventIds.ID_REGEX.matches(id)) statusMap[id] = EventStatus.from(s)
            }
        }
        val skinMap = HashMap<String, String>()
        root.opt("eventSkins")?.let { so ->
            val o = so as? JSONObject ?: throw BackupFormatException(R.string.backup_err_type)
            for (id in o.keys()) {
                val s = o.opt(id) as? String ?: throw BackupFormatException(R.string.backup_err_type)
                if (EventIds.ID_REGEX.matches(id) && EventIds.ID_REGEX.matches(s)) skinMap[id] = s
            }
        }
        val quests = HashMap<String, Int>()
        root.opt("quests")?.let { qo ->
            val o = qo as? JSONObject ?: throw BackupFormatException(R.string.backup_err_type)
            if (o.length() > MAX_QUESTS) throw BackupFormatException(R.string.backup_err_too_big)
            for (id in o.keys()) {
                val n = o.opt(id) as? Number ?: throw BackupFormatException(R.string.backup_err_type)
                val d = n.toDouble()
                if (d != Math.floor(d) || d < 0.0 || d > EventIds.MAX_TARGET.toDouble()) throw BackupFormatException(R.string.backup_err_type)
                if (EventIds.ID_REGEX.matches(id)) quests[id] = d.toInt()
            }
        }
        val selected = (root.opt("selectedSkin") as? String)?.takeIf { EventIds.ID_REGEX.matches(it) }

        val events = HashMap<String, EventRecord>()
        for (id in joined + statusMap.keys) {
            val st = statusMap[id] ?: EventStatus.IS_LOCKED
            val sk = skinMap[id]
            events[id] = EventRecord(
                joined = id in joined, status = st,
                skinId = if (st == EventStatus.UNLOCKED) "" else (sk ?: ""),
                claimedSkinId = if (st == EventStatus.UNLOCKED) sk else null
            )
        }
        return RestoreData(events, quests, selected)
    }
}
