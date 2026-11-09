# Skin Engine (v2, Stage 15) — ringkasan arsitektur

Lihat `skin-v2.md` untuk apa yang berubah dan kenapa.

Paket: `app/src/main/java/com/kendang/realpads/skin/`

| File | Peran |
|---|---|
| `SkinModel.kt` | `SvgRect`, `SkinControl`, `SkinDefinition`, `SkinException` |
| `SvgDom.kt` | parse/serialize DOM SVG (tolak DOCTYPE/ENTITY), cari elemen by id |
| `SkinParser.kt` | skin.json + skin.svg → `SkinDefinition` (posisi dari `hit-*` rect, jumlah pad dari `pad-N`) |
| `SkinValidator.kt` | metadata root, tag/atribut terlarang, ID, hit area dalam viewBox, function resmi, pad berurutan |
| `FunctionRegistry.kt` | katalog function resmi + tipe kontrol yang boleh memakainya |
| `SkinLoader.kt` | muat dari assets (default offline) atau folder (skin unduhan, Stage 08) |
| `SkinRenderer.kt` | pecah SVG jadi layer (base / active / rotary) lewat DOM, render tiap layer dengan AndroidSVG ke Bitmap |
| `HitTester.kt` | `ViewportTransform` (fit-center) + touch layer dari hit area (+ perluasan ±40dp untuk tombol/knob) |
| `SkinActions.kt` | kontrak ke fungsi APK + `SkinUiState` |
| `SkinActionDispatcher.kt` | `function` skin.json → `SkinActions`; `VolumeMapping`; `DisplayTemplate` |
| `SkinManager.kt` | state `Loading/Active/Failed`, muat+validasi, fallback ke UI klasik |
| `SkinHost.kt` | Composable: gambar layer, multi-touch, knob (circular + vertikal), teks LCD |
| `SkinLayout.kt` | zona pad (melar) + zona panel (seragam, bisa disembunyikan) |
| `SkinToggleTab.kt` | tab 3 tombol: panel asli APK / panel skin / tombol utama bawah |

## Alur
`SkinManager.ensureLoaded` (IO) → `SkinLoader` → `SkinParser` → `SkinValidator` → `State.Active(skin)` →
`SkinHost` render layer (Dispatchers.Default) → sentuhan → `HitTester` → `SkinActionDispatcher` → `SkinActions` (diimplementasi di `PadScreen`, memanggil fungsi lama).

## Pilihan library SVG: AndroidSVG 1.4 (`com.caverock:androidsvg-aar`)
Dipakai hanya untuk MERENDER visual. Skin memakai linearGradient, radialGradient, pattern, path/arc, ellipse, polyline, text, rotate, dash-array — semuanya didukung AndroidSVG. Renderer SVG buatan sendiri (gradien/pattern/arc) dinilai berisiko. State dan interaksi ditangani engine sendiri, karena AndroidSVG tidak bisa mengubah DOM saat jalan; maka SVG dipecah jadi layer:
1. **base** — semua kecuali `vol-knob`, `hit-*`, teks placeholder `slot-display`; `*-active` tetap `display=none`.
2. **active** — tiap `*-active` sendiri (dipotong ke hit area); digambar dengan alpha = tekan atau kedip `flashIntensity` APK.
3. **rotary** — grup `vol-knob` tanpa `transform` awal; engine memutar di sekitar `data-cx/data-cy`: `angle = min + (max-min)*nilai` (menimpa `rotate(75)` di SVG).

Batasan AndroidSVG (dicatat, jangan dipakai skin baru): tidak ada `<filter>`; `letter-spacing` diabaikan (pengaruh kecil pada teks label); CSS terbatas.

## Integrasi di MainActivity.kt (minimal, fitur lama tidak dihapus)
- import 4 kelas skin; `LaunchedEffect { SkinManager.ensureLoaded }`;
- blok glue `skinUi` + `skinActions` sebelum `Box` terluar;
- `if (Active) SkinHost(...) else if (Loading) Spacer else { UI klasik }` membungkus `Row` lama (isi `Row` tidak diubah).

## Mapping function → fungsi APK
| function | aksi |
|---|---|
| `sample.play {pad:N}` | `onPadDown(N)` |
| `music.toggle` | `onMusicToggle()` |
| `preset.prev/next` | `selectPreset(current∓1)` (batas 1..presetCount; > `FREE_UNLOCKED_PRESETS` → `openProUpgradeLink`) |
| `preset.menu` | `toggleSidePanels()` (panel preset ASLI APK) |
| `file.load` / `file.export` | `showLoadSourceChooser` / `showExportConfirmDialog` |
| `padSettings.open` | refresh nilai per pad + `showPadVolumeSettings = true` |
| `volume.kendang` | `onKendangVolumeChange(knob*1.5)` — rentang APK 0..1.5, knob 0..1 |
| `display.preset` | teks LCD dari template (`KIT` / `P001`) |

## Yang BELUM ada di mode skin (lihat CHECKPOINT → Problems)
slider tempo loop per pad, gambar latar kustom, pad tambahan 13–16 (skin-01 memang 12), tab sembunyi/tampil kontrol, sorotan tutorial pada tombol LOAD.
