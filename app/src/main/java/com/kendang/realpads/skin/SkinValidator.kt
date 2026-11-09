package com.kendang.realpads.skin

import com.kendang.realpads.PresetStorage

/** Hasil validasi: errors = skin DITOLAK, warnings = skin tetap dipakai. */
class SkinValidationResult(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    fun summary(): String = if (ok) "OK (${warnings.size} peringatan)" else errors.joinToString("; ")
}

/**
 * Validasi skin sebelum dipakai (Stage 05 = pemeriksaan inti; Stage 09 = batas ukuran/metadata/viewBox + fallback di SkinManager).
 * Tidak pernah melempar exception: semua masalah dilaporkan sebagai teks.
 */
object SkinValidator {

    private const val MAX_VIEWBOX = 100_000f
    private const val MAX_ELEMENTS = 20_000

    // <image> kini DIIZINKAN tetapi sangat dibatasi (lihat checkImage): hanya data URI base64 image/png | image/jpeg.
    private val forbiddenTags = setOf("script", "foreignObject", "use", "audio", "video", "iframe")

    private const val MAX_IMAGES = 16                    // jumlah <image> per skin
    private const val MAX_IMAGE_BYTES = 450 * 1024       // ukuran biner (setelah base64-decode) per gambar
    private const val MAX_IMAGES_TOTAL = 2 * 1024 * 1024 // total biner semua gambar
    private val DATA_URI = Regex("^data:image/(png|jpeg);base64,([A-Za-z0-9+/]+={0,2})$")

    /** Periksa satu href <image>. Mengembalikan ukuran biner (0 bila tidak valid; error sudah dicatat). */
    private fun checkImageHref(v: String, errors: MutableList<String>): Long {
        val m = DATA_URI.matchEntire(v.trim())
        if (m == null) { errors.add("<image> hanya boleh data URI base64 image/png atau image/jpeg"); return 0 }
        val b64 = m.groupValues[2]
        val bytes = b64.length / 4L * 3 - (if (b64.endsWith("==")) 2 else if (b64.endsWith("=")) 1 else 0)
        if (bytes > MAX_IMAGE_BYTES) { errors.add("gambar terlalu besar (${bytes / 1024} KB > ${MAX_IMAGE_BYTES / 1024} KB)"); return 0 }
        // Magic bytes: PNG = 89 50 4E 47, JPEG = FF D8 FF (cegah file lain berkedok gambar).
        val head = try { android.util.Base64.decode(b64.take(16), android.util.Base64.DEFAULT) } catch (_: IllegalArgumentException) { ByteArray(0) }
        val png = head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        val jpg = head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        if (m.groupValues[1] == "png" && !png || m.groupValues[1] == "jpeg" && !jpg) { errors.add("isi gambar tidak sesuai tipe data URI"); return 0 }
        return bytes
    }

