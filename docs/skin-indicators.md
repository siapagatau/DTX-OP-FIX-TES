# Lampu indikator pad (Skin v3.3)

Skin DTX-Multi 12 punya keypad 12 tombol (kotak salmon). Sekarang tiap kotak = lampu untuk satu pad: pad N dipukul -> kotak N menyala, lalu padam bertahap (±240 ms).

## Format
`skin.json` (opsional, level atas):
```json
"indicators": [ { "id": "led-pad-1", "pad": 1, "on": "led-pad-1-on" }, ... ]
```
`skin.svg`:
- Dasar (lampu mati) digambar biasa (mis. di `deco-keypad`), warna redup.
- `<g id="led-pad-N-on" display="none">` = gambar lampu menyala. **Persegi (`<rect>`) pertama di dalamnya = area lampu** (koordinat SVG; sertakan halo/glow di dalamnya). Boleh di mana saja, disarankan di `<g id="layer-indicators">` sebelum `layer-hit`.
- Tanpa `hit-*`: lampu bukan tombol, tidak bisa disentuh.

## Alur di APK
- `SkinParser` membaca `indicators` -> `SkinIndicator(id, pad, onId, rect)`; entri yang elemennya/rect-nya tidak ada dilewati.
- `SkinRenderer.buildDetails` merender tiap `on` jadi bitmap kecil (kunci layer `ind:<id>`), disimpan di cache disk bersama state pad/tombol.
- `SkinHost` menjalankan loop frame: membaca `padFlash(pad)` (= `flashIntensity` PadScreen, diisi `onPadDown` untuk sentuh/MIDI/keyboard). Nilai naik = pukulan baru -> lampu terang (ikut kekuatan pukulan), lalu padam linear `LAMP_FADE_MS`. Canvas menggambar bitmap `on` dengan alpha = kecerahan.
- Bitmap belum siap -> kotak terang bawaan. Lampu hanya tampil bila areanya terlihat (panel skin tidak disembunyikan) dan pad ≤ jumlah pad tampil.
- `SkinValidator`: masalah indikator hanya peringatan (skin tetap dipakai).

Skin lama tanpa `indicators` tidak berubah perilakunya; APK lama mengabaikan kunci `indicators`.
