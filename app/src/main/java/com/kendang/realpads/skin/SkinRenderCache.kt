package com.kendang.realpads.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs

/** Hasil render lengkap (inti + state tekan) yang dimuat dari cache disk. */
class CachedLayers(val core: SkinCore, val active: Map<String, Bitmap>)

/**
 * Cache hasil render skin di `cacheDir/skin-render/<hash skin>/<WxH>/` (PNG + meta.json; meta ditulis TERAKHIR
 * sebagai penanda lengkap -> cache setengah jadi tidak pernah dipakai).
 *
 * Kenapa: merender SVG skin (gradien + pola mesh di tiap pad, ~20 layer) di HP memakan waktu. Setelah sekali dirender,
 * pembukaan berikutnya cukup memuat PNG (puluhan ms).
 *
 * Kunci = isi skin (svg + deklarasi layout + versi renderer) + jumlah pad tampil. Ukuran area yang MIRIP dipakai ulang
 * ([fits]), jadi tampil/sembunyi tombol bawah atau perubahan kecil tinggi layar tidak memicu render ulang.
 * Cache tinggal dibersihkan sistem bila ruang penuh; kehilangan cache hanya berarti render ulang sekali.
 */
object SkinRenderCache {
    private const val TAG = "SkinRenderCache"
    private const val ROOT = "skin-render"
    /** Naikkan bila cara render berubah supaya cache lama tidak dipakai. */
    private const val RENDER_VERSION = 33
    private const val KEEP_SIZES = 3
    private const val KEEP_SKINS = 6

    /** Gambar yang dirender untuk area [refW] x [refH] masih layak dipakai untuk area [w] x [h] (skala GPU, tanpa render ulang). */
    fun fits(refW: Int, refH: Int, w: Float, h: Float): Boolean =
        w <= refW * 1.2f && h <= refH * 1.2f && w >= refW * 0.6f && h >= refH * 0.6f

    private fun hashOf(skin: SkinDefinition, visiblePads: Int): String {
        val md = MessageDigest.getInstance("SHA-1")
        fun add(s: String) { md.update(s.toByteArray(Charsets.UTF_8)); md.update(0) }
        add(RENDER_VERSION.toString()); add(skin.version); add(skin.svgText)
        add(skin.padZoneDecl.toString()); add(skin.panelZoneDecl.toString())
        add(skin.indicators.toString())
        add(visiblePads.toString())
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun skinDir(context: Context, skin: SkinDefinition, visiblePads: Int) =
        File(File(context.cacheDir, ROOT), hashOf(skin, visiblePads))

    /** Cari entri cache yang cocok dengan [spec]; null bila tidak ada / rusak. */
    fun load(context: Context, skin: SkinDefinition, visiblePads: Int, spec: RenderSpec): CachedLayers? {
        val base = skinDir(context, skin, visiblePads)
        val dirs = base.listFiles { f -> f.isDirectory } ?: return null
        var best: File? = null
        var bestDiff = Long.MAX_VALUE
        for (d in dirs) {
            val meta = readMeta(d) ?: continue
            val w = meta.optInt("w"); val h = meta.optInt("h")
            if (!fits(w, h, spec.w.toFloat(), spec.h.toFloat())) continue
            val diff = abs(w.toLong() * h - spec.w.toLong() * spec.h)
            if (diff < bestDiff) { best = d; bestDiff = diff }
        }
        val dir = best ?: return null
        return try {
            val meta = readMeta(dir) ?: return null
            fun bmp(name: String): Bitmap = BitmapFactory.decodeFile(File(dir, name).path)
                ?: throw IllegalStateException("$name tidak bisa dibaca")
            val base = bmp("base.png")
            val rotary = HashMap<String, Bitmap>()
            val rj = meta.optJSONObject("rotary")
            rj?.keys()?.forEach { id -> rotary[id] = bmp(rj.getString(id)) }
            val active = HashMap<String, Bitmap>()
            val aj = meta.optJSONObject("active")
            aj?.keys()?.forEach { id -> active[id] = bmp(aj.getString(id)) }
            dir.setLastModified(System.currentTimeMillis())
            CachedLayers(SkinCore(base, rotary), active)
        } catch (e: Throwable) {
            Log.w(TAG, "cache rusak, dihapus: ${e.message}")
            dir.deleteRecursively()
            null
        }
    }

    fun save(context: Context, skin: SkinDefinition, visiblePads: Int, spec: RenderSpec, core: SkinCore, active: Map<String, Bitmap>) {
        val base = skinDir(context, skin, visiblePads)
        val dir = File(base, "${spec.w}x${spec.h}")
        try {
            dir.deleteRecursively()
            if (!dir.mkdirs()) return
            fun write(name: String, b: Bitmap) {
                FileOutputStream(File(dir, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val meta = JSONObject()
            meta.put("w", spec.w); meta.put("h", spec.h)
            write("base.png", core.base)
            val rj = JSONObject()
            var n = 0
            for ((id, b) in core.rotary) { val f = "r_${n++}.png"; write(f, b); rj.put(id, f) }
            meta.put("rotary", rj)
            val aj = JSONObject()
            for ((id, b) in active) { val f = "a_${n++}.png"; write(f, b); aj.put(id, f) }
            meta.put("active", aj)
            File(dir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)   // penanda lengkap: TERAKHIR
            prune(base)
        } catch (e: Throwable) {
            Log.w(TAG, "gagal menyimpan cache: ${e.message}")
            dir.deleteRecursively()
        }
    }

    private fun readMeta(dir: File): JSONObject? {
        val f = File(dir, "meta.json")
        if (!f.isFile) return null
        return try { JSONObject(f.readText(Charsets.UTF_8)) } catch (_: Exception) { null }
    }

    private fun prune(base: File) {
        base.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.drop(KEEP_SIZES)?.forEach { it.deleteRecursively() }
        base.parentFile?.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.drop(KEEP_SKINS)?.forEach { it.deleteRecursively() }
    }
}
