# Pemilih Skin (Stage 14)

Cara buka: tombol **SKIN** di strip tombol utama (UI klasik, atau mode skin setelah ditampilkan lewat tab kanan), atau tombol EXIT di dalam skin.
Default = **APK Asli (Klasik)**; baris itu selalu ada, jadi user bisa kembali kapan saja.

Kode: `skin/SkinPickerDialog.kt`, dibuka lewat tombol EXIT di skin (function `skin.pick`), state `showSkinPicker` di PadScreen (MainActivity).
Logika data: `SkinManager.refreshCatalog / downloadAndUse / useSkin`, `SkinRepository.lastRefreshOnline / cachedVersion`.

## Isi dialog
- **APK Asli (Klasik)** (default, selalu ada) + skin bawaan APK (offline; disembunyikan bila repository punya skin bernama sama) + semua skin dari index.json repository.
- Tiap baris: nama, versi, author, jumlah pad, dan satu tombol:
  | Kondisi | Tombol |
  |---|---|
  | belum diunduh | UNDUH (langsung dipakai setelah selesai) |
  | sudah diunduh, belum aktif | PAKAI |
  | versi di index beda dengan yang terunduh | UPDATE |
  | sedang dipakai | teks DIPAKAI |
- Tombol SEGARKAN mengambil ulang index. Offline: tampil daftar tersimpan + pesan "Offline".
- indexUrl belum diisi: "Repository skin belum dikonfigurasi", hanya skin bawaan.

## Keamanan / fallback
Unduhan diverifikasi sha256 + ukuran + SkinValidator sebelum masuk cache. Gagal → pesan error, skin yang sedang dipakai tidak berubah, cache lama utuh.
Skin terpilih tersimpan (SharedPreferences) dan dipakai lagi saat start; gagal render → otomatis kembali ke skin bawaan.

## Uji
`tools/sim14_picker.py` → `validation/stage14-sim.md` (12 PASS: unduh via HTTP lokal, sha256, offline, update, file rusak/404). Simulasi Python; Kotlin dan tampilan dialog belum dibuild/diuji di HP.
