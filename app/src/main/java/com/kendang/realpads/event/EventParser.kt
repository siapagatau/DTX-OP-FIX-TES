package com.kendang.realpads.event

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Parser events.json. Isi repo = DATA TIDAK TEPERCAYA:
 *  - field tak dikenal diabaikan; event/quest rusak dilewati (dihitung di [Result.skipped]), tidak pernah crash
 *  - schemaVersion tak dikenal -> SchemaException (pemanggil memakai cache)
 *  - id lewat regex, target_value 1..MAX_TARGET, jumlah event/quest dibatasi
 */
object EventParser {
    private const val TAG = "EventParser"
    const val SUPPORTED_SCHEMA = 1
    const val MAX_EVENTS = 20
    const val MAX_QUESTS_PER_EVENT = 12

    class Result(val events: List<GameEvent>, val skipped: Int)
    class SchemaException(message: String) : Exception(message)

    fun parse(text: String): Result {
        val root = try { JSONObject(text) } catch (e: JSONException) { throw SchemaException("JSON rusak") }
        val v = (root.opt("schemaVersion") as? Number)?.toInt() ?: -1
        if (v != SUPPORTED_SCHEMA) throw SchemaException("schemaVersion $v tidak dikenal")
        val arr = root.optJSONArray("events") ?: throw SchemaException("tanpa 'events'")
        val out = ArrayList<GameEvent>()
        val seenEvents = HashSet<String>()
        val seenQuests = HashSet<String>()   // quest_id harus unik se-file
        var skipped = 0
        for (i in 0 until minOf(arr.length(), MAX_EVENTS * 2)) {
            if (out.size >= MAX_EVENTS) { skipped += arr.length() - i; break }
            val o = arr.optJSONObject(i)
            if (o == null) { skipped++; continue }
            try {
                val ev = parseEvent(o, seenEvents, seenQuests)
                out.add(ev)
            } catch (e: Exception) {
                skipped++
                Log.w(TAG, "event #$i dilewati: ${e.message}")
            }
        }
        return Result(out, skipped)
    }

    private fun parseEvent(o: JSONObject, seenEvents: MutableSet<String>, seenQuests: MutableSet<String>): GameEvent {
        val id = str(o, "event_id")
        require(EventIds.ID_REGEX.matches(id)) { "event_id tidak valid" }
        require(seenEvents.add(id)) { "event_id ganda: $id" }
        val title = str(o, "title").trim().take(80)
        require(title.isNotEmpty()) { "title kosong" }
        val skinId = str(o, "skin_id")
        require(EventIds.ID_REGEX.matches(skinId)) { "skin_id tidak valid" }
        val bannerRaw = str(o, "banner").trim()
        val banner = if (bannerRaw.isNotEmpty() &&
            (EventIds.FILE_REGEX.matches(bannerRaw) || (bannerRaw.startsWith("https://") && bannerRaw.length <= 300 && bannerRaw.none { it.isWhitespace() })))
            bannerRaw else null
        val start = optTime(o, "start_at")
        val end = optTime(o, "end_at")
        require(start == null || end == null || end > start) { "end_at <= start_at" }

        val qs = ArrayList<Quest>()
        val qa = o.optJSONArray("quests")
        if (qa != null) for (j in 0 until qa.length()) {
            if (qs.size >= MAX_QUESTS_PER_EVENT) break
            val q = qa.optJSONObject(j) ?: continue
            val qid = str(q, "quest_id")
            if (!EventIds.ID_REGEX.matches(qid) || qid in seenQuests) continue
            val type = QuestType.from(str(q, "type"))
            if (type == null) { Log.w(TAG, "tipe quest '${str(q, "type")}' dilewati"); continue }
            val tv = q.opt("target_value") as? Number ?: continue
            val d = tv.toDouble()
            if (d != Math.floor(d) || d < 1.0 || d > EventIds.MAX_TARGET.toDouble()) continue
            val qt = str(q, "title").trim().take(80)
            if (qt.isEmpty()) continue
            seenQuests.add(qid)
            qs.add(Quest(qid, qt, str(q, "description").trim().take(200), type, d.toInt()))
        }
        require(qs.isNotEmpty()) { "tidak ada quest valid" }
        return GameEvent(id, title, str(o, "description").trim().take(300), banner, skinId, start, end, qs)
    }

    /** Hanya String asli yang diterima (angka/null/objek -> ""). */
    private fun str(o: JSONObject, key: String): String = (o.opt(key) as? String) ?: ""

    /** Kosong -> null. Ada tapi tidak bisa di-parse -> exception (event dilewati). */
    private fun optTime(o: JSONObject, key: String): Long? {
        val s = str(o, key).trim()
        if (s.isEmpty()) return null
        return parseTime(s) ?: throw IllegalArgumentException("$key tidak valid")
    }

    private fun parseTime(s: String): Long? {
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {}
        try { return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {}
        try { return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (_: DateTimeParseException) {}
        return null
    }
}
