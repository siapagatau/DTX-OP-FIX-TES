package com.kendang.realpads.skin

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Utilitas DOM kecil untuk SVG skin (tanpa library tambahan). */
internal object SvgDom {

    fun parse(svgText: String): Document {
        // Tolak DOCTYPE/ENTITY (XXE / billion-laughs). Skin tidak butuh keduanya.
        if (svgText.contains("<!DOCTYPE", ignoreCase = true) || svgText.contains("<!ENTITY", ignoreCase = true)) {
            throw SkinException("SVG ditolak: DOCTYPE/ENTITY tidak diizinkan")
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // Parser Android tidak mengenal fitur ini; pemeriksaan teks di atas sudah cukup.
        }
        try {
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(svgText.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw SkinException("SVG bukan XML yang valid: ${e.message}", e)
        }
    }

    fun elements(root: Node): Sequence<Element> = sequence {
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n is Element) yield(n)
            val kids = n.childNodes
            for (i in kids.length - 1 downTo 0) stack.addLast(kids.item(i))
        }
    }

    fun findById(root: Node, id: String): Element? =
        elements(root).firstOrNull { it.getAttribute("id") == id }

    fun attrFloat(e: Element, name: String): Float? = e.getAttribute(name).trim().toFloatOrNull()

    fun rectOf(e: Element): SvgRect? {
        val x = attrFloat(e, "x") ?: return null
        val y = attrFloat(e, "y") ?: return null
        val w = attrFloat(e, "width") ?: return null
        val h = attrFloat(e, "height") ?: return null
        return SvgRect(x, y, w, h)
    }

    fun remove(e: Element) {
        e.parentNode?.removeChild(e)
    }

    fun serialize(doc: Document): String {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val sw = StringWriter()
        t.transform(DOMSource(doc), StreamResult(sw))
        return sw.toString()
    }
}
