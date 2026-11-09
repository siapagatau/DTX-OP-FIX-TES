# Arsitektur Skin Controller Engine

```
Gambar referensi → skin.svg + skin.json → (repo GitHub atau assets/default)
   → SkinRepository (unduh + cache) → SkinLoader → SkinParser → SkinValidator
   → SkinManager.state → SkinHost (render layer + sentuhan) → HitTester
   → SkinActionDispatcher → SkinActions → fungsi APK (audio engine, preset, file, volume)
```
**SVG** = tampilan + layout + ID + hit area + state. **skin.json** = daftar kontrol + mapping. **APK** = fungsi + audio + sentuhan. SVG tidak berisi logika.

## Komponen (`app/src/main/java/com/kendang/realpads/skin/`)
| Komponen | File | Tugas |
|---|---|---|
| SkinParser | `SkinParser.kt`, `SvgDom.kt`, `SkinModel.kt` | baca skin.svg + skin.json → `SkinDefinition` (posisi dari hit-*, jumlah pad dari pad-N) |
| SkinLoader | `SkinLoader.kt` | muat dari assets (default) atau folder (cache); batas 4 MB |
| SkinValidator | `SkinValidator.kt`, `FunctionRegistry.kt` | tolak skin tidak aman/tidak lengkap (lihat `validation.md`) |
| SkinRenderer | `SkinRenderer.kt` | pecah SVG jadi layer base/active/rotary, render AndroidSVG → Bitmap |
| HitTester | `HitTester.kt` | `ViewportTransform` (viewBox → layar), area aman, touch layer (lihat `scaling.md`) |
| SkinManager | `SkinManager.kt` | state Loading/Active/Failed, skin terpilih, rantai fallback |
| SkinRepository | `SkinRepository.kt` | index.json → unduh → cek sha256 → cache offline (lihat `skin-repo.md`) |
| UI/aksi | `SkinPickerDialog.kt`, `SkinHost.kt`, `SkinActions.kt`, `SkinActionDispatcher.kt`, `PadModes.kt`, `PresetPickerDialog.kt`, `MusicPanelDialog.kt` | composable, multi-touch, 12/16 pad (`pad-support.md`), dialog |

## Sumber skin & fallback
1. Skin terpilih dari cache unduhan (`filesDir/skin-cache/skins/<id>`), bisa dipakai offline.
2. Skin default `assets/skins/default/{skin.svg,skin.json}` (salinan skin-01; hanya fallback offline).
3. UI klasik APK (PadGrid + panel lama) bila skin default pun gagal. APK tidak crash.

## Konfigurasi
`config/skin-repo.json` → `{"indexUrl": "<URL raw index.json, https>"}`; salinan yang dibaca APK: `app/src/main/assets/config/skin-repo.json`. Nilai `RAW_GITHUB_INDEX_URL` = repo dinonaktifkan (cache/default saja). Repo skin dipisah dari APK (`skins-repo.zip`).

## Dokumen lain
`skin-engine.md` (rincian Stage 05), `skin-picker.md`, `scaling.md`, `pad-support.md`, `skin-repo.md`, `skin-picker.md`, `validation.md`, `mapping-table.md`, `unsupported-elements.md`.

## Status
Kode Kotlin Stage 06–09 ditulis tetapi BELUM dikompilasi di lingkungan pengembangan (tanpa Android SDK). Stage 05 dilaporkan berhasil dibuild user. Uji logika dilakukan lewat simulasi Python (`validation/stage0*-sim.md`).
