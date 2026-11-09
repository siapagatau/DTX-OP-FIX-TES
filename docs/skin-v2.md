# Skin v2 (Stage 15) - apa yang berubah dan kenapa

Laporan masalah (screenshot): skin tampak ter-zoom dan terpotong (panel bawah hilang), ada kotak abu-abu berbingkai
kuning yang nyangkut di atas pad-4, dan muncul tombol titik tiga yang tidak ada di desain skin.

## Akar masalah -> perbaikan
| Gejala | Penyebab | Perbaikan |
|---|---|---|
| Skin ter-zoom ~1,26x & terpotong; layer tekan tergambar di skala benar sehingga tidak pas dengan pad | `SkinRenderer` menyerahkan skala ke `RenderOptions.viewBox/viewPort`, tetapi root SVG punya `width/height=900` -> AndroidSVG menggambar 1:1 lalu dipotong ke bitmap | Root dinormalisasi (tanpa width/height); SVG selalu dirender di viewport = viewBox; skala & wilayah dipasang lewat matriks `Canvas` sendiri (`SkinRenderer.renderDoc`) |
| Kotak pad "nyangkut" menyala | `pointerInput` di-key ke `vt`/`hitTester`/inset -> handler restart saat jari masih menekan -> hitungan "ditekan" tidak pernah turun | `pointerInput(skin)` saja; `vt`/`hitTester` dibaca lewat `rememberUpdatedState`; semua jari terangkat -> `pressed` dibersihkan paksa |
| Skin bergeser dari tengah | Inset display cutout dihitung dua kali (`windowInsetsPadding(safeDrawing)` di PadScreen + `SafeInsets` di SkinHost) | SkinHost tidak menambah inset lagi |
| Layar kembali kosong saat ukuran berubah | `produceState` di-reset tiap kunci berubah | Gambar lama dipertahankan sampai gambar baru siap; render ulang hanya bila skala berubah >~2% |
| Tombol titik tiga + menu melayang | `NativeFloatingMenu` ditambahkan untuk fungsi APK yang tidak punya kontrol di skin | DIHAPUS. Fungsinya dipindah ke tombol di dalam skin (tabel di bawah) |

## Prinsip
1. Skin adalah satu-satunya UI layar utama. Tidak ada tombol/menu tambahan di luar gambar skin.
2. Setiap fungsi APK diakses lewat kontrol di dalam skin (`FunctionRegistry`). Dialog hanya terbuka setelah kontrolnya ditekan.
3. Posisi/skala ditentukan kode sendiri, bukan perilaku default library SVG.
4. Tombol tidak wajib punya gambar state-active; tanpa itu SkinHost memberi highlight putih transparan.

## Pemetaan tombol skin-01 v1.2 (label di gambar tidak diubah; bisa dipetakan ulang di skin.json)
| Tombol di gambar | id | Function |
|---|---|---|
| KIT | `btn-preset-menu` | `preset.menu` |
| PTN ▶/■ | `btn-play` | `music.toggle` |
| WAVE | `btn-load` | `file.load` |
| UTILITY | `btn-settings` | `padSettings.open` |
| STORE | `btn-export` | `file.export` |
| DEC / INC | `btn-preset-prev/next` | `preset.prev/next` |
| MIDI | `btn-music` | `music.panel` (volume musik + posisi) |
| VOICE | `btn-voice` | `sound.set` (pad terakhir yang diketuk) |
| TAP (bulat) | `btn-screenrec` | `screenrecord.toggle` |
| EXIT | `btn-skin` | `skin.pick` |
| ENTER | `btn-rec` | `record.toggle` |
| SHIFT | `btn-theme` | `theme.open` |
| ◄ / ► | `btn-seek-back/fwd` | `music.seekBack/seekForward` (5 detik) |
| knob VOLUME | `vol-knob` | `volume.kendang` |
| ▲▼, keypad | - | dekorasi |

## Status verifikasi
Kotlin BELUM dikompilasi (sandbox tanpa Android SDK). Yang sudah dicek: konsistensi skin.json/skin.svg/FunctionRegistry,
hit area, dan simulasi geometri di Chromium (`tools/sim15_skinv2.py`, `validation/stage15-sim.md`).
Perilaku AndroidSVG di perangkat hanya bisa dipastikan lewat build + screenshot.
