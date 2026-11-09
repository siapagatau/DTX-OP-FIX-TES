package com.kendang.realpads.skin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Membaca skin.json + skin.svg menjadi SkinDefinition.
 * Posisi/ukuran SEMUA kontrol dibaca dari SVG (hit-* rect) - tidak ada koordinat hardcode,
 * dan jumlah pad dibaca dari controls (pad-N), bukan konstanta.
 */
object SkinParser {

    fun parse(jsonText: String, svgText: String, source: String): SkinDefinition {
        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw SkinException("skin.json bukan JSON yang valid: ${e.message}", e)
        }
        val doc = SvgDom.parse(svgText)
        val root = doc.documentElement
        if (root == null || root.tagName != "svg") throw SkinException("Root SVG bukan <svg>")

        val viewBox = parseViewBox(root.getAttribute("viewBox"))
            ?: throw SkinException("SVG tidak punya viewBox yang valid")

        val name = root.getAttribute("data-skin-name").ifBlank { json.optString("name", "") }
        val version = root.getAttribute("data-skin-version").ifBlank { json.optString("version", "") }
        val author = root.getAttribute("data-skin-author").ifBlank { json.optString("author", "") }
        val padMin = root.getAttribute("data-pad-min").toIntOrNull() ?: json.optJSONObject("padSupport")?.optInt("min", 0) ?: 0
        val padMax = root.getAttribute("data-pad-max").toIntOrNull() ?: json.optJSONObject("padSupport")?.optInt("max", 0) ?: 0

        val mapping = json.optJSONObject("mapping") ?: JSONObject()
        val controlsJson = json.optJSONArray("controls") ?: JSONArray()
        val controls = ArrayList<SkinControl>()

        for (i in 0 until controlsJson.length()) {
            val c = controlsJson.optJSONObject(i) ?: continue
            val id = c.optString("id", "")
            val type = ControlType.from(c.optString("type", "")) ?: continue
            val m = mapping.optJSONObject(id) ?: JSONObject()
            val function = m.optString("function", "")
            val params = HashMap<String, Any?>()
            m.optJSONObject("params")?.let { p -> p.keys().forEach { k -> params[k] = p.opt(k) } }
            val status = m.optString("apkStatus", "existing")

            val hitId = c.optString("hit", "").ifBlank { null }
            val hitRect = hitId?.let { hid -> SvgDom.findById(root, hid)?.let { SvgDom.rectOf(it) } }
            val activeId = c.optJSONObject("states")?.optString("active", "")?.ifBlank { null }
            val trigger = when (c.optString("trigger", if (type == ControlType.PAD) "down" else "tap")) {
                "down" -> Trigger.DOWN
                else -> Trigger.TAP
            }
            val index = if (type == ControlType.PAD) c.optInt("index", id.substringAfterLast('-').toIntOrNull() ?: 0) else 0

            var rotary: RotaryInfo? = null
            if (type == ControlType.ROTARY) {
                val g = SvgDom.findById(root, id)
                val cx = g?.let { SvgDom.attrFloat(it, "data-cx") }
                val cy = g?.let { SvgDom.attrFloat(it, "data-cy") }
                if (g != null && cx != null && cy != null) {
                    rotary = RotaryInfo(
                        cx = cx, cy = cy,
                        angleMin = SvgDom.attrFloat(g, "data-angle-min") ?: c.optDouble("angleMin", -135.0).toFloat(),
                        angleMax = SvgDom.attrFloat(g, "data-angle-max") ?: c.optDouble("angleMax", 135.0).toFloat(),
                        defaultValue = (SvgDom.attrFloat(g, "data-value-default") ?: c.optDouble("defaultValue", 0.5).toFloat())
                            .coerceIn(0f, 1f)
                    )
                }
            }

            var display: DisplayInfo? = null
            if (type == ControlType.DISPLAY) {
                val g = SvgDom.findById(root, id)
                val rect = g?.let { SvgDom.elements(it).firstOrNull { e -> e.tagName == "rect" } }?.let { SvgDom.rectOf(it) }
                if (rect != null) {
                    val tpl = m.optJSONArray("template")
                    val lines = ArrayList<String>()
                    if (tpl != null) for (k in 0 until tpl.length()) lines.add(tpl.optString(k))
                    display = DisplayInfo(rect, lines, parseColor(m.optString("textColor", ""), 0xFF2F3A0C.toInt()))
                }
            }

            controls.add(
                SkinControl(
                    id = id, type = type, index = index, hitId = hitId, hit = hitRect, activeId = activeId,
                    trigger = trigger, function = function, params = params, apkStatus = status,
                    rotary = rotary, display = display
                )
            )
        }

        val unsupported = ArrayList<String>()
        json.optJSONArray("unsupportedInReference")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("function", o.optString("id", ""))
                if (label.isNotBlank()) unsupported.add(label)
            }
        }

        val layoutJson = json.optJSONObject("layout")
        val padZone = layoutJson?.optString("padZone", "")?.let { parseRect(it) }
        val panelZone = layoutJson?.optString("panelZone", "")?.let { parseRect(it) }

        val indicators = ArrayList<SkinIndicator>()
        json.optJSONArray("indicators")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val onId = o.optString("on", "")
                val pad = o.optInt("pad", 0)
                if (id.isBlank() || onId.isBlank() || pad <= 0) continue
                val el = SvgDom.findById(root, onId) ?: continue
                // Area lampu = persegi pertama di dalam elemen "on" (atau elemen itu sendiri bila berupa <rect>).
                val rect = (if (el.tagName == "rect") SvgDom.rectOf(el)
                            else SvgDom.elements(el).firstOrNull { e -> e.tagName == "rect" }?.let { r -> SvgDom.rectOf(r) })
                    ?: continue
                if (rect.w <= 0f || rect.h <= 0f) continue
                indicators.add(SkinIndicator(id, pad, onId, rect))
            }
        }

        return SkinDefinition(
            name = name, version = version, author = author, svgText = svgText, viewBox = viewBox,
            padMin = padMin, padMax = padMax, controls = controls, unsupportedFunctions = unsupported, source = source,
            padZoneDecl = padZone, panelZoneDecl = panelZone, indicators = indicators
        )
    }

    /** "x y w h" -> SvgRect; salah format / ukuran <= 0 -> null (zona dihitung otomatis oleh SkinZones). */
    private fun parseRect(s: String): SvgRect? {
        val p = s.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (p.size != 4 || p[2] <= 0f || p[3] <= 0f) return null
        return SvgRect(p[0], p[1], p[2], p[3])
    }

    private fun parseViewBox(s: String): SvgRect? {
        val p = s.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (p.size != 4 || p[2] <= 0f || p[3] <= 0f) return null
        return SvgRect(p[0], p[1], p[2], p[3])
    }

    private fun parseColor(s: String, fallback: Int): Int =
        try { if (s.isBlank()) fallback else android.graphics.Color.parseColor(s) } catch (_: Exception) { fallback }
}
