# Tabel Mapping Skin → Fungsi APK (default skin = skin-01)

Dibuat dari `assets/skins/default/skin.json`. Katalog function resmi: `skin/FunctionRegistry.kt`.

| ID | Tipe | Function | Params | Status APK | Rujukan di APK |
|---|---|---|---|---|---|
| `pad-1` … `pad-12` | pad | `sample.play` | `{"pad": N}` | existing | MainActivity.onPadDown(id) |
| `btn-play` | button | `music.toggle` | - | existing | MainActivity.onMusicToggle() (jika belum ada lagu: membuka pustaka musik) |
| `btn-preset-prev` | button | `preset.prev` | - | wrap-existing | currentPreset - 1 (batas 1; preset terkunci FREE -> openProUpgradeLink) |
| `btn-preset-next` | button | `preset.next` | - | wrap-existing | currentPreset + 1 (batas presetCount; preset terkunci FREE -> openProUpgradeLink) |
| `btn-preset-menu` | button | `preset.menu` | - | new | tampil/sembunyi panel preset ASLI APK (v3: tanpa dialog baru) |
| `btn-load` | button | `file.load` | - | existing | showLoadSourceChooser = true |
| `btn-export` | button | `file.export` | - | existing | showExportConfirmDialog = true |
| `btn-settings` | button | `padSettings.open` | - | existing | onOpenSettings -> showPadVolumeSettings = true |
| `btn-music` (MIDI) | button | `music.panel` | - | existing | tampil/sembunyi panel volume & musik ASLI APK (v3: tanpa dialog baru) |
| `btn-voice` (VOICE) | button | `sound.set` | - | existing | showSetSoundSourceChooser; pad = pad terakhir yang diketuk |
| `btn-screenrec` (TAP) | button | `screenrecord.toggle` | - | existing | onScreenRecordToggle() |
| `btn-skin` (EXIT) | button | `skin.pick` | - | existing | SkinPickerDialog |
| `btn-rec` (ENTER) | button | `record.toggle` | - | existing | onRecordToggle() |
| `btn-theme` (SHIFT) | button | `theme.open` | - | existing | showColorPicker (FREE -> openProUpgradeLink) |
| `btn-seek-back` (◄) | button | `music.seekBack` | - | existing | onSeekMusic(posisi - 5 detik) |
| `btn-seek-fwd` (►) | button | `music.seekForward` | - | existing | onSeekMusic(posisi + 5 detik) |
| `vol-knob` | rotary | `volume.kendang` | - | existing | MainActivity.onKendangVolumeChange(v: Float) |
| `slot-display` | display | `display.preset` | template=["KIT", "P{preset:000}"] | wrap-existing | currentPreset (APK belum punya nama preset: baris 1 = 'KIT', baris 2 = 'Pnnn') |

## Function resmi (semua skin)

| function | Tipe | Aksi di APK |
|---|---|---|
| `sample.play` | pad | `onPadDown(N)` |
| `music.toggle` | button | `onMusicToggle()` |
| `preset.prev` / `preset.next` | button | `selectPreset(current∓1)` (preset terkunci FREE → `openProUpgradeLink`) |
| `preset.menu` | button | `toggleSidePanels()` (panel preset asli APK) |
| `file.load` / `file.export` | button | `showLoadSourceChooser` / `showExportConfirmDialog` |
| `padSettings.open` | button | refresh nilai per pad + `showPadVolumeSettings = true` |
| `volume.kendang` | rotary | `onKendangVolumeChange(knob*1.5)` |
| `display.preset` | display | teks LCD dari template |

Catatan (v3): `PresetPickerDialog` dan `MusicPanelDialog` DIHAPUS; KIT/MIDI memakai panel asli APK.
