package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.ui.isOpenableMarkdownLink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * The reader page's colours, as packed `0xRRGGBB` values from the app theme, never from the sender. [Dark] is the
 * page's built-in palette, so a document that is never themed keeps the colours the policy has always written.
 */
internal data class LocalHtmlPalette(
    val background: Int,
    val text: Int,
    val link: Int,
    val border: Int,
) {
    internal companion object {
        /** The page's built-in dark palette, matching the colour literals in the policy's own style header. */
        val Dark = LocalHtmlPalette(background = 0x000000, text = 0xEEEEEE, link = 0x8AC7FF, border = 0x888888)
    }
}

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

/**
 * Rebuilds sender HTML as a fresh tree from allowlists. Styling is kept because the page runs under a
 * `default-src 'none'` CSP with no script, network or file access, so CSS can lay out but never fetch.
 */
internal object LocalHtmlPolicy {
    private fun words(value: String) = value.split(' ').toSet()

    private val htmlAllowed =
        words(
            "a abbr article aside b blockquote br caption cite code col colgroup dd del details div dl dt em " +
                "figcaption figure footer h1 h2 h3 h4 h5 h6 header hr i ins kbd li main mark nav ol p pre q s " +
                "samp section small span strong sub summary sup table tbody td tfoot th thead time tr u ul wbr",
        )
    private val htmlDropped =
        words(
            "script template noscript title head iframe object embed math select textarea option button " +
                "noembed noframes xmp plaintext input form",
        )

    // Lowercase parser name to the SVG spelling. Text containers and integration points are handled below.
    private val svgAllowed =
        listOf(
            "svg g path rect circle ellipse line polyline polygon text tspan defs stop desc title marker",
            "linearGradient radialGradient clipPath",
        ).flatMap { it.split(' ') }.associateBy { it.lowercase() }
    private val svgTextOnly = setOf("title", "desc")
    private val svgTextContainers = setOf("text", "tspan")
    private val globalAttributes = words("class id style lang dir role aria-label aria-hidden")
    private val svgAttributes =
        listOf(
            "viewBox width height x y x1 y1 x2 y2 cx cy r rx ry fx fy fr d points transform pathLength",
            "fill fill-opacity fill-rule stroke stroke-width stroke-opacity stroke-linecap stroke-linejoin",
            "stroke-dasharray stroke-dashoffset stroke-miterlimit opacity visibility display color",
            "font-size font-family font-weight font-style text-anchor dominant-baseline alignment-baseline",
            "baseline-shift letter-spacing word-spacing text-decoration dx dy rotate textLength lengthAdjust",
            "preserveAspectRatio offset stop-color stop-opacity gradientUnits gradientTransform spreadMethod",
            "clip-path clip-rule clipPathUnits marker-start marker-mid marker-end markerWidth markerHeight",
            "markerUnits refX refY orient vector-effect shape-rendering paint-order",
        ).flatMap { it.split(' ') }.associateBy { it.lowercase() }

    // Attributes with no effect once rebuilt; dropping them is not worth an omitted-content notice.
    private val ignoredAttributes = words("xmlns xmlns:xlink version target rel title")
    private val ignoredHeadTags = words("meta title")
    private val span = Regex("[1-9][0-9]{0,2}")
    private val number = Regex("-?[0-9]{1,6}")
    private val scope = Regex("(?i)row|col|rowgroup|colgroup")
    private val dateTime = Regex("[0-9A-Za-z:+. -]{1,64}")
    private val localReference = Regex("(?i)\\s*url\\(\\s*#[A-Za-z0-9_.:-]{1,128}\\s*\\)\\s*")
    private const val MAX_DEPTH = 128
    private const val MAX_LINK_LENGTH = 2048
    private const val MAX_LINK_CONTROL_CODE = 0x20
    private const val MAX_NODES = 20000
    private const val MAX_OUTPUT = 2 * 1024 * 1024
    private const val MAX_ATTRIBUTE = 4096
    private const val MAX_STYLE_ATTRIBUTE = 16 * 1024
    private const val MAX_GEOMETRY_ATTRIBUTE = 64 * 1024
    private const val MAX_CSS = 256 * 1024
    private const val FIRST_PRINTABLE = 0x20
    private const val DELETE = 0x7f

    private const val BODY_COLOURS = "body{background:#000;color:#eee;"
    private const val LINK_COLOUR = "a{color:#8ac7ff}"
    private const val CELL_BORDER = "td,th{border:1px solid #888;"

    private const val HEAD =
        "<meta charset=\"UTF-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
            "style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\"><meta name=\"referrer\" " +
            "content=\"no-referrer\"><meta http-equiv=\"X-DNS-Prefetch-Control\" content=\"off\"><meta " +
            "name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>" +
            BODY_COLOURS + "font:16px sans-serif;line-height:1.5;margin:16px;" +
            "overflow-wrap:anywhere}" + LINK_COLOUR + "table{border-collapse:collapse;max-width:100%}" +
            CELL_BORDER + "padding:4px}pre{white-space:pre-wrap}blockquote{margin-inline:16px}" +
            "img,svg{max-width:100%}</style>"

