# Mode 12/16 Pad (Stage 07)

Kode: `skin/PadModes.kt`, `skin/SkinRenderer.kt` (parameter `visiblePads`), `skin/HitTester.kt` (parameter `visiblePads`),
`skin/SkinHost.kt` (`wants16Pads`), `PadVolumeDialog.kt` (`extraPadUnsupportedMessage`, `visiblePadCountOverride`), `MainActivity.kt`.

## Sumber data (tidak ada hardcode jumlah pad)
`data-pad-min` / `data-pad-max` (atau `padSupport` di skin.json) dan elemen `pad-1..pad-N` yang benar-benar ada.
Konstanta 12 dan 16 hanya dua mode yang dikenal APK (mirror `PresetStorage.BASE_PAD` / `MAX_PAD`).

## Aturan
- `can12` = min <= 12 <= min(jumlah pad-N, max); `can16` = min <= 16 <= min(jumlah pad-N, max).
- Skin mendukung KEDUA mode -> pilihan 12/16 tersedia. Pilihan user = switch "Pad Tambahan" APK (`ExtraPadState.isActiveFor(preset)`): aktif -> 16, mati -> 12.
- Selain itu pilihan DINONAKTIFKAN, switch Pad Tambahan di dialog Pengaturan nonaktif, dan tampil pesan: "Pengaturan tambahan pad tidak didukung untuk skin ini." Skin yang menentukan: semua pad yang dideklarasikan (mis. skin-01 = 12).
- Pad yang tidak ditampilkan: tidak digambar (grup `pad-K` dibuang dari layer base), tidak bisa disentuh (HitTester), tidak punya layer active. Data pad 13-16 di disk tetap utuh.
- Ganti mode -> layer base dirender ulang (key `visiblePads`).

## Aturan untuk pembuat skin 12-16
Pad 13-16 harus berupa grup `pad-13..16` (+ `hit-pad-13..16`) terpisah, dan tampilan 12 pad harus terlihat utuh tanpa keempatnya.
Pad opsional ditempatkan di area kosong yang tetap tampak rapi saat pad itu tidak digambar.

## Uji
`tools/sim07_padmodes.py <root>` (Python, rumus sama dengan Kotlin; BUKAN uji Kotlin): 20 PASS, termasuk skin sintetis 16 pad. Hasil: validation/stage07-sim.md.
