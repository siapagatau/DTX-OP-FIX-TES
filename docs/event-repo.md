# Event Skin + Misi (Cat Edition) - panduan repo

## Struktur repo GitHub (boleh repo skin yang sama)
```
index.json                      <- index skin lama (tambahkan isi index-entry.json ke array "skins")
skins/cat-edition/skin.json
skins/cat-edition/skin.svg      <- ~200 KB, 12 PNG kucing tertanam sebagai data URI
events/events.json              <- daftar event + misi (schemaVersion: 1)
events/banners/cat-edition-2026.png   <- <= 300 KB, opsional
```
APK: `assets/config/event-repo.json` -> `{"eventsUrl":"https://raw.githubusercontent.com/<user>/<repo>/<branch>/events/events.json"}`
(placeholder `RAW_GITHUB_EVENTS_URL` = belum dikonfigurasi). `assets/config/events.json` = salinan bawaan (cadangan terakhir).

## Menambah event baru (TANPA rilis APK)
1. Buat skin (skrip `tools/build_cat_skin.py` sebagai contoh), taruh di `skins/<skin_id>/`, tambah entri di `index.json` (sha256 + size).
2. Tambah objek di `events/events.json`: event_id, title, description, banner, skin_id, start_at, end_at, quests[].
3. Commit. Klien mengambil ulang saat tab Event dibuka / app mulai (maks 1x per 6 jam).
Tipe quest baru (di luar PAD_HITS, SESSIONS, RECORDINGS, PRESETS_SAVED, ACTIVE_DAYS) butuh rilis APK; tipe tak dikenal dilewati.

## Definisi hitung
PAD_HITS = tiap onPadDown (sentuh/keyboard/MIDI) | SESSIONS = start app, jeda min 5 menit | RECORDINGS = rekaman audio yang berhasil disimpan |
PRESETS_SAVED = suara disimpan ke pad / preset di-load | ACTIVE_DAYS = hari berbeda dengan >=1 pukulan (per event, sejak ikut).
Progres hanya bertambah setelah "Ikut Event" dan selagi event berjalan.

## Multi event + Koleksi (update)
- `events.json` boleh berisi banyak event sekaligus (maks 20). Semua tampil di tab Event, diurutkan: siap klaim > diikuti > belum ikut > segera > berakhir.
- Skin event yang sudah DIKLAIM masuk **Koleksi Skin Event** (permanen). Koleksi dibaca dari progres lokal (`EventRecord.title` + `claimedSkinId`), bukan dari events.json -> tetap ada walau event berakhir / dihapus dari repo.
- Status tiap skin di Koleksi selalu eksplisit: **Sedang dipakai** (kotak outline aksen), **Belum diunduh** (tombol Unduh & Pakai), **Ada update** (Update & Pakai), **Siap dipakai** (Pakai).
- Status event: Belum ikut / Sedang diikuti / Siap diklaim / Segera hadir / Berakhir. Kotak status (Terkunci, Berakhir) tidak lagi berupa tombol disabled yang warnanya menyatu dengan latar.