    /**
     * Recolours a sanitized document's own style header with [palette], leaving the sender's markup and styles
     * untouched. Only the policy's fixed colour rules change, and the first match of each is the policy's header,
     * which precedes the sender's style element and body. The new values are formatted here from integers, so no
     * sender text reaches the page.
     */
    fun themed(
        document: LocalHtmlDocument,
        palette: LocalHtmlPalette,
    ): LocalHtmlDocument {
        if (palette == LocalHtmlPalette.Dark) return document
        val html =
            document.html
                .replaceFirst(BODY_COLOURS, "body{background:${hex(palette.background)};color:${hex(palette.text)};")
                .replaceFirst(LINK_COLOUR, "a{color:${hex(palette.link)}}")
                .replaceFirst(CELL_BORDER, "td,th{border:1px solid ${hex(palette.border)};")
        return document.copy(html = html)
    }

    /** A packed `0xRRGGBB` colour as a CSS hex literal. */
    private fun hex(rgb: Int): String = "#%06x".format(rgb and RGB_MASK)

    private const val RGB_MASK = 0xFFFFFF

    private enum class Context { HTML, SVG, SVG_TEXT }

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "LoopWithTooManyJumpStatements",
        "NestedBlockDepth",
    )
    suspend fun sanitize(source: String): LocalHtmlDocument {
        val context = currentCoroutineContext()
        context.ensureActive()
        val parsed = Jsoup.parse(source, "", Parser.htmlParser().setMaxDepth(MAX_DEPTH))
        val output = Document.createShell("")
        val links = mutableListOf<String>()
        val styles = StringBuilder()
        var visited = 0
        var omitted = false

        fun plainValue(
            value: String,
            limit: Int,
        ) = value.takeIf { candidate ->
            candidate.length <= limit &&
                candidate.none { (it.code < FIRST_PRINTABLE && it !in "\t\n\r") || it.code == DELETE }
        }

        // Every source attribute is either copied through a rule, deliberately ignored, or reported.
        fun copyAttributes(
            from: Element,
            to: Element,
            svg: Boolean,
        ) {
            for (attribute in from.attributes()) {
                val key = attribute.key.lowercase()
                val value = attribute.value
                val copied =
                    when {
                        key in globalAttributes -> {
                            val limit = if (key == "style") MAX_STYLE_ATTRIBUTE else MAX_ATTRIBUTE
                            plainValue(value, limit)?.let { to.attr(key, it) } != null
                        }
                        svg -> {
                            val name = svgAttributes[key]
                            val limit = if (key in setOf("d", "points")) MAX_GEOMETRY_ATTRIBUTE else MAX_ATTRIBUTE
                            val safe =
                                plainValue(value, limit) != null &&
                                    '\\' !in value &&
                                    (!value.contains("url(", ignoreCase = true) || localReference.matches(value))
                            name?.takeIf { safe }?.let { to.attr(it, value) } != null
                        }
                        else -> copyHtmlAttribute(to, key, value)
                    }
                if (!copied && key !in ignoredAttributes) omitted = true
            }
        }

        fun hoistStyle(style: Element) {
            val css = StringBuilder()
            var valid = true
            for (child in style.childNodes()) {
                when (child) {
                    is DataNode -> css.append(child.wholeData)
                    is TextNode -> css.append(child.wholeText)
                    else -> valid = false
                }
            }
            // Without '<' nothing can close the rebuilt raw-text <style>, wherever the block came from.
            val fits = styles.length + css.length <= MAX_CSS && plainValue(css.toString(), MAX_CSS) != null
            if (valid && fits && '<' !in css) {
                styles.append(css).append('\n')
            } else {
                omitted = true
            }
        }

        fun copy(
            node: Node,
            target: Element,
            depth: Int,
            mode: Context,
        ) {
            context.ensureActive()
            require(depth <= MAX_DEPTH) { "HTML structure limit" }
            for (child in node.childNodes()) {
                context.ensureActive()
                require(++visited <= MAX_NODES) { "HTML node limit" }
                if (child is TextNode) {
                    target.appendText(child.wholeText)
                    continue
                }
                if (child !is Element) continue
                val tag = child.normalName()
                val namespace = child.tag().namespace()
                if (tag == "style") {
                    hoistStyle(child)
                    continue
                }
                when (mode) {
                    Context.HTML -> {
                        if (namespace == Parser.NamespaceSvg && tag == "svg") {
                            val element = target.appendElement("svg")
                            copyAttributes(child, element, svg = true)
                            copy(child, element, depth + 1, Context.SVG)
                        } else if (namespace != Parser.NamespaceHtml || tag in htmlDropped) {
                            omitted = true
                        } else if (tag !in htmlAllowed) {
                            omitted = true
                            copy(child, target, depth + 1, Context.HTML)
                        } else {
                            val element = target.appendElement(tag)
                            if (tag == "a") rewriteLink(child, element, links)
                            copyAttributes(child, element, svg = false)
                            copy(child, element, depth + 1, Context.HTML)
                        }
                    }
                    Context.SVG, Context.SVG_TEXT -> {
                        val name =
                            svgAllowed[tag]
                                ?.takeIf { namespace == Parser.NamespaceSvg }
                                ?.takeIf { mode == Context.SVG || it == "tspan" }
                        if (namespace == Parser.NamespaceSvg && tag == "a") {
                            // SVG links stay inert: their text and shapes survive, the href never does.
                            omitted = true
                            copy(child, target, depth + 1, mode)
                        } else if (name == null) {
                            omitted = true
                        } else {
                            val element = target.appendElement(name)
                            copyAttributes(child, element, svg = true)
                            when (name) {
                                // HTML integration points: only text, so nothing changes namespace on reparse.
                                in svgTextOnly -> {
                                    if (child.children().isNotEmpty()) omitted = true
                                    element.appendText(child.wholeText())
                                }
                                in svgTextContainers -> copy(child, element, depth + 1, Context.SVG_TEXT)
                                else -> copy(child, element, depth + 1, Context.SVG)
                            }
                        }
                    }
                }
            }
        }

        for (child in parsed.head().children()) {
            when {
                child.normalName() == "style" -> hoistStyle(child)
                child.normalName() !in ignoredHeadTags -> omitted = true
            }
        }
        // The root keeps only its language and direction; a root class or style would restyle the reader shell.
        parsed.body().parent()?.let { root ->
            val target = output.selectFirst("html")!!
            for (attribute in root.attributes()) {
                val key = attribute.key.lowercase()
                val value = plainValue(attribute.value, MAX_ATTRIBUTE)
                if ((key == "lang" || key == "dir") && value != null) {
                    target.attr(key, value)
                } else if (key !in ignoredAttributes) {
                    omitted = true
                }
            }
        }
        copyAttributes(parsed.body(), output.body(), svg = false)
        copy(parsed.body(), output.body(), 0, Context.HTML)
        output.head().append(HEAD)
        if (styles.isNotEmpty()) output.head().appendElement("style").appendChild(DataNode(styles.toString()))
        output.outputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.base)
        // The browser parses this text again, and tree-builder repairs such as closing a <p> or <a> inside an
        // unwrapped element can change the nesting. Serializing jsoup's spec-following reparse, then refusing any
        // result that a further parse would still change, keeps the page the browser builds the tree checked here.
        val html = reparse(output.html())
        require(reparse(html) == html) { "HTML reparse changed the document" }
        require(html.toByteArray(Charsets.UTF_8).size <= MAX_OUTPUT) { "HTML output limit" }
        return LocalHtmlDocument(html, links.toList(), omitted)
    }

    private fun reparse(html: String): String =
        Jsoup
            .parse(if (html.startsWith("<!doctype html>", ignoreCase = true)) html else "<!doctype html>$html")
            .apply { outputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.base) }
            .html()

    @Suppress("ComplexCondition")
    private fun rewriteLink(
        from: Element,
        to: Element,
        links: MutableList<String>,
    ) {
        val href = from.attr("href").trim()
        if ((href.startsWith("http://", true) || href.startsWith("https://", true)) &&
            href.length <= MAX_LINK_LENGTH &&
            href.none { it.code <= MAX_LINK_CONTROL_CODE } &&
            isOpenableMarkdownLink(href)
        ) {
            to.attr("href", "wn-document-link:${links.size}")
            links.add(href)
        }
    }

    /** Per-element HTML attributes; `href` was already rewritten, never copied. */
    @Suppress("CyclomaticComplexMethod")
    private fun copyHtmlAttribute(
        to: Element,
        key: String,
        value: String,
    ): Boolean {
        val tag = to.normalName()
        val accepted =
            when {
                key == "href" -> tag == "a" && to.hasAttr("href")
                (tag == "td" || tag == "th") && (key == "colspan" || key == "rowspan") -> span.matches(value)
                tag == "th" && key == "scope" -> scope.matches(value)
                (tag == "col" || tag == "colgroup") && key == "span" -> span.matches(value)
                tag == "ol" && key == "start" -> number.matches(value)
                tag == "li" && key == "value" -> number.matches(value)
                tag == "ol" && key == "reversed" -> true
                tag == "details" && key == "open" -> true
                tag == "time" && key == "datetime" -> dateTime.matches(value)
                else -> false
            }
        if (accepted && key != "href") {
            to.attr(key, if (key == "reversed" || key == "open") "" else value)
        }
        return accepted
    }
}
