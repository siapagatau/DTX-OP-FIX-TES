package com.kendang.realpads.skin

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Persegi di koordinat layar (piksel). */
data class PxRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= l && x <= r && y >= t && y <= b

    /** Jarak dari titik ke tepi persegi (0 jika di dalam). */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = max(max(l - x, 0f), x - r)
        val dy = max(max(t - y, 0f), y - b)
        return hypot(dx, dy)
    }
}

/**
 * Pemetaan wilayah SVG [svg] -> persegi layar [dst]. Skala X dan Y BOLEH berbeda: SELURUH skin dimelarkan
 * (stretch) memenuhi area, jadi tidak ada area kosong dan semua bagian (pad, panel, tombol) diperlakukan sama.
 */
class SkinZone(val svg: SvgRect, val dst: PxRect) {
    val sx: Float = dst.w / svg.w
    val sy: Float = dst.h / svg.h
    fun x(v: Float): Float = dst.l + (v - svg.x) * sx
    fun y(v: Float): Float = dst.t + (v - svg.y) * sy
    fun rect(r: SvgRect): PxRect = PxRect(x(r.x), y(r.y), x(r.right), y(r.bottom))
}

/**
 * Wilayah skin yang ditampilkan (koordinat SVG):
 *  - [padZone]  : membungkus semua pad. Dari skin.json "layout.padZone"; bila tidak ada = gabungan hit pad + 2 unit.
 *  - [panelZone]: kontrol non-pad (LCD, tombol, knob). Dari "layout.panelZone"; bila tidak ada = dari tepi bawah padZone
 *                 sampai kontrol non-pad terbawah + 30 unit. Tanpa kontrol non-pad -> null.
 *  - [fullZone] : gabungan keduanya = seluruh isi skin yang dipakai (tepi kosong viewBox dibuang).
 */
object SkinZones {
    private const val PAD_MARGIN = 2f
    private const val PANEL_BOTTOM_MARGIN = 30f

    private fun clipToViewBox(r: SvgRect, vb: SvgRect): SvgRect {
        val l = max(r.x, vb.x)
        val t = max(r.y, vb.y)
        val rr = min(r.right, vb.right)
        val b = min(r.bottom, vb.bottom)
        return if (rr > l && b > t) SvgRect(l, t, rr - l, b - t) else vb
    }

    fun padZone(skin: SkinDefinition): SvgRect {
        skin.padZoneDecl?.let { return clipToViewBox(it, skin.viewBox) }
        val rects = skin.controls.filter { it.type == ControlType.PAD }.mapNotNull { it.hit }
        if (rects.isEmpty()) return skin.viewBox
        val l = rects.minOf { it.x } - PAD_MARGIN
        val t = rects.minOf { it.y } - PAD_MARGIN
        val r = rects.maxOf { it.right } + PAD_MARGIN
        val b = rects.maxOf { it.bottom } + PAD_MARGIN
        return clipToViewBox(SvgRect(l, t, r - l, b - t), skin.viewBox)
    }

    fun panelZone(skin: SkinDefinition): SvgRect? {
        val others = skin.controls.filter { it.type != ControlType.PAD }
        if (others.none { it.hit != null }) return null
        skin.panelZoneDecl?.let { return clipToViewBox(it, skin.viewBox) }
        val pz = padZone(skin)
        val bottom = min(skin.viewBox.bottom, others.maxOf { it.hit?.bottom ?: Float.NEGATIVE_INFINITY } + PANEL_BOTTOM_MARGIN)
        if (bottom - pz.bottom < 8f) return null
        return clipToViewBox(SvgRect(pz.x, pz.bottom, pz.w, bottom - pz.bottom), skin.viewBox)
    }

    fun fullZone(skin: SkinDefinition): SvgRect {
        val p = padZone(skin)
        val q = panelZone(skin) ?: return p
        val l = min(p.x, q.x); val t = min(p.y, q.y)
        val r = max(p.right, q.right); val b = max(p.bottom, q.bottom)
        return SvgRect(l, t, r - l, b - t)
    }
}

/**
 * Tata letak skin di layar untuk ukuran [wPx] x [hPx]: SATU wilayah skin di-stretch memenuhi seluruh area.
 *  - panel TAMPIL   : wilayah = seluruh skin (pad + panel).
 *  - panel SEMBUNYI : wilayah = zona pad saja -> pad memenuhi seluruh area; kontrol panel tidak tampil & tidak bisa disentuh.
 *
 * Gambar hasil render tidak bergantung pada objek ini (hanya dipetakan ke layar saat digambar), jadi
 * tampil/sembunyi panel atau tombol bawah tidak memicu render ulang.
 */
class SkinLayout(
    skin: SkinDefinition, val wPx: Float, val hPx: Float, showPanel: Boolean,
    /** 0..1: 1 = stretch penuh selebar area; 0 = proporsi asli skin (lebar = tinggi x rasio skin), di tengah. */
    widthFraction: Float = 1f
) {
    /** Seluruh isi skin (pad + panel). */
    val full: SvgRect = SkinZones.fullZone(skin)
    /** Wilayah yang sedang tampil. */
    val region: SvgRect = if (showPanel) full else SkinZones.padZone(skin)
    val zone: SkinZone = run {
        val aw = max(wPx, 1f)
        val ah = max(hPx, 1f)
        // Lebar asli = tinggi area x rasio skin; tidak pernah melebihi lebar area (skin yang memang lebih lebar tetap penuh).
        val natural = if (region.h > 0f) ah * region.w / region.h else aw
        val minW = min(aw, natural)
        val cw = (minW + (aw - minW) * widthFraction.coerceIn(0f, 1f)).coerceIn(1f, aw)
        val left = (aw - cw) / 2f
        SkinZone(region, PxRect(left, 0f, left + cw, ah))
    }

    /** Hit area / bagian skin [r] sedang tampil (berada di dalam wilayah)? */
    fun visible(r: SvgRect): Boolean =
        r.x >= region.x - 0.5f && r.y >= region.y - 0.5f && r.right <= region.right + 0.5f && r.bottom <= region.bottom + 0.5f

    /** Posisi hit area kontrol di layar; null bila kontrolnya sedang tidak tampil (panel disembunyikan). */
    fun rectOf(c: SkinControl): PxRect? {
        val hit = c.hit ?: return null
        return if (visible(hit)) zone.rect(hit) else null
    }
}
