# Potong Suara (v1.7.0)

Alur SET SUARA (lokal & online) sekarang: pilih suara -> decode -> **dialog Potong Suara** -> simpan ke pad.

## Komponen
- `SampleTrimDialog.kt`
  - `PendingTrim` : suara hasil decode yang menunggu keputusan potong.
  - `SampleTrimmer.trim()` : potong PCM, fade 2 ms (awal) / 8 ms (akhir) hanya di sisi yang dipotong, encode WAV kanonik, parse ulang via `WavLoader.parse` (level volume konsisten dengan saat app dimuat ulang).
  - `SampleTrimmer.detectSoundRange()` : tombol BUANG HENING (ambang -40 dB dari puncak, ruang 5 ms awal / 30 ms akhir).
  - `TrimPreviewPlayer` : preview AudioTrack (stream) terpisah dari native engine pad.
  - `SampleTrimDialog` : UI (gelombang, 2 garis drag, nudge 10 ms, DENGAR/BUANG HENING/RESET).
- `MainActivity.kt`
  - `pendingTrim` state; `filePicker` & `setSoundFromOnline` mengisinya.
  - `commitPendingSample()` : simpan ke `PresetStorage`, `nativeLoadSample`, update flag bass.

## Perilaku
- Rentang tidak diubah -> disimpan persis seperti sebelumnya (bytes asli).
- Panjang pilihan minimal 10 ms.
- Dialog tidak tertutup oleh tap di luar (cegah kehilangan hasil atur).
- Online: BATAL di dialog potong kembali ke daftar suara online.
