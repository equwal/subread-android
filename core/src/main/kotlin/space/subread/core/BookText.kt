package space.subread.core

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * A book as a flat list of paragraphs, in reading order.
 *
 * Reads epubs straight off the zip with a tolerant HTML parser rather than an
 * epub library: converted and scraped epubs are malformed more often than not,
 * and all that is needed from one is its text in spine order.
 */
object BookText {

    private val BLOCKS = "p, li, blockquote, h1, h2, h3, h4, h5, h6"

    fun read(file: File): List<String> = file.inputStream().use { read(it, file.name) }

    fun read(input: InputStream, name: String): List<String> = when {
        name.endsWith(".epub", ignoreCase = true) -> epub(input)
        name.endsWith(".zip", ignoreCase = true) -> aozoraZip(input)
        else -> plain(decode(input.readBytes()))
    }

    // ------------------------------------------------------------------ epub

    fun epub(input: InputStream): List<String> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
            }
        }

        val order = spine(entries) ?: entries.keys.filter(::isPage).sorted()
        return order.flatMap { path -> entries[path]?.let(::page) ?: emptyList() }
    }

    private fun isPage(name: String) =
        name.endsWith(".xhtml", true) || name.endsWith(".html", true) || name.endsWith(".htm", true)

    /** Document paths in spine order, or null when the package file cannot be followed. */
    private fun spine(entries: Map<String, ByteArray>): List<String>? {
        val container = entries["META-INF/container.xml"] ?: return null
        val opfPath = Jsoup.parse(String(container), "", Parser.xmlParser())
            .selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotEmpty() } ?: return null
        val opf = Jsoup.parse(String(entries[opfPath] ?: return null), "", Parser.xmlParser())
        val base = opfPath.substringBeforeLast('/', "")

        val hrefs = opf.select("manifest > item").associate { it.attr("id") to it.attr("href") }
        val paths = opf.select("spine > itemref")
            // linear="no" is auxiliary content (notes, answers) outside the reading order.
            .filter { it.attr("linear") != "no" }
            .mapNotNull { hrefs[it.attr("idref")] }
            .map { resolve(base, java.net.URLDecoder.decode(it.substringBefore('#'), "UTF-8")) }
            .filter { it in entries }
        return paths.ifEmpty { null }
    }

    private fun resolve(base: String, href: String): String {
        val parts = ArrayDeque(if (base.isEmpty()) emptyList() else base.split('/'))
        for (seg in href.split('/')) when (seg) {
            "", "." -> {}
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(seg)
        }
        return parts.joinToString("/")
    }

    private fun page(bytes: ByteArray): List<String> {
        val doc = Jsoup.parse(String(bytes, Charsets.UTF_8))
        // Furigana would otherwise be read twice: once as kanji, once as kana.
        doc.select("rt, rp").remove()
        val body = doc.body()
        val blocks = body.select(BLOCKS)
            // A quote holding paragraphs would yield its text twice; keep the leaves.
            .filter { it.select(BLOCKS).size == 1 }
        val source = if (blocks.isEmpty()) listOf(body) else blocks
        return source.map { it.text().trim() }.filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- aozora

    /** An Aozora Bunko download: a zip holding one Shift_JIS text with ruby markup. */
    fun aozoraZip(input: InputStream): List<String> {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.endsWith(".txt", true)) return aozora(decode(zip.readBytes()))
            }
        }
        return emptyList()
    }

    private val annotation = Regex("［＃[^］]*］")
    private val ruby = Regex("《[^》]*》")

    fun aozora(raw: String): List<String> {
        var lines = raw.replace("\r\n", "\n").split('\n')
        // Title block, then a legend fenced by two dashed rules; then the text.
        val rules = lines.indices.filter { lines[it].startsWith("-----") }
        if (rules.size >= 2) lines = lines.drop(rules[1] + 1)
        // The colophon.
        val colophon = lines.indexOfFirst { it.startsWith("底本：") }
        if (colophon >= 0) lines = lines.take(colophon)

        return lines.map { ruby.replace(annotation.replace(it, ""), "").replace("｜", "").trim() }
            .filter { it.isNotEmpty() }
    }

    // ----------------------------------------------------------------- plain

    fun plain(text: String): List<String> {
        if ("《" in text && "底本：" in text) return aozora(text)
        return text.replace("\r\n", "\n").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** UTF-8 unless it plainly is not, then Shift_JIS - the two a text file here will be. */
    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if ('�' !in utf8) return utf8.removePrefix("﻿")
        return String(bytes, Charset.forName("Shift_JIS"))
    }
}
