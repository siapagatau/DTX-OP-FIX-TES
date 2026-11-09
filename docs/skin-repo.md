# Skin Repository di APK (Stage 08)

Kode: `skin/SkinRepository.kt` + tambahan di `skin/SkinManager.kt`. Config: `assets/config/skin-repo.json` (`indexUrl`, harus https; placeholder `RAW_GITHUB_INDEX_URL` = repo dinonaktifkan, APK pakai cache/default).

Alur: `refreshIndex` (online → simpan index.json ke `filesDir/skin-cache/`; gagal → pakai index cache) → `download(entry)` (skin.json + skin.svg, cek ukuran + sha256, parse + SkinValidator di folder sementara, baru dipasang atomik ke `skin-cache/skins/<id>/`) → `SkinManager.useSkin(id)` (id tersimpan di SharedPreferences `skin_prefs`).

Saat start: skin terpilih dimuat dari cache (offline OK); tidak ada / rusak → skin default di assets.
Keamanan: hanya https, batas 4 MB, id & path divalidasi (tanpa `..`), skin ditolak validator tidak masuk cache.
UI pemilih skin: lihat `skin-picker.md` (Stage 14).
