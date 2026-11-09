# Skin v3.2 - SELURUH skin di-stretch (menggantikan pemisahan zona v3 / v3.1)

Keputusan: tidak ada lagi pemisahan "zona pad melar" vs "panel skala seragam" vs "latar panel melar". Semua bagian skin
diperlakukan sama: SATU gambar di-stretch memenuhi area. Desain skin asli tidak diubah (skin.json v1.5 tanpa deklarasi pemisah latar).

## Cara kerja
- `SkinLayout`: satu wilayah SVG -> seluruh area. Panel tampil = wilayah seluruh skin (`SkinZones.fullZone` = padZone + panelZone).
  Panel disembunyikan = wilayah padZone saja (pad memenuhi seluruh area; kontrol panel tak tampil & tak bisa disentuh).
- `SkinRenderer`: satu bitmap `base` (seluruh skin, skala X = lebar area / lebar skin, skala Y = tinggi area / tinggi zona pad) + gambar knob utuh.
  Saat panel disembunyikan, SkinHost hanya menggambar potongan atas bitmap yang sama (tajam, tanpa render ulang).
- Knob: gambar dirender utuh (bulat), lalu SkinHost memutar DULU baru men-stretch -> putaran tetap benar walau skin melar.
- Sentuhan: hit area dipetakan lewat pemetaan yang sama -> selalu pas dengan gambar.
- Tetap dari v3.1: render bertahap (inti dulu, state tekan menyusul), cache disk, tidak ada render ulang saat tampil/sembunyi panel
  atau tombol bawah, bug "panel hilang" tidak kembali.

## Dihapus
Tinggi maks panel (26% / 150dp), latar panel terpisah (`panelBackground` / `panelHide`), pita panel, skala seragam panel.

## Konsekuensi
Di layar sangat lebar bentuk bulat (knob, tombol bulat) tampil lonjong karena ikut di-stretch - sesuai keputusan "stretch semua".

## Status verifikasi
Kotlin BELUM dikompilasi (sandbox tanpa Android SDK). Rumus pemetaan disimulasikan; hasil gambar AndroidSVG hanya bisa dipastikan lewat build + screenshot.
