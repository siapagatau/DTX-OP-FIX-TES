package com.kendang.realpads.skin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ukuran acuan render = ukuran area skin ([w] x [h], px). Seluruh skin di-stretch ke area ini.
 * Skala acuan: X = w / lebar skin; Y = h / tinggi ZONA PAD -> saat panel disembunyikan (pad memenuhi layar) gambar tetap tajam;
 * saat panel tampil gambar hanya diperkecil sedikit. Gambar TIDAK bergantung pada status panel/tombol bawah APK,
 * jadi tampil/sembunyi tidak memicu render ulang. Render ulang hanya bila ukuran area berubah jauh ([SkinRenderCache.fits]).
 */
class RenderSpec(val w: Int, val h: Int) {
    fun scaleX(skin: SkinDefinition): Float = w / SkinZones.fullZone(skin).w
    fun scaleY(skin: SkinDefinition): Float = h / SkinZones.padZone(skin).h
}

/**
 * Lapisan INTI (cukup untuk menampilkan skin):
 *  - base   : seluruh skin (pad + panel) di-stretch pada skala acuan; tanpa knob, hit-area, dan teks placeholder LCD
 *  - rotary : id knob -> gambar knob UTUH (tidak gepeng) tanpa rotasi awal; SkinHost memutar lalu men-stretch-nya
 */
class SkinCore(
    val base: Bitmap,
    val rotary: Map<String, Bitmap>
)

/**
 * Skin v3.2 - renderer. AndroidSVG hanya MENGGAMBAR; posisi & skala ditentukan di sini lewat matriks Canvas
 * (skala X dan Y boleh berbeda -> seluruh skin melar mengikuti bentuk layar). Dibangun BERTAHAP supaya cepat tampil:
 *   1. [buildCore]    : base + knob  -> skin langsung tampil.
 *   2. [buildDetails] : state tekan tiap pad/tombol, satu per satu di latar belakang. Sebelum siap, SkinHost memakai
 *                       highlight bawaan, jadi tidak ada yang hilang atau menunggu.
 * Hasil lengkap disimpan ke disk ([SkinRenderCache]) -> pembukaan berikutnya tinggal memuat gambar.
 *
 * Catatan: Document.cloneNode() melempar DOMException di Android, jadi tiap layer mem-parse ulang teks SVG
 * yang sudah dibersihkan (cepat; SVG skin hanya puluhan KB).
 */
object SkinRenderer {
    private const val TAG = "SkinRenderer"
    private const val MAX_BITMAP_PIXELS = 6_000_000f   // batas piksel per bitmap (jaga memori)

