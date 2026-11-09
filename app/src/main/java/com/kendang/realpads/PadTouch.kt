package com.kendang.realpads

import androidx.compose.ui.geometry.Rect

// Jalur sentuh pad (v1.9.0, disederhanakan).
//
// Sebelumnya hit-test ada di dalam pointerInput PadGrid (~100 baris): tiap event, tiap pad
// dikonversi window->lokal lewat LayoutCoordinates, plus dua loop (chip tempo & pad).
// Sekarang alurnya satu garis lurus:
//
//   jari -> pointerInput (1 buah, level grid) -> PadTouchRouter.hit() -> onPadDown() -> nativeTrigger
//
// Semua posisi disimpan SEKALI sebagai Rect koordinat window (diperbarui hanya saat layout
// berubah), jadi per event cuma perbandingan angka - tanpa LayoutCoordinates, tanpa alokasi.
// Kelas ini murni data + matematika (tidak menyentuh Compose/audio) sehingga mudah diuji.
class PadTouchRouter {
    // Posisi pad & chip tempo (tombol −/+ pad yang sedang loop), koordinat window.
    val padRects = HashMap<Int, Rect>()
    val tempoRects = HashMap<Int, Rect>()

    // Titik kiri-atas grid di window; posisi event (relatif grid) + ini = koordinat window.
    var originX = 0f
    var originY = 0f

    // pointerId -> pad yang sedang "ditempati" jari itu. Dipakai supaya hanya pindah pad
    // (bukan tiap event move di pad yang sama) yang dihitung sebagai pukulan baru.
    private val pointerPad = HashMap<Long, Int>()

    // Pad di titik (x,y): yang persis kena, kalau tidak ada -> pad TERDEKAT asal jaraknya
    // <= snapPx (menutup celah 3dp antar pad + jitter sensor). -1 = bukan pad.
    fun padAt(x: Float, y: Float, snapPx: Float): Int {
        var nearest = -1
        var best = snapPx * snapPx
        for ((id, r) in padRects) {
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            val d = dx * dx + dy * dy
            if (d == 0f) return id
            if (d <= best) { best = d; nearest = id }
        }
        return nearest
    }

    private fun inTempoChip(x: Float, y: Float): Boolean {
        for (r in tempoRects.values) {
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
        }
        return false
    }

    // Dipanggil untuk jari yang BARU menyentuh atau BERGESER. Return pad yang harus
    // dibunyikan, atau -1 (tidak ada). Aturan:
    //  - sentuhan di chip tempo bukan pukulan (chip menangani tap-nya sendiri);
    //  - jari baru (belum punya pad) selalu memukul;
    //  - jari yang sudah menempel dan pindah ke pad lain hanya memukul bila slideRoll aktif.
    fun hit(pointerId: Long, x: Float, y: Float, slideRoll: Boolean, snapPx: Float): Int {
        if (inTempoChip(x, y)) return -1
        val pad = padAt(x, y, snapPx)
        if (pad < 0) return -1
        val prev = pointerPad[pointerId]
        if (prev == null || (prev != pad && slideRoll)) {
            pointerPad[pointerId] = pad
            return pad
        }
        return -1
    }

    // Jari terangkat: lupakan pad terakhirnya.
    fun lift(pointerId: Long) {
        pointerPad.remove(pointerId)
    }
}