    fun validate(skin: SkinDefinition): SkinValidationResult {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        try {
            val doc = SvgDom.parse(skin.svgText)
            val root = doc.documentElement
            if (root.tagName != "svg") errors.add("elemen akar bukan <svg> (${root.tagName})")
            if (skin.name.isBlank()) errors.add("nama skin kosong")
            if (skin.version.isBlank()) errors.add("versi skin kosong")
            if (skin.viewBox.w <= 0f || skin.viewBox.h <= 0f || skin.viewBox.w > MAX_VIEWBOX || skin.viewBox.h > MAX_VIEWBOX) {
                errors.add("viewBox tidak wajar (${skin.viewBox.w} x ${skin.viewBox.h})")
            }
            if (SvgDom.elements(root).take(MAX_ELEMENTS + 1).count() > MAX_ELEMENTS) {
                errors.add("elemen SVG terlalu banyak (> $MAX_ELEMENTS)")
            }
            for (a in listOf("data-skin-name", "data-skin-version", "data-skin-author", "data-pad-min", "data-pad-max", "viewBox")) {
                if (!root.hasAttribute(a)) errors.add("root SVG tanpa atribut $a")
            }
            var imageCount = 0
            var imageBytes = 0L
            for (e in SvgDom.elements(root)) {
                if (e.tagName in forbiddenTags) errors.add("elemen terlarang <${e.tagName}>")
                val isImage = e.tagName == "image"
                if (isImage) {
                    imageCount++
                    if (imageCount > MAX_IMAGES) { if (imageCount == MAX_IMAGES + 1) errors.add("terlalu banyak <image> (maks $MAX_IMAGES)"); }
                }
                val attrs = e.attributes
                for (i in 0 until attrs.length) {
                    val n = attrs.item(i).nodeName
                    val v = attrs.item(i).nodeValue ?: ""
                    if (n.startsWith("on")) errors.add("atribut event '$n' pada <${e.tagName}>")
                    if (n == "href" || n == "xlink:href") {
                        // href hanya sah pada <image> dengan data URI png/jpeg yang ukurannya wajar; selain itu tetap terlarang.
                        if (isImage) {
                            val size = checkImageHref(v, errors)
                            imageBytes += size
                            if (imageBytes > MAX_IMAGES_TOTAL) { errors.add("total gambar > ${MAX_IMAGES_TOTAL / 1024} KB"); imageBytes = Long.MIN_VALUE / 2 }
                        } else errors.add("referensi eksternal ($n) pada <${e.tagName}>")
                    }
                    if (v.contains("http://", true) || v.contains("https://", true)) {
                        if (n != "xmlns" && !n.startsWith("xmlns:")) errors.add("URL eksternal di atribut $n")
                    }
                }
            }

            // controller wajib & jumlah pad
            val pads = skin.pads
            if (pads.isEmpty()) errors.add("tidak ada pad (minimal 1 pad-N)")
            val nums = pads.map { it.index }
            if (nums.isNotEmpty() && nums.sorted() != (1..nums.size).toList()) errors.add("nomor pad tidak berurutan 1..N: $nums")
            if (pads.size > PresetStorage.MAX_PAD) errors.add("jumlah pad ${pads.size} melebihi batas APK (${PresetStorage.MAX_PAD})")
            if (skin.padMin > skin.padMax) errors.add("data-pad-min > data-pad-max")
            if (pads.isNotEmpty() && (pads.size < skin.padMin || pads.size > skin.padMax)) {
                errors.add("jumlah pad ${pads.size} di luar padSupport ${skin.padMin}..${skin.padMax}")
            }

            val seen = HashSet<String>()
            for (c in skin.controls) {
                if (!seen.add(c.id)) errors.add("ID ganda: ${c.id}")
                if (SvgDom.findById(root, c.id) == null) errors.add("${c.id}: elemen tidak ada di SVG")
                if (c.function.isBlank()) {
                    errors.add("${c.id}: mapping function kosong")
                } else if (c.function !in FunctionRegistry.official) {
                    errors.add("${c.id}: function '${c.function}' tidak ada di katalog resmi")
                } else if (c.function !in FunctionRegistry.allowedFor(c.type)) {
                    errors.add("${c.id}: function '${c.function}' tidak cocok untuk tipe ${c.type}")
                }
                if (c.type != ControlType.DISPLAY) {
                    val hit = c.hit
                    if (c.hitId == null || hit == null) {
                        errors.add("${c.id}: hit area tidak ditemukan")
                    } else if (hit.w <= 0f || hit.h <= 0f ||
                        hit.x < skin.viewBox.x || hit.y < skin.viewBox.y ||
                        hit.right > skin.viewBox.right || hit.bottom > skin.viewBox.bottom
                    ) {
                        errors.add("${c.id}: hit area di luar viewBox / ukuran tidak valid")
                    }
                }
                if (c.type == ControlType.PAD) {
                    val aid = c.activeId
                    if (aid == null || SvgDom.findById(root, aid) == null) warnings.add("${c.id}: state active tidak ada (tanpa efek tekan)")
                }
                // Tombol tanpa state active valid: SkinHost memberi highlight bawaan saat ditekan.
                if (c.type == ControlType.BUTTON && c.activeId != null && SvgDom.findById(root, c.activeId) == null) {
                    warnings.add("${c.id}: state active '${c.activeId}' tidak ada di SVG (pakai highlight bawaan)")
                }
                if (c.type == ControlType.ROTARY && c.rotary == null) errors.add("${c.id}: atribut data-cx/data-cy rotary tidak ada")
                if (c.type == ControlType.DISPLAY && c.display == null) warnings.add("${c.id}: display tanpa rect/template")
                if (c.type == ControlType.PAD && c.function == FunctionRegistry.SAMPLE_PLAY) {
                    val p = (c.params["pad"] as? Number)?.toInt()
                    if (p != c.index) errors.add("${c.id}: params.pad ($p) != nomor pad (${c.index})")
                }
            }

            // lampu indikator (opsional): masalah apa pun hanya peringatan - skin tetap dipakai, lampunya saja tidak aktif
            val seenInd = HashSet<String>()
            for (ind in skin.indicators) {
                if (!seenInd.add(ind.id)) warnings.add("indikator ${ind.id}: ID ganda")
                if (ind.pad !in 1..pads.size) warnings.add("indikator ${ind.id}: pad ${ind.pad} tidak ada di skin")
                val r = ind.rect
                if (r.x < skin.viewBox.x || r.y < skin.viewBox.y || r.right > skin.viewBox.right || r.bottom > skin.viewBox.bottom) {
                    warnings.add("indikator ${ind.id}: area lampu di luar viewBox")
                }
            }

            // zona layout: pad harus di zona pad, kontrol lain di zona panel (kalau tidak, kontrol tidak tampil / tidak bisa disentuh)
            val padZone = SkinZones.padZone(skin)
            val panelZone = SkinZones.panelZone(skin)
            fun inside(z: SvgRect, h: SvgRect) =
                h.x >= z.x - 0.5f && h.y >= z.y - 0.5f && h.right <= z.right + 0.5f && h.bottom <= z.bottom + 0.5f
            for (c in skin.controls) {
                val h = c.hit ?: continue
                if (c.type == ControlType.PAD) {
                    if (!inside(padZone, h)) warnings.add("${c.id}: hit area di luar zona pad (layout.padZone)")
                } else if (panelZone == null || !inside(panelZone, h)) {
                    warnings.add("${c.id}: hit area di luar zona panel (layout.panelZone) - kontrol ini tidak akan tampil")
                }
            }

            // hit area saling tumpuk -> peringatan saja (HitTester menentukan pemenang: area terkecil)
            val hits = skin.interactive.filter { it.hit != null }
            for (i in hits.indices) for (j in i + 1 until hits.size) {
                val a = hits[i].hit!!; val b = hits[j].hit!!
                if (a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom) {
                    warnings.add("hit area ${hits[i].id} dan ${hits[j].id} saling tumpuk")
                }
            }
        } catch (e: SkinException) {
            errors.add(e.message ?: "SVG tidak bisa dibaca")
        } catch (e: Exception) {
            errors.add("validasi gagal: ${e.javaClass.simpleName}: ${e.message}")
        }
        return SkinValidationResult(errors, warnings)
    }
}
