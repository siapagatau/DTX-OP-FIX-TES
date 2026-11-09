package com.kendang.realpads.skin

/**
 * Touch layer: menentukan kontrol yang kena sentuh dari hit area SVG yang SUDAH dipetakan ke layar
 * ([SkinLayout.rectOf]) - jadi sentuhan selalu pas dengan gambar, walau zona pad dimelarkan.
 *
 * Aturan:
 *  1. Sentuhan tepat di dalam hit area -> kontrol itu menang (pad besar tidak "dicuri" tombol kecil).
 *  2. Jika tidak ada yang kena, tombol/knob kecil diperluas target sentuhnya sampai [expandDp] di ruang layar;
 *     bila beberapa kandidat, yang terdekat (jarak ke tepi, lalu ke pusat) menang -> terbatas oleh tetangga.
 *  3. Pad TIDAK diperluas (celah antar-pad memang kosong).
 *
 * @param targets pasangan kontrol -> hit area di layar. Kontrol yang sedang tidak tampil tidak boleh ada di sini.
 */
class HitTester(private val targets: List<Pair<SkinControl, PxRect>>, private val expandDp: Float = 40f) {

    fun hit(x: Float, y: Float, density: Float): SkinControl? {
        var exact: SkinControl? = null
        var exactArea = Float.MAX_VALUE
        for ((c, r) in targets) {
            if (r.contains(x, y)) {
                val area = r.w * r.h
                if (area < exactArea) { exact = c; exactArea = area }
            }
        }
        if (exact != null) return exact

        val margin = expandDp * density
        var best: SkinControl? = null
        var bestD = Float.MAX_VALUE
        var bestC = Float.MAX_VALUE
        for ((c, r) in targets) {
            if (c.type == ControlType.PAD) continue
            val d = r.distanceTo(x, y)
            if (d > margin) continue
            val cd = kotlin.math.hypot(r.cx - x, r.cy - y)
            if (d < bestD || (d == bestD && cd < bestC)) { best = c; bestD = d; bestC = cd }
        }
        return best
    }
}
