package com.kendang.realpads.skin

/**
 * Katalog function RESMI yang boleh dipakai di skin.json (mapping[id].function).
 * Skin hanya boleh memakai nama-nama ini; function tidak dikenal = skin ditolak oleh SkinValidator
 * (tidak ada fungsi palsu).
 *
 * Prinsip Skin v2: SEMUA fungsi APK diakses lewat kontrol DI DALAM skin. Tidak ada tombol/menu
 * tambahan di luar gambar skin. Skin v3: TIDAK ada dialog buatan skin. `preset.menu` dan `music.panel`
 * menampilkan/menyembunyikan panel preset & panel volume/musik ASLI APK; `skin.pick` membuka pemilih skin.
 */
object FunctionRegistry {
    // pad
    const val SAMPLE_PLAY = "sample.play"
    // musik
    const val MUSIC_TOGGLE = "music.toggle"
    const val MUSIC_PANEL = "music.panel"
    const val MUSIC_SEEK_BACK = "music.seekBack"
    const val MUSIC_SEEK_FORWARD = "music.seekForward"
    // preset
    const val PRESET_PREV = "preset.prev"
    const val PRESET_NEXT = "preset.next"
    const val PRESET_MENU = "preset.menu"
    // berkas & pengaturan
    const val FILE_LOAD = "file.load"
    const val FILE_EXPORT = "file.export"
    const val PAD_SETTINGS_OPEN = "padSettings.open"
    const val SOUND_SET = "sound.set"
    const val THEME_OPEN = "theme.open"
    const val SKIN_PICK = "skin.pick"
    // rekam
    const val RECORD_TOGGLE = "record.toggle"
    const val SCREENRECORD_TOGGLE = "screenrecord.toggle"
    // putar & tampilan
    const val VOLUME_KENDANG = "volume.kendang"
    const val DISPLAY_PRESET = "display.preset"

    /** Fungsi tombol (tap). */
    val buttonFunctions: Set<String> = setOf(
        MUSIC_TOGGLE, MUSIC_PANEL, MUSIC_SEEK_BACK, MUSIC_SEEK_FORWARD,
        PRESET_PREV, PRESET_NEXT, PRESET_MENU,
        FILE_LOAD, FILE_EXPORT, PAD_SETTINGS_OPEN, SOUND_SET, THEME_OPEN, SKIN_PICK,
        RECORD_TOGGLE, SCREENRECORD_TOGGLE
    )

    val official: Set<String> = buttonFunctions + setOf(SAMPLE_PLAY, VOLUME_KENDANG, DISPLAY_PRESET)

    /** Fungsi yang cocok untuk tiap tipe kontrol (dipakai validator). */
    fun allowedFor(type: ControlType): Set<String> = when (type) {
        ControlType.PAD -> setOf(SAMPLE_PLAY)
        ControlType.BUTTON -> buttonFunctions
        ControlType.ROTARY -> setOf(VOLUME_KENDANG)
        ControlType.DISPLAY -> setOf(DISPLAY_PRESET)
    }
}
