package zcode.idea.ui

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Paths

data class FileReference(val path: String, val startLine: Int? = null, val endLine: Int? = null) {
    val tooltip: String get() = "打开 " + path + when {
        endLine != null -> "，第 " + startLine + "–" + endLine + " 行"
        startLine != null -> "，第 " + startLine + " 行"
        else -> ""
    }
}

object FileRefs {
    private const val SCHEME = "zcodefile:"
    private val PATH = Regex("""^(?:[A-Za-z]:)?(?:[\p{L}\p{N}_.\-+@ &()]*[/\\])*[\p{L}\p{N}_.\-+@ &()]+\.[A-Za-z][A-Za-z0-9]{0,7}$""")
    private val LOCATION = Regex("""^(.*?)(?::(\d+)(?:[-–](\d+))?|#L(\d+)(?:[-–]L?(\d+))?)$""")
    private val PLAIN = Regex("""(?<![\w/\\.:@])(?:[A-Za-z]:)?[/\\]?(?:[\p{L}\p{N}_.\-+@]+[/\\])*[\p{L}\p{N}_.\-+@]+\.[A-Za-z][A-Za-z0-9]{0,7}(?::\d+(?:[-–]\d+)?|#L\d+(?:[-–]L?\d+)?)?(?![\w.:/\\#–-])""")
    private val PROTECTED = Regex("""<a\b[^>]*>.*?</a>|(?:https?://|file:///)[^\s<>]+|<[^>]+>""", RegexOption.IGNORE_CASE)

    fun parseReference(raw: String): FileReference? {
        var value = raw.trim()
        if (value.isEmpty() || value.length > 2048 || value.any { it.code < 32 }) return null
        if (value.startsWith("file:///", true)) {
            value = runCatching {
                val uri = URI(value)
                val path = Paths.get(URI(uri.scheme, uri.authority, uri.path, null, null)).toString()
                path + (uri.fragment?.let { "#" + it } ?: "")
            }.getOrNull() ?: return null
        } else if (value.contains("://")) return null
        val match = LOCATION.matchEntire(value)
        val path = match?.groupValues?.get(1) ?: value
        if (!PATH.matches(path)) return null
        if (match == null) return FileReference(path)
        val start = (match.groupValues[2].ifEmpty { match.groupValues[4] }).toIntOrNull() ?: return null
        val endText = match.groupValues[3].ifEmpty { match.groupValues[5] }
        val end = if (endText.isEmpty()) null else endText.toIntOrNull() ?: return null
        if (start < 1 || (end != null && end < start)) return null
        return FileReference(path, start, end)
    }

    fun hrefForCodeSpan(content: String): String? =
        parseReference(content)?.let { target ->
            val suffix = target.startLine?.let { ":" + it + (target.endLine?.let { end -> "-" + end } ?: "") } ?: ""
            SCHEME + URLEncoder.encode(target.path + suffix, Charsets.UTF_8)
        }

    fun parseTarget(href: String): FileReference? {
        if (!href.startsWith(SCHEME)) return null
        val raw = runCatching { URLDecoder.decode(href.removePrefix(SCHEME), Charsets.UTF_8) }.getOrNull() ?: return null
        return parseReference(raw)
    }

    /** Kept for callers that need only the first line. Navigation uses parseTarget instead. */
    fun parseHref(href: String): Pair<String, Int?>? = parseTarget(href)?.let { it.path to it.startLine }

    fun linkifyPlainText(html: String): String {
        val out = StringBuilder()
        var offset = 0
        for (protected in PROTECTED.findAll(html)) {
            out.append(linkifySegment(html.substring(offset, protected.range.first)))
            out.append(protected.value)
            offset = protected.range.last + 1
        }
        return out.append(linkifySegment(html.substring(offset))).toString()
    }

    private fun linkifySegment(segment: String): String = PLAIN.replace(segment) { match ->
        val ref = parseReference(match.value)
        // A bare filename needs an explicit line number to distinguish it from ordinary prose.
        if (ref == null || (ref.startLine == null && !ref.path.contains('/') && !ref.path.contains('\\'))) match.value
        else "<a href=\"" + hrefForCodeSpan(match.value) + "\">" + match.value + "</a>"
    }

    internal fun unescapeHtml(text: String): String = text.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

/** A conservative symbol candidate filter; actual links are added only after index resolution. */
internal object SymbolRefs {
    private const val SCHEME = "zcodesymbol:"
    private val CANDIDATE = Regex("""^(?:[A-Za-z_$][\w$]*[.#])?[A-Za-z_$][\w$]*(?:\(\))?$""")
    fun query(text: String): String? {
        if (text.length > 160 || !CANDIDATE.matches(text)) return null
        val name = text.removeSuffix("()").substringAfterLast('.').substringAfterLast('#')
        if (!text.endsWith("()") && name.none { it.isUpperCase() }) return null
        return name
    }
    fun href(text: String): String = SCHEME + URLEncoder.encode(text, Charsets.UTF_8)
    fun parseHref(href: String): String? {
        if (!href.startsWith(SCHEME)) return null
        val text = runCatching { URLDecoder.decode(href.removePrefix(SCHEME), Charsets.UTF_8) }.getOrNull() ?: return null
        return text.takeIf { query(it) != null }
    }
    private val SPANS = Regex("""<a\b[^>]*>.*?</a>|<code>([^<]*)</code>""")
    fun candidatesIn(html: String): List<String> = SPANS.findAll(html)
        .mapNotNull { it.groups[1]?.value }.map(FileRefs::unescapeHtml)
        .filter { query(it) != null }.distinct().toList()

    fun decorate(html: String, targets: Map<String, List<String>>): String = SPANS.replace(html) { match ->
        val raw = match.groups[1]?.value
        val text = raw?.let(FileRefs::unescapeHtml)
        val found = targets[text].orEmpty()
        if (text == null || found.isEmpty()) match.value
        else {
            val tip = if (found.size == 1) found.single() else "选择定义（" + found.size + " 个候选）：" + text
            "<a href=\"" + href(text) + "\" title=\"" + Markdown.escape(tip) +
                "\"><code>" + raw + "</code></a>"
        }
    }

}
