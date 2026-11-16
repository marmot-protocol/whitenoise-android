package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.ui.isOpenableMarkdownLink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

internal data class LocalHtmlDocument(
    val html: String,
    val links: List<String>,
    val omitted: Boolean,
) {
    fun destination(token: String): String? =
        token
            .removePrefix("wn-document-link:")
            .toIntOrNull()
            ?.takeIf { token == "wn-document-link:$it" }
            ?.let(links::getOrNull)
}

internal object LocalHtmlPolicy {
    private val allowed =
        (
            "a b blockquote br caption code dd del div dl dt em h1 h2 h3 h4 h5 h6 hr i ins " +
                "kbd li mark ol p pre q s samp small span strong sub sup table tbody td tfoot " +
                "th thead tr u ul"
        ).split(
            ' ',
        ).toSet()
    private val dropped =
        (
            "script style template noscript title head iframe object embed svg math select " +
                "textarea option button noembed noframes xmp plaintext input form"
        ).split(
            ' ',
        ).toSet()
    private val span = Regex("[1-9][0-9]{0,2}")
    private const val MAX_DEPTH = 128
    private const val MAX_LINK_LENGTH = 2048
    private const val MAX_LINK_CONTROL_CODE = 0x20
    private const val MAX_NODES = 20000
    private const val MAX_OUTPUT = 2 * 1024 * 1024

    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth")
    suspend fun sanitize(source: String): LocalHtmlDocument {
        val context = currentCoroutineContext()
        context.ensureActive()
        val parsed = Jsoup.parse(source, "", Parser.htmlParser().setMaxDepth(MAX_DEPTH))
        val output = Document.createShell("")
        val links = mutableListOf<String>()
        var visited = 0
        // The output shell keeps no root or body attributes, so a styled <body> also counts as omitted content.
        var omitted =
            parsed.head().children().isNotEmpty() ||
                parsed.body().attributesSize() > 0 ||
                (parsed.body().parent()?.attributesSize() ?: 0) > 0

        fun copy(
            node: Node,
            target: Element,
            depth: Int,
        ) {
            context.ensureActive()
            require(depth <= MAX_DEPTH) { "HTML structure limit" }
            for (child in node.childNodes()) {
                context.ensureActive()
                require(++visited <= MAX_NODES) { "HTML node limit" }
                when (child) {
                    is TextNode -> target.appendText(child.wholeText)
                    is Element -> {
                        val tag = child.normalName()
                        if (tag in dropped) {
                            omitted = true
                            continue
                        }
                        if (tag !in allowed) {
                            omitted = true
                            copy(child, target, depth + 1)
                            continue
                        }
                        val element = target.appendElement(tag)
                        if (tag == "a") {
                            val href = child.attr("href").trim()
                            if ((href.startsWith("http://", true) || href.startsWith("https://", true)) &&
                                href.length <= MAX_LINK_LENGTH &&
                                href.none { it.code <= MAX_LINK_CONTROL_CODE } &&
                                isOpenableMarkdownLink(href)
                            ) {
                                element.attr("href", "wn-document-link:${links.size}")
                                links.add(href)
                            }
                        }
                        if (tag == "td" || tag == "th") {
                            for (key in listOf("colspan", "rowspan")) {
                                child.attr(key).takeIf(span::matches)?.let { element.attr(key, it) }
                            }
                        }
                        if (child.attributes().any { it.key !in element.attributes().map { attr -> attr.key } }) {
                            omitted = true
                        }
                        copy(child, element, depth + 1)
                    }
                }
            }
        }
        copy(parsed.body(), output.body(), 0)
        output.outputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.base)
        val body = output.body().html()
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_OUTPUT) { "HTML output limit" }
        return LocalHtmlDocument(
            (
                "<!doctype html><html><head><meta charset=\"UTF-8\"><meta " +
                    "http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
                    "style-src 'unsafe-inline'\"><meta name=\"referrer\" content=\"no-referrer\">" +
                    "<meta http-equiv=\"X-DNS-Prefetch-Control\" content=\"off\"><meta " +
                    "name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>" +
                    "body{background:#000;color:#eee;font:16px sans-serif;line-height:1.5;" +
                    "margin:16px;overflow-wrap:anywhere}a{color:#8ac7ff}" +
                    "table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #888;" +
                    "padding:4px}pre{white-space:pre-wrap}blockquote{margin-inline:16px}</style>" +
                    "</head><body>$body</body></html>"
            ),
            links.toList(),
            omitted,
        )
    }
}
