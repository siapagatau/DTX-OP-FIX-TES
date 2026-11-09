package com.kendang.realpads.skin

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Memuat skin dari assets (default/fallback offline) atau dari folder (skin terunduh / cache).
 * Tidak melakukan validasi; panggil SkinValidator setelahnya (lihat SkinManager).
 */
object SkinLoader {
    private const val TAG = "SkinLoader"
    private const val MAX_BYTES = 4 * 1024 * 1024 // batas wajar ukuran skin.svg / skin.json

    fun loadFromAssets(context: Context, dir: String = "skins/default"): SkinDefinition {
        val json = readAsset(context, "$dir/skin.json")
        val svgName = svgNameFrom(json)
        val svg = readAsset(context, "$dir/$svgName")
        return SkinParser.parse(json, svg, "asset:$dir")
    }

    fun loadFromDir(dir: File): SkinDefinition {
        val jsonFile = File(dir, "skin.json")
        if (!jsonFile.isFile) throw SkinException("skin.json tidak ada di ${dir.path}")
        val json = readFile(jsonFile)
        val svgName = svgNameFrom(json)
        // svgName hanya boleh nama file biasa (cegah path traversal dari skin.json)
        if (svgName.contains('/') || svgName.contains('\\') || svgName.contains("..")) {
            throw SkinException("nama file svg tidak valid: $svgName")
        }
        val svgFile = File(dir, svgName)
        if (!svgFile.isFile) throw SkinException("$svgName tidak ada di ${dir.path}")
        return SkinParser.parse(json, readFile(svgFile), "dir:${dir.path}")
    }

    private fun svgNameFrom(json: String): String =
        try {
            org.json.JSONObject(json).optString("svg", "skin.svg").ifBlank { "skin.svg" }
        } catch (e: Exception) {
            throw SkinException("skin.json bukan JSON yang valid: ${e.message}", e)
        }

    private fun readAsset(context: Context, path: String): String =
        try {
            context.assets.open(path).use { readLimited(it) }
        } catch (e: SkinException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "gagal membaca asset $path", e)
            throw SkinException("tidak bisa membaca asset $path: ${e.message}", e)
        }

    private fun readFile(f: File): String =
        try {
            f.inputStream().use { readLimited(it) }
        } catch (e: SkinException) {
            throw e
        } catch (e: Exception) {
            throw SkinException("tidak bisa membaca ${f.path}: ${e.message}", e)
        }

    private fun readLimited(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw SkinException("file skin terlalu besar (> ${MAX_BYTES / 1024} KB)")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
