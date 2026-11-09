package com.kendang.realpads.event

/** Tipe misi yang DIKENAL klien. Tipe lain di events.json dilewati; tipe baru butuh rilis APK. */
enum class QuestType {
    PAD_HITS, SESSIONS, RECORDINGS, PRESETS_SAVED, ACTIVE_DAYS;

    companion object {
        fun from(s: String?): QuestType? = values().firstOrNull { it.name == s }
    }
}

data class Quest(
    val id: String,
    val title: String,
    val description: String,
    val type: QuestType,
    val target: Int
)

data class GameEvent(
    val id: String,
    val title: String,
    val description: String,
    /** Nama file banner (relatif ke <folder events.json>/banners/) atau URL https penuh; null = tanpa banner. */
    val banner: String?,
    val skinId: String,
    val startAtMs: Long?,
    val endAtMs: Long?,
    val quests: List<Quest>
) {
    fun hasStarted(now: Long) = startAtMs == null || now >= startAtMs
    fun hasEnded(now: Long) = endAtMs != null && now >= endAtMs
    /** Progres HANYA bertambah selagi event berjalan. */
    fun isRunning(now: Long) = hasStarted(now) && !hasEnded(now)
}

/** Urutan enum = tingkat kemajuan (dipakai saat menggabung backup). UNLOCKED tidak pernah diturunkan. */
enum class EventStatus {
    IS_LOCKED, READY_TO_CLAIM, UNLOCKED;

    companion object {
        fun from(s: String?): EventStatus = values().firstOrNull { it.name == s } ?: IS_LOCKED
    }
}

enum class DataSource { ONLINE, CACHE, BUNDLED, NONE }

data class EventsSnapshot(
    val events: List<GameEvent>,
    val source: DataSource,
    val lastError: String? = null,
    val skippedItems: Int = 0
)

object EventIds {
    /** Sama dengan SkinRepository.ID_REGEX (itu private, jadi ditiru). */
    val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    val FILE_REGEX = Regex("^[A-Za-z0-9_.-]{1,64}$")
    const val MAX_TARGET = 10_000_000
}
