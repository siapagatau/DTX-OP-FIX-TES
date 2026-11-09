package com.kendang.realpads.skin

import kotlin.math.hypot

/** Persegi dalam koordinat SVG (viewBox). */
data class SvgRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f

    fun contains(px: Float, py: Float): Boolean = px >= x && px <= right && py >= y && py <= bottom

    /** Jarak dari titik ke tepi persegi (0 jika di dalam). */
    fun distanceTo(px: Float, py: Float): Float {
        val dx = maxOf(x - px, 0f, px - right)
        val dy = maxOf(y - py, 0f, py - bottom)
        return hypot(dx, dy)
    }
}

enum class ControlType {
    PAD, BUTTON, ROTARY, DISPLAY;

    companion object {
        fun from(s: String?): ControlType? = when (s) {
            "pad" -> PAD
            "button" -> BUTTON
            "rotary" -> ROTARY
            "display" -> DISPLAY
            else -> null
        }
    }
}

enum class Trigger { DOWN, TAP }

data class RotaryInfo(
    val cx: Float,
    val cy: Float,
    val angleMin: Float,
    val angleMax: Float,
    val defaultValue: Float
)

data class DisplayInfo(
    val rect: SvgRect,
    /** Template per baris, mis. ["KIT","P{preset:000}"]. */
    val lines: List<String>,
    val textColor: Int
)

data class SkinControl(
    val id: String,
    val type: ControlType,
    /** Nomor pad (1..N) untuk PAD, 0 untuk lainnya. */
    val index: Int,
    val hitId: String?,
    /** Hit area dalam koordinat SVG (null untuk display). */
    val hit: SvgRect?,
    val activeId: String?,
    val trigger: Trigger,
    /** Nama function resmi dari skin.json mapping (mis. "sample.play"). */
    val function: String,
    val params: Map<String, Any?>,
    val apkStatus: String,
    val rotary: RotaryInfo?,
    val display: DisplayInfo?
)

/**
 * Lampu indikator pad (mis. keypad 12 tombol DTX-Multi 12). Menyala saat pad [pad] dipukul (sentuh / MIDI / keyboard).
 * [rect] = area gambar lampu menyala (persegi pertama di dalam elemen SVG [onId]) dalam koordinat SVG.
 */
data class SkinIndicator(
    val id: String,
    val pad: Int,
    val onId: String,
    val rect: SvgRect
) {
    /** Kunci gambar state-menyala di peta layer (dipisah dari id kontrol agar tidak bentrok). */
    val layerKey: String get() = "ind:$id"
}

class SkinDefinition(
    val name: String,
    val version: String,
    val author: String,
    val svgText: String,
    val viewBox: SvgRect,
    val padMin: Int,
    val padMax: Int,
    val controls: List<SkinControl>,
    /** Fungsi/dekorasi yang dicatat skin sebagai tidak ada kontrolnya (hanya informasi). */
    val unsupportedFunctions: List<String>,
    val source: String,
    /** Opsional (skin.json "layout"): wilayah SVG berisi semua pad. Dipakai untuk menentukan bagian skin yang tetap tampil saat panel disembunyikan. */
    val padZoneDecl: SvgRect? = null,
    /** Opsional (skin.json "layout"): wilayah SVG panel kontrol (LCD, tombol, knob) yang bisa disembunyikan. */
    val panelZoneDecl: SvgRect? = null,
    /** Opsional (skin.json "indicators"): lampu yang menyala saat pad dipukul. Kosong = skin tanpa lampu. */
    val indicators: List<SkinIndicator> = emptyList()
) {
    val pads: List<SkinControl> get() = controls.filter { it.type == ControlType.PAD }.sortedBy { it.index }
    val padCount: Int get() = pads.size
    val interactive: List<SkinControl> get() = controls.filter { it.hit != null }
    fun byId(id: String): SkinControl? = controls.firstOrNull { it.id == id }
}

class SkinException(message: String, cause: Throwable? = null) : Exception(message, cause)
