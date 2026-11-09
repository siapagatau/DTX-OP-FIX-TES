package com.kendang.realpads.skin

/**
 * Kontrak antara Skin Engine dan fungsi APK. Diimplementasikan di PadScreen (MainActivity.kt) dengan
 * memanggil fungsi yang SUDAH ADA - modul skin tidak bergantung pada MainActivity.
 */
interface SkinActions {
    fun playPad(pad: Int)               // onPadDown(pad)
    fun toggleMusic()                   // onMusicToggle()
    fun loadFile()                      // showLoadSourceChooser = true
    fun exportFile()                    // showExportConfirmDialog = true
    fun openPadSettings()               // showPadVolumeSettings = true (setelah refresh nilai per pad)
    fun selectPreset(preset: Int)       // batas 1..presetCount + kunci FREE (-> openProUpgradeLink)
    fun setKendangVolume(volume: Float) // onKendangVolumeChange (rentang APK 0..1.5)

    // --- fungsi APK lain: dipanggil dari tombol di dalam skin (lihat FunctionRegistry) ---
    fun toggleRecord()                  // onRecordToggle()
    fun toggleScreenRecord()            // onScreenRecordToggle()
    fun openSetSound()                  // showSetSoundSourceChooser (butuh pad terpilih)
    fun openTheme()                     // showColorPicker / upgrade link (FREE)
    fun seekMusic(seconds: Double)      // onSeekMusic
    fun setMusicVolume(volume: Float)   // onMusicVolumeChange (rentang APK 0..1.5)

    // --- UI ASLI APK (bukan dialog baru): tombol skin hanya menampilkan/menyembunyikan panel bawaan ---
    fun toggleSidePanels()              // tampil/sembunyi panel preset (kiri) + panel volume/musik (kanan) bawaan APK
    fun openSkinPicker()                // showSkinPicker = true
}

/** Snapshot state APK yang dibutuhkan skin (LCD, knob, menu melayang). Dibangun ulang tiap recompose. */
data class SkinUiState(
    val currentPreset: Int,
    val presetCount: Int,
    val freeUnlockedPresets: Int,
    val kendangVolume: Float,
    val isRecording: Boolean,
    val recordEnabled: Boolean,
    val isScreenRecording: Boolean,
    val screenRecordEnabled: Boolean,
    val selectedPad: Int?,
    val songLoaded: Boolean,
    val isMusicPlaying: Boolean,
    val musicPositionSec: Double,
    val musicDurationSec: Double,
    val musicVolume: Float,
    val themeLocked: Boolean
)
