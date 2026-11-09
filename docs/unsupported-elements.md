# Elemen Tidak Didukung / Tanpa Fungsi

## 1. Dekorasi pada skin-01 v1.2 (digambar, tidak bisa disentuh)

| ID | Label | Alasan |
|---|---|---|
| `deco-updown` | ▲▼ | Tombol hardware Yamaha tanpa padanan fungsi di APK |
| `deco-keypad` | Keypad 12 tombol | Tombol hardware Yamaha tanpa padanan fungsi di APK |
| `preset.select` | P1..P50 langsung | Diganti DEC/INC + preset.menu |

Tombol hardware Yamaha lain di skin-01 sudah dijadikan kontrol sungguhan sejak v1.2 (lihat `mapping-table.md`).

## 2. Fungsi APK yang tidak punya kontrol di skin-01

Tidak ada. Sejak Skin v2 tidak ada lagi menu native melayang (tombol titik tiga dihapus); semua fungsi APK punya tombol di dalam skin.

## 3. Fitur UI klasik yang belum ada di mode skin (dicatat jujur)

- slider tempo loop per pad
- gambar latar kustom (`BackgroundState`)
- tab sembunyi/tampil kontrol
- sorotan tutorial pada tombol LOAD
- slider lurus sebagai kontrol skin (engine hanya mendukung rotary `volume.kendang`)

Bila skin gagal/ditolak, UI klasik (yang punya semua fitur di atas) dipakai sebagai fallback terakhir.
