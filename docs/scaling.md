# Scaling & Responsive (v3.2)

Kode: `skin/SkinLayout.kt` (`SkinZone`, `SkinZones`, `SkinLayout`), `skin/HitTester.kt` (ruang layar), `skin/SkinHost.kt`.
- SELURUH skin (zona pad + zona panel) di-stretch memenuhi area skin (skala X/Y boleh beda) - tanpa area kosong, tanpa pemisahan zona.
- Panel disembunyikan -> hanya zona pad yang di-stretch memenuhi area; kontrol panel tidak tampil dan tidak bisa disentuh.
- Render: sekali pada ukuran acuan, TIDAK bergantung status panel; render ulang hanya bila ukuran area keluar dari 60%..120% acuan. Hasil di-cache ke disk.
- Knob diputar dulu lalu di-stretch bersama skin. Sentuh: hit area dipetakan ke layar; tombol/knob kecil diperluas sampai 40dp, pad tidak diperluas.
- Bar sistem disembunyikan (immersive); inset ditangani PadScreen (`safeDrawing`), SkinHost tidak menambah inset.

## Lebar skin (v3.2.1)
- Pengaturan "Lebar skin" (slider di pemilih skin, hanya tampil saat skin aktif), disimpan di `skin_prefs` (`skin_width_percent`, 0..100, default 100). State: `SkinManager.widthPercent`.
- 100 = stretch memenuhi lebar area (perilaku lama). 0 = proporsi asli skin: lebar = tinggi area x rasio skin, di tengah; sisi kiri-kanan kosong (warna latar), tidak bisa disentuh.
- Di antaranya = interpolasi linear. Skin yang rasionya memang lebih lebar dari area tetap penuh.
- Kode: `SkinLayout(widthFraction)` (persegi tujuan di tengah), `SkinHost(widthPercent)`. Ukuran acuan render memakai lebar skin lengkap, jadi menggeser slider / tampil-sembunyi panel tidak memicu render ulang kecuali di luar 60%..120% acuan.
