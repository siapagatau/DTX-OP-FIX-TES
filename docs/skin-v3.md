# Skin v3 - apa yang berubah dan kenapa

> Pemisahan zona pad/panel di dokumen ini digantikan oleh skin-v3.2.md (seluruh skin di-stretch).

Laporan: dialog volume musik & pilih preset buatan sendiri (jelek, bukan asli APK); pad kecil dan banyak area kosong
(skin di-letterbox 1:1 di layar lebar); saat pertama masuk APK langsung loading ke skin DTX M12, dan skin tidak bisa diganti
karena "skin bawaan" = DTX M12.

| Masalah | Perbaikan |
|---|---|
| Dialog preset/musik baru | `PresetPickerDialog` & `MusicPanelDialog` DIHAPUS. Panel preset (kiri) dan panel volume/musik (kanan) ASLI APK tetap tampil di samping skin. Tombol KIT/MIDI di skin = tampil/sembunyi panel itu (`toggleSidePanels`). |
| Pad kecil, area kosong | Skin dibagi 2 zona (`layout` di skin.json): **padZone** dimelarkan memenuhi area (skala X/Y beda), **panelZone** (LCD+tombol+knob) skala seragam di bawah, maks 26% tinggi. Sentuhan dihitung di ruang layar (`SkinLayout.rectOf`) sehingga selalu pas dengan gambar. |
| Strip tombol utama bawah APK | Di mode skin disembunyikan (default), bisa ditampilkan lewat tab kanan. |
| Panel DTX makan tempat | Bisa disembunyikan lewat tab kanan -> pad memenuhi seluruh area. |
| Langsung loading ke DTX M12 | Default = **UI klasik asli APK** (`SkinManager.CLASSIC_ID`), tanpa loading. Skin hanya dipakai bila dipilih. |
| Tidak bisa ganti skin / "sudah di skin default" | Pemilih skin: baris **APK Asli (Klasik)** + skin bawaan APK (offline) + katalog repository. Klasik selalu bisa dipilih lagi. Render gagal -> pilihan kembali ke klasik. |

## Tab kanan (mode skin) - 3 tombol bertumpuk
1. atas: tampil/sembunyi panel asli APK (preset kiri, volume+musik kanan)
2. tengah: tampil/sembunyi panel kontrol skin (LCD + tombol DTX)
3. bawah: tampil/sembunyi strip tombol utama APK (REC, LAYAR, SET SUARA, TEMA, SKIN, EXPORT, LOAD)
Pilihan 2 dan 3 disimpan antar sesi (`SkinManager.uiFlag`).

## Membuka pemilih skin
Tombol EXIT di skin, atau tombol SKIN di strip bawah (tampilkan lewat tombol ke-3 di tab), atau di UI klasik tombol SKIN.

## Status verifikasi
Kotlin BELUM dikompilasi (sandbox tanpa Android SDK/jaringan). Rumus layout disimulasikan di Python (lihat laporan).
Perilaku AndroidSVG di perangkat hanya bisa dipastikan lewat build + screenshot.
