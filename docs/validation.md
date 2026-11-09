# Validasi & Fallback Skin (Stage 09)

## Yang divalidasi (`SkinValidator.kt`, tidak pernah melempar exception)
| Area | Aturan (ERROR = skin ditolak) |
|---|---|
| SVG | XML valid; akar `<svg>`; tanpa script/foreignObject/image/use/audio/video/iframe; tanpa atribut `on*`, `href`, URL http(s); maks 20.000 elemen |
| Metadata | `data-skin-name/version/author`, `data-pad-min/max`, `viewBox` wajib; nama & versi tidak kosong |
| viewBox | lebar/tinggi > 0 dan <= 100.000 |
| Pad | minimal 1 pad; nomor berurutan 1..N; N dalam padMin..padMax; N <= `PresetStorage.MAX_PAD`; `params.pad` = nomor pad |
| ID | tidak ada ID ganda; setiap kontrol ada di SVG |
| Hit area | tiap kontrol non-display punya hit di dalam viewBox, ukuran > 0 |
| Mapping | function wajib ada, ada di katalog resmi, cocok dengan tipe kontrol |
| Rotary | `data-cx/data-cy` wajib |
| WARNING (skin tetap dipakai) | state active tidak ada; display tanpa template; hit area saling tumpuk |

Ukuran file dibatasi 4 MB di `SkinLoader`/`SkinRepository`.

## Rantai fallback (APK tidak crash)
1. Start: skin terpilih dari cache. Hilang/rusak/ditolak -> skin default (assets).
2. Unduhan: sha256/ukuran salah atau validator menolak -> tidak masuk cache, skin lama tetap.
3. Render gagal (`reportRenderFailure`): pilihan di-reset ke default, skin default dimuat.
4. Default pun gagal -> `State.Failed` -> UI klasik APK.

## Uji
`tools/sim09_validate.py` -> `validation/stage09-sim.md` (18 PASS: 1 skin valid + 17 variasi rusak). Ini simulasi Python yang mencerminkan aturan Kotlin; Kotlin belum dikompilasi/dijalankan.