    fun buildCore(skin: SkinDefinition, spec: RenderSpec, visiblePads: Int): SkinCore {
        val vb = skin.viewBox
        val full = SkinZones.fullZone(skin)
        val sx = spec.scaleX(skin)
        val sy = spec.scaleY(skin)
        val svgText = normalizedSvg(skin)
        val lite = stripImages(svgText)

        val baseSvg = SVG.getFromString(SvgDom.serialize(prepareBase(svgText, skin, visiblePads)))
        val base = renderRegion(baseSvg, vb, full, full.w * sx, full.h * sy)

        val rotary = HashMap<String, Bitmap>()
        try {
            // knob digambar UTUH (skala seragam, resolusi cukup untuk skala X maupun Y); SkinHost yang memutar lalu men-stretch-nya,
            // sehingga putarannya tetap benar walau skin melar.
            val m = max(sx, sy)
            for (c in skin.controls) {
                if (c.type != ControlType.ROTARY) continue
                val hit = c.hit ?: continue
                try {
                    rotary[c.id] = renderOnly(lite, c.id, vb, hit, hit.w * m, hit.h * m, clearTransform = true)
                } catch (e: Exception) {
                    Log.w(TAG, "knob ${c.id} gagal dirender: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            base.recycle(); rotary.values.forEach { it.recycle() }
            throw e
        }
        return SkinCore(base, rotary)
    }

    /**
     * State tekan semua pad & tombol (tombol dulu - kecil dan cepat - lalu pad). Tiap hasil langsung dikirim lewat [onLayer].
     * [isAlive] false -> berhenti (render dibatalkan karena ukuran berubah / layar ditutup).
     */
    fun buildDetails(
        skin: SkinDefinition, spec: RenderSpec, visiblePads: Int,
        isAlive: () -> Boolean, onLayer: (String, Bitmap) -> Unit
    ) {
        val vb = skin.viewBox
        val sx = spec.scaleX(skin)
        val sy = spec.scaleY(skin)
        val svgText = stripImages(normalizedSvg(skin))
        val ordered = skin.controls
            .filter { it.hit != null && it.activeId != null && (it.type == ControlType.BUTTON || it.type == ControlType.PAD) }
            .filter { !(it.type == ControlType.PAD && it.index > visiblePads) }
            .sortedBy { if (it.type == ControlType.BUTTON) 0 else 1 }
        // Urutan: tombol -> lampu indikator pad (kecil & cepat, supaya siap lebih dulu) -> pad.
        val jobs = ArrayList<Triple<String, String, SvgRect>>()   // (kunci layer, id elemen yang digambar, area)
        for (c in ordered) if (c.type == ControlType.BUTTON) jobs.add(Triple(c.id, c.activeId!!, c.hit!!))
        for (ind in skin.indicators) if (ind.pad <= visiblePads) jobs.add(Triple(ind.layerKey, ind.onId, ind.rect))
        for (c in ordered) if (c.type == ControlType.PAD) jobs.add(Triple(c.id, c.activeId!!, c.hit!!))
        for ((key, elementId, area) in jobs) {
            if (!isAlive()) return
            try {
                onLayer(key, renderOnly(svgText, elementId, vb, area, area.w * sx, area.h * sy, clearTransform = false))
            } catch (e: Exception) {
                // satu layer gagal tidak boleh menjatuhkan seluruh skin: kontrol tetap jalan dengan highlight bawaan
                Log.w(TAG, "layer $key gagal dirender: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Layer state tekan / knob / indikator tidak butuh gambar raster (base sudah memuatnya). Membuang <image> dari TEKS
     * sebelum di-parse menghindari parse berulang ~20x terhadap ratusan KB base64.
     */
    private val IMAGE_TAG = Regex("<image\\b[^>]*?(?:/>|>.*?</image>)", RegexOption.DOT_MATCHES_ALL)
    private fun stripImages(svgText: String): String = if (svgText.contains("<image")) IMAGE_TAG.replace(svgText, "") else svgText

    private fun normalizedSvg(skin: SkinDefinition): String {
        val doc = SvgDom.parse(skin.svgText)
        val root = doc.documentElement
        SvgDom.elements(root).filter { it.tagName == "metadata" }.toList().forEach { SvgDom.remove(it) }
        // Root tanpa width/height -> viewport = yang kita berikan; viewBox = viewBox skin.
        val vb = skin.viewBox
        root.removeAttribute("width")
        root.removeAttribute("height")
        root.removeAttribute("preserveAspectRatio")
        root.setAttribute("viewBox", "${vb.x} ${vb.y} ${vb.w} ${vb.h}")
        return SvgDom.serialize(doc)
    }

    /**
     * Dokumen dasar: tanpa pad di luar mode 12/16, tanpa knob, tanpa hit-*, tanpa teks placeholder display.
     */
    private fun prepareBase(srcText: String, skin: SkinDefinition, visiblePads: Int): Document {
        val doc = SvgDom.parse(srcText)
        val root = doc.documentElement
        for (c in skin.controls) {
            if (c.type == ControlType.PAD && c.index > visiblePads) SvgDom.findById(root, c.id)?.let { SvgDom.remove(it) }
        }
        for (c in skin.controls) {
            if (c.type == ControlType.ROTARY) SvgDom.findById(root, c.id)?.let { SvgDom.remove(it) }
        }
        SvgDom.elements(root).filter { it.getAttribute("id").startsWith("hit-") }.toList().forEach { SvgDom.remove(it) }
        for (c in skin.controls) {
            if (c.type == ControlType.DISPLAY) {
                SvgDom.findById(root, c.id)?.let { g ->
                    SvgDom.elements(g).filter { it.tagName == "text" }.toList().forEach { SvgDom.remove(it) }
                }
            }
        }
        return doc
    }

    private fun renderOnly(
        srcText: String, targetId: String, vb: SvgRect, region: SvgRect, pxW: Float, pxH: Float, clearTransform: Boolean
    ): Bitmap {
        val doc = SvgDom.parse(srcText)
        val target = SvgDom.findById(doc.documentElement, targetId)
            ?: throw SkinException("elemen $targetId tidak ada")
        target.removeAttribute("display")
        if (clearTransform) target.removeAttribute("transform")
        pruneTo(doc, target)
        return renderRegion(SVG.getFromString(SvgDom.serialize(doc)), vb, region, pxW, pxH)
    }

    /** Hapus semua yang bukan <defs> dan bukan leluhur/target (leluhur dipertahankan agar transform-nya ikut). */
    private fun pruneTo(doc: Document, target: Element) {
        var node: Node = target
        val root = doc.documentElement
        while (node !== root) {
            val parent = node.parentNode ?: break
            val kids = parent.childNodes
            val remove = ArrayList<Node>()
            for (i in 0 until kids.length) {
                val k = kids.item(i)
                if (k === node) continue
                if (k is Element && k.tagName == "defs") continue
                if (k is Element) remove.add(k)
            }
            remove.forEach { parent.removeChild(it) }
            node = parent
        }
    }

    /**
     * Gambar wilayah [region] dari [svg] ke bitmap berukuran ~[pxW] x [pxH]. Skala X dan Y dihitung terpisah
     * (zona pad boleh melar). Skala & geser lewat matriks Canvas; library selalu melihat viewport = ukuran viewBox.
     */
    private fun renderRegion(svg: SVG, vb: SvgRect, region: SvgRect, pxW: Float, pxH: Float): Bitmap {
        var w = max(1f, pxW)
        var h = max(1f, pxH)
        val f = min(1f, sqrt(MAX_BITMAP_PIXELS / (w * h)))
        w *= f; h *= f
        val bw = max(1, ceil(w).toInt())
        val bh = max(1, ceil(h).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(bw / region.w, bh / region.h)
        canvas.translate(-(region.x - vb.x), -(region.y - vb.y))
        val opts = RenderOptions.create().viewPort(0f, 0f, vb.w, vb.h)
        svg.renderToCanvas(canvas, opts)
        return bmp
    }
}
