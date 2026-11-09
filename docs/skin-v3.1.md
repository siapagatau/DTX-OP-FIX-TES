> **Sebagian digantikan oleh skin-v3.2.md**: bagian 1 (latar panel melar, tinggi maks 150dp) tidak berlaku lagi. Bagian 2 (bug panel hilang) dan 3 (render bertahap + cache) masih berlaku.

# Skin v3.1 - panel bawah, "hilang", dan kecepatan load

## 1. Latar panel bawah ikut melar, isi tetap proporsional
- Skin boleh mendeklarasikan di `skin.json` -> `layout`:
  - `panelBackground`: id elemen latar panel (DTX: `deco-panel`, `deco-panel-edge`). Yang dirender hanya KOLOM TENGAH latar itu
    (gradien vertikal + garis tepi) lalu dimelarkan selebar layar -> tidak ada lagi sisa kiri/kanan berwarna polos.
  - `panelHide`: id elemen yang tidak digambar di isi panel (DTX: `layer-bg`, `body` - latar global & body bertepi bulat).
- Isi panel (LCD, tombol, knob) tetap skala seragam, di tengah, tidak gepeng.
- Tinggi panel dibatasi `min(26% tinggi area, 150dp)` (`SkinLayout.PANEL_MAX_FRACTION` / `PANEL_MAX_DP`) -> di tablet / HP besar
  isi panel tidak jadi raksasa; latar yang melebar mengisi sisanya, pad mengambil ruang selebihnya.
- Skin tanpa `panelBackground`: sisi kiri/kanan diisi dengan memelarkan kolom tepi panel (cadangan).

## 2. Bug panel bawah tiba-tiba hilang / lama muncul
Penyebab: gambar skin dirender untuk ukuran + status panel tertentu, dan DIRENDER ULANG tiap tampil/sembunyi panel atau tombol bawah.
Selama render ulang yang lama, layar masih memakai gambar versi lama yang tidak memuat panel -> yang tampak hanya pita kosong
dan teks LCD kecil.
Perbaikan: gambar tidak lagi bergantung pada layout/status panel. Render sekali pada ukuran acuan (area penuh, panel tampil);
tampil/sembunyi panel dan tombol bawah hanya menggambar ulang (skala GPU, instan). Render ulang hanya bila ukuran area berubah
jauh (di luar 60%..120% ukuran acuan, mis. rotasi).

## 3. Load skin lebih cepat
1. Render bertahap: lapisan INTI (pad + panel + knob) dulu -> skin tampil; state tekan ~20 pad/tombol dirender satu-satu di latar
   belakang (sebelum siap dipakai highlight bawaan, jadi tetap terasa responsif).
2. Cache disk (`cacheDir/skin-render/`, `SkinRenderCache`): setelah sekali dirender, buka APK berikutnya cukup memuat PNG.
   Kunci cache = isi skin + versi renderer + jumlah pad; ukuran area mirip dipakai ulang. Cache otomatis dibersihkan (3 ukuran
   per skin, 6 skin) dan sistem boleh menghapusnya kapan saja (akibatnya hanya render ulang sekali).
3. Tidak ada render ulang saat toggle panel/tombol (lihat no. 2).
Pembukaan pertama setelah memilih skin (atau setelah update skin / ganti ukuran layar) tetap perlu render sekali.
UI klasik (default) tidak terpengaruh sama sekali: tanpa render, tanpa loading.

## Berkas
`SkinRenderer.kt` (buildCore / buildDetails), `SkinRenderCache.kt` (baru), `SkinLayout.kt`, `SkinHost.kt`, `SkinModel.kt`, `SkinParser.kt`,
skin.json/skin.svg v1.4.

## Status verifikasi
Kotlin BELUM dikompilasi (sandbox tanpa Android SDK). Yang sudah diperiksa: struktur SVG DTX untuk pemisahan latar panel
(simulasi), rumus layout, kurung/sintaks. Hasil gambar AndroidSVG hanya bisa dipastikan lewat build + screenshot.
