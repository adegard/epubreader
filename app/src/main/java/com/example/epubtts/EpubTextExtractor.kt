package com.example.epubtts

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

object EpubTextExtractor {

    data class Chapter(
        val title: String,
        val paragraphs: List<String>
    )

    fun extractChapters(epubInputStream: InputStream): List<Chapter> {
        val entries = readZipEntries(epubInputStream)
        val opfPath = entries.keys.firstOrNull { it.lowercase().endsWith(".opf") }
            ?: return emptyList()
        val opfData = entries[opfPath] ?: return emptyList()

        val doc = Jsoup.parse(String(opfData, Charsets.UTF_8), "", Parser.xmlParser())
        val manifest = mutableMapOf<String, String>()
        for (item in doc.select("manifest > item")) {
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
        }

        val baseDir = File(opfPath).parent ?: ""
        val chapters = mutableListOf<Chapter>()
        for (itemref in doc.select("spine > itemref")) {
            val idref = itemref.attr("idref")
            val href = manifest[idref] ?: continue
            val rel = if (baseDir.isEmpty()) href else "$baseDir/$href"
            if (!isHtmlFile(rel) && !isHtmlFile(href)) continue
            val data = lookupEntry(entries, rel) ?: continue
            chapters.add(parseChapter(String(data, Charsets.UTF_8), rel))
        }
        return chapters
    }

    private fun readZipEntries(stream: InputStream): Map<String, ByteArray> {
        val zip = ZipInputStream(BufferedInputStream(stream))
        val entries = mutableMapOf<String, ByteArray>()
        while (true) {
            val e = zip.nextEntry ?: break
            if (!e.isDirectory) entries[e.name] = readEntry(zip)
            zip.closeEntry()
        }
        zip.close()
        return entries
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val buffer = ByteArray(4096)
        val output = ByteArrayOutputStream()
        var read: Int
        while (zip.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun isHtmlFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
    }

    private fun lookupEntry(entries: Map<String, ByteArray>, path: String): ByteArray? {
        val normalized = normalizePath(path.trimStart('.', '/'))
        entries[normalized]?.let { return it }
        val decoded = try {
            URLDecoder.decode(normalized, "UTF-8")
        } catch (e: Exception) {
            normalized
        }
        entries[decoded]?.let { return it }
        val key = decoded.lowercase()
        for ((name, data) in entries) {
            if (name.lowercase() == key) return data
        }
        val base = decoded.substringAfterLast('/')
        for ((name, data) in entries) {
            if (name.substringAfterLast('/') == base) return data
        }
        return null
    }

    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun parseChapter(html: String, name: String): Chapter {
        val doc: Document = Jsoup.parse(html)
        doc.select("script, style, nav").remove()

        val elements = doc.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre").toMutableList()
        for (div in doc.select("div")) {
            if (div.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre").isEmpty()) {
                elements.add(div)
            }
        }

        val paragraphs = mutableListOf<String>()
        val seen = HashSet<String>()
        for (el in elements) {
            val t = el.text()
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (t.length >= 4 && seen.add(t)) paragraphs.add(t)
        }

        if (paragraphs.isEmpty()) {
            val body: org.jsoup.nodes.Element? = doc.body()
            val bodyText = body?.text()?.trim().orEmpty()
            for (part in bodyText.split(Regex("(?<=[.!?])\\s+"))) {
                val t = part.trim()
                if (t.length >= 4) paragraphs.add(t)
            }
        }

        return Chapter(findTitle(doc, paragraphs, name), paragraphs)
    }

    private fun findTitle(doc: Document, paragraphs: List<String>, name: String): String {
        for (el in doc.select("h1, h2, h3")) {
            val t = el.text().trim()
            if (t.isNotEmpty()) return t
        }
        val docTitle = doc.title().trim()
        if (docTitle.isNotEmpty()) return docTitle
        paragraphs.firstOrNull()?.take(60)?.let { return it }
        return name.substringAfterLast('/').substringBeforeLast('.')
    }
}
