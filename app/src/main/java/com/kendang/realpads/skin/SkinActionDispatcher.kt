package com.kendang.realpads.skin

import android.util.Log

/** Konversi knob skin (0..1) <-> volume pad APK (0..1.5, lihat MusicSidePanel valueRange). */
object VolumeMapping {
    const val APK_MAX = 1.5f
    fun toKnob(apk: Float): Float = (apk / APK_MAX).coerceIn(0f, 1f)
    fun toApk(knob: Float): Float = knob.coerceIn(0f, 1f) * APK_MAX
}

/**
 * Menghubungkan `function` di skin.json ke SkinActions. Hanya nama dari FunctionRegistry yang dikenal;
 * selain itu diabaikan (dan dilog), tidak ada aksi palsu.
 */
class SkinActionDispatcher(
    private val actions: () -> SkinActions,
    private val ui: () -> SkinUiState
) {
    companion object { const val SEEK_STEP_SEC = 5.0 }

    /**
     * Preset berikutnya/sebelumnya dengan PUTAR BALIK: di preset terakhir -> NEXT kembali ke 1, di preset 1 -> PREV
     * lompat ke preset terakhir. Batas putaran = preset yang bisa dipakai (versi FREE: hanya yang tidak terkunci,
     * supaya tombol tidak terus-menerus membuka halaman upgrade; preset terkunci tetap bisa dicoba dari panel preset).
     */
    private fun stepPreset(s: SkinUiState, dir: Int): Int {
        val last = minOf(s.presetCount, s.freeUnlockedPresets).coerceAtLeast(1)
        val cur = s.currentPreset.coerceIn(1, last)
        val n = cur + dir
        return when {
            n > last -> 1
            n < 1 -> last
            else -> n
        }
    }

    /** Dipanggil saat kontrol "ditekan" (pad: down; tombol: tap). @return true bila function dikenal. */
    fun trigger(c: SkinControl): Boolean {
        val a = actions()
        val s = ui()
        when (c.function) {
            FunctionRegistry.SAMPLE_PLAY -> {
                val pad = (c.params["pad"] as? Number)?.toInt() ?: c.index
                a.playPad(pad)
            }
            FunctionRegistry.MUSIC_TOGGLE -> a.toggleMusic()
            FunctionRegistry.MUSIC_PANEL -> a.toggleSidePanels()   // panel volume/musik ASLI APK (tidak ada dialog baru)
            FunctionRegistry.MUSIC_SEEK_BACK -> seekBy(a, s, -SEEK_STEP_SEC)
            FunctionRegistry.MUSIC_SEEK_FORWARD -> seekBy(a, s, SEEK_STEP_SEC)
            FunctionRegistry.PRESET_PREV -> a.selectPreset(stepPreset(s, -1))
            FunctionRegistry.PRESET_NEXT -> a.selectPreset(stepPreset(s, +1))
            FunctionRegistry.PRESET_MENU -> a.toggleSidePanels()   // panel preset ASLI APK (tidak ada dialog baru)
            FunctionRegistry.FILE_LOAD -> a.loadFile()
            FunctionRegistry.FILE_EXPORT -> a.exportFile()
            FunctionRegistry.PAD_SETTINGS_OPEN -> a.openPadSettings()
            FunctionRegistry.SOUND_SET -> a.openSetSound()
            FunctionRegistry.THEME_OPEN -> a.openTheme()
            FunctionRegistry.SKIN_PICK -> a.openSkinPicker()
            FunctionRegistry.RECORD_TOGGLE -> a.toggleRecord()
            FunctionRegistry.SCREENRECORD_TOGGLE -> a.toggleScreenRecord()
            else -> {
                Log.w("SkinDispatcher", "function tidak dikenal untuk ${c.id}: '${c.function}'")
                return false
            }
        }
        return true
    }

    private fun seekBy(a: SkinActions, s: SkinUiState, deltaSec: Double) {
        if (!s.songLoaded || s.musicDurationSec <= 0.0) return
        a.seekMusic((s.musicPositionSec + deltaSec).coerceIn(0.0, s.musicDurationSec))
    }

    /** Nilai knob 0..1 -> fungsi APK. */
    fun setRotary(c: SkinControl, knobValue: Float) {
        when (c.function) {
            FunctionRegistry.VOLUME_KENDANG -> actions().setKendangVolume(VolumeMapping.toApk(knobValue))
            else -> Log.w("SkinDispatcher", "rotary function tidak dikenal: ${c.function}")
        }
    }
}

/** Mengisi template display, mis. "P{preset:000}" -> "P001". Token tak dikenal dibiarkan apa adanya. */
object DisplayTemplate {
    private val token = Regex("\\{(\\w+)(?::(0+))?\\}")

    fun render(line: String, ui: SkinUiState): String = token.replace(line) { m ->
        val value: Int? = when (m.groupValues[1]) {
            "preset" -> ui.currentPreset
            "presetCount" -> ui.presetCount
            else -> null
        }
        if (value == null) m.value else value.toString().padStart(m.groupValues[2].length, '0')
    }
}
