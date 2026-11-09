package com.kendang.realpads.skin

/**
 * Stage 07 - mode 12/16 pad. Jumlah pad TIDAK di-hardcode: dibaca dari data-pad-min/max (padMin/padMax)
 * dan elemen pad-N yang benar-benar ada di skin. Konstanta di bawah hanya dua mode yang dikenal APK
 * (mirror PresetStorage.BASE_PAD / MAX_PAD; modul skin tidak bergantung ke kelas APK).
 */
const val PAD_MODE_BASE = 12
const val PAD_MODE_EXTENDED = 16
const val PAD_EXTRA_UNSUPPORTED_MESSAGE = "Pengaturan tambahan pad tidak didukung untuk skin ini."

data class PadSupport(
    /** Mode 12 pad tersedia untuk skin ini. */
    val can12: Boolean,
    /** Mode 16 pad tersedia untuk skin ini. */
    val can16: Boolean,
    /** Pilihan 12/16 bisa diganti user (kedua mode tersedia). Jika false -> opsi dinonaktifkan + pesan. */
    val toggleSupported: Boolean
)

object PadModes {
    /** Pad yang benar-benar dideklarasikan skin, dibatasi data-pad-max. */
    private fun declared(skin: SkinDefinition): Int = minOf(skin.padCount, skin.padMax)

    fun support(skin: SkinDefinition): PadSupport {
        val hi = declared(skin)
        val lo = skin.padMin
        val can12 = lo <= PAD_MODE_BASE && PAD_MODE_BASE <= hi
        val can16 = lo <= PAD_MODE_EXTENDED && PAD_MODE_EXTENDED <= hi
        return PadSupport(can12, can16, can12 && can16)
    }

    /**
     * Jumlah pad yang ditampilkan/aktif disentuh. [wants16] = pilihan user (APK: ExtraPadState.isActiveFor(preset)).
     * Hanya berpengaruh bila skin mendukung 12 DAN 16; selain itu skin yang menentukan (semua pad yang dideklarasikan).
     */
    fun visibleCount(skin: SkinDefinition, wants16: Boolean): Int {
        if (support(skin).toggleSupported) return if (wants16) PAD_MODE_EXTENDED else PAD_MODE_BASE
        return declared(skin).coerceAtLeast(1)   // skin menentukan: tampilkan semua pad yang dideklarasikan
    }
}
