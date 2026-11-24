package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHtmlPolicyTest {
    private val hostile =
        listOf(
            "<base href=https://evil.example/><a href=relative>relative</a>",
            "<img src=x srcset='https://evil.example 2x'><video poster=x><source src=x></video>",
            (
                "<table background=https://evil.example><tr><td " +
                    "style='background:url(https://evil.example)' colspan=2>text</td></tr></table>"
            ),
            "<math><mtext><table><mglyph><style><img src=https://evil.example>",
            "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>",
            "<svg><a href=https://evil.example>svg</a></svg><select><option>option</option></select>",
            "<iframe srcdoc='<img src=x>'></iframe><object data=x></object><embed src=x>",
            (
                "<meta http-equiv=refresh content='0;url=https://evil.example'><link " +
                    "rel=prefetch href=https://evil.example>"
            ),
            "<style>@import 'https://evil.example';p{color:red}</style><script>fetch('https://evil.example')</script>",
            "<form action=https://evil.example method=post><input name=x><button>send</button></form>",
            "<p>&lt;script&gt; &amp; <custom-el onclick=x>kept text</custom-el></p>",
            "<a href='https://ok.example/\"><img src=https://evil.example>' ping=x target=_blank>quote</a>",
            "<svg><title><img src=x onerror=alert(1)></title><desc><svg><foreignObject><img src=x>",
            "<svg><style>&lt;/style&gt;&lt;img src=x onerror=alert(1)&gt;</style></svg>",
            "<svg><style><![CDATA[</style><img src=x onerror=alert(1)>]]></style></svg>",
            "<svg><style><g></g>p{}</style></svg><p style=\"x</style><img src=x>\">p</p>",
            "<svg><foreignObject><div onclick=x>inside</div></foreignObject><b>breakout</b></svg>",
            (
                "<svg onload=alert(1)><use href=#a xlink:href=https://evil.example/x.svg#a /><image href=x />" +
                    "<animate attributeName=href to=javascript:x /><set attributeName=fill to=red /></svg>"
            ),
            (
                "<svg><rect fill='url(https://evil.example/x)' stroke='URL( \"https://evil.example\" )' " +
                    "filter='url(#a)' clip-path='url(#a) url(https://evil.example)' mask=url(#a) /></svg>"
            ),
            "<svg><text>t<a href=https://evil.example>link</a><tspan>s</tspan><rect /></text></svg>",
            "<svg><rect fill='&#117;rl(https://evil.example)' stroke='\\75 rl(x)' /></svg>",
            "<div onclick=x onmouseover=y style='color:red'>events</div><body onload=x background=y>",
            "<template><style>p{}</style><img src=x></template><xmp><img src=x></xmp>",
        )

    private val allowedTags =
        (
            "html head body meta style a abbr article aside b blockquote br caption cite code col colgroup dd " +
                "del details div dl dt em figcaption figure footer h1 h2 h3 h4 h5 h6 header hr i ins kbd li main " +
                "mark nav ol p pre q s samp section small span strong sub summary sup table tbody td tfoot th " +
                "thead time tr u ul wbr svg g path rect circle ellipse line polyline polygon text tspan defs stop " +
                "desc title marker lineargradient radialgradient clippath"
        ).split(' ').toSet()
    private val forbiddenAttribute = Regex("(?i)on.*|.*:.*|src|srcset|srcdoc|background|action|poster|data|ping")

    private fun bodyInvariants(
        source: String,
        result: LocalHtmlDocument,
    ) {
        val output = Jsoup.parse(result.html)
        for (element in output.body().allElements) {
            assertTrue("$source -> ${element.normalName()}", element.normalName() in allowedTags - "meta" - "style")
            for (attr in element.attributes()) {
                assertFalse("$source -> ${attr.key}", forbiddenAttribute.matches(attr.key))
                if (attr.key == "href") assertNotNull(source, result.destination(attr.value))
                if (attr.key != "style") {
                    assertFalse(source, attr.value.contains("url(", true) && !attr.value.contains("url(#", true))
                }
            }
        }
        val styles = output.head().select("style")
        assertTrue(source, styles.size in 1..2)
        for (style in styles) assertFalse(source, '<' in style.data())
    }

    @Test fun hostileContentHasNoSenderResourcesOrActiveMarkup() =
        runBlocking {
            for (source in hostile) {
                val result = LocalHtmlPolicy.sanitize(source)
                assertTrue(source, result.omitted)
                bodyInvariants(source, result)
                // Inline style url() survives by design; the CSP and blocked network loads contain it.
                val body = Jsoup.parse(result.html).body()
                body.select("[style]").removeAttr("style")
                assertFalse(source, "evil.example" in body.html())
            }
        }

    /** A second pass over the output keeps everything: the browser's reparse cannot move markup elsewhere. */
    @Test fun outputIsAStableReparse() =
        runBlocking {
            val unwrappedNesting =
                listOf(
                    "<a href=https://a.example/><marquee><a href=https://b.example/>x</a></marquee>tail</a>",
                    "<p><marquee><p>inner</p></marquee>after</p>",
                    "<h1><font><h1>inner</h1></font>after</h1>",
                    "<ul><li><center><li>inner</li></center>after</li></ul>",
                )
            for (source in hostile + unwrappedNesting + AGENT_OVERVIEW) {
                val html = LocalHtmlPolicy.sanitize(source).html
                val reparsed = Jsoup.parse(html)
                reparsed.outputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.base)
                assertEquals(source, html, reparsed.html())
                val first = Jsoup.parse(html)
                first.select("a[href]").removeAttr("href")
                val second = LocalHtmlPolicy.sanitize(first.html())
                assertFalse(source, second.omitted)
                assertEquals(source, first.body().html(), Jsoup.parse(second.html).body().html())
            }
        }

    @Test fun senderStylesheetFollowsPolicyInOneHeadBlock() =
        runBlocking {
            val doc =
                LocalHtmlPolicy.sanitize(
                    "<head><style>.a{color:red}</style></head><body><svg><style>.b{fill:red}</style></svg></body>",
                )
            val output: Document = Jsoup.parse(doc.html)
            assertFalse(doc.omitted)
            val head = output.head().children().map { it.normalName() }
            val policy = output.head().selectFirst("meta[http-equiv=Content-Security-Policy]")!!
            assertTrue("default-src 'none'" in policy.attr("content"))
            assertEquals(listOf("style", "style"), head.filter { it == "style" })
            assertTrue(head.indexOf("meta") < head.lastIndexOf("style"))
            assertEquals(
                ".a{color:red}\n.b{fill:red}\n",
                output
                    .head()
                    .select("style")
                    .last()!!
                    .data(),
            )
            assertTrue(output.body().select("style").isEmpty())
        }

    @Test fun agentOverviewKeepsLayoutStylingAndCharts() =
        runBlocking {
            val doc = LocalHtmlPolicy.sanitize(AGENT_OVERVIEW)
            assertFalse(doc.omitted)
            val output = Jsoup.parse(doc.html)
            assertTrue(
                ".card{border:1px solid #333}" in
                    output
                        .head()
                        .select("style")
                        .last()!!
                        .data(),
            )
            assertEquals("en", output.selectFirst("html")!!.attr("lang"))
            assertEquals("page", output.body().className())
            assertEquals("padding:8px", output.selectFirst("section.card")!!.attr("style"))
            assertEquals("2", output.selectFirst("th")!!.attr("colspan"))
            assertTrue(output.selectFirst("details")!!.hasAttr("open"))
            // jsoup prints SVG names lowercase; the HTML parser restores their SVG spelling on load.
            assertEquals("0 0 100 50", output.selectFirst("svg")!!.attributes().get("viewBox"))
            assertEquals("linearGradient", output.selectFirst("[id=g]")!!.tagName())
            assertTrue("stop-color=\"#f00\"" in doc.html)
            assertEquals("url(#g)", output.selectFirst("rect")!!.attr("fill"))
            assertEquals("Q1 a", output.selectFirst("svg text")!!.text())
            assertEquals("Revenue", output.selectFirst("svg title")!!.text())
        }

    @Test fun rawLinksIgnoreBaseAndOnlyAllowLiteralHttpSchemes() =
        runBlocking {
            val rejected =
                listOf(
                    "relative",
                    " javascript:alert(1)",
                    "data:text/html,x",
                    "file:///x",
                    "content://x",
                    "intent://x",
                    "blob:x",
                    "&#106;avascript:x",
                    "https://a.example/a&#10;b",
                    "https://" + "x".repeat(2048),
                )
            val source =
                "<base href=https://base.example/>" + rejected.joinToString("") { "<a href='$it'>bad</a>" } +
                    "<a href=' HTTPS://ok.example/a?x=1&amp;y=2 '>ok</a>"
            val doc = LocalHtmlPolicy.sanitize(source)
            assertEquals(listOf("HTTPS://ok.example/a?x=1&y=2"), doc.links)
            assertEquals(doc.links[0], doc.destination("wn-document-link:0"))
            for (token in listOf(
                "wn-document-link:-1",
                "wn-document-link:01",
                "wn-document-link:1",
                "https://ok.example",
                "0",
            )) {
                assertNull(doc.destination(token))
            }
        }

    /** Links the chat link confirmation would refuse, such as hidden userinfo or bidi overrides, stay inert text. */
    @Test fun deceptiveHttpLinksGetNoDestination() =
        runBlocking {
            for (href in listOf("https://bank.example@evil.example/", "https://bank.example‮elpmaxe.live")) {
                val doc = LocalHtmlPolicy.sanitize("<a href=\"$href\">bank</a>")
                assertTrue(doc.links.isEmpty())
                assertFalse("wn-document-link" in doc.html)
            }
        }

    @Test fun extremeDepthSizeAndStructureAreBounded() =
        runBlocking {
            val deep = "<div>".repeat(1000) + "text" + "</div>".repeat(1000)
            val result = runCatching { LocalHtmlPolicy.sanitize(deep) }
            result.getOrNull()?.let { document ->
                for (element in Jsoup.parse(document.html).body().allElements) {
                    assertTrue(element.parents().size <= 130)
                }
            }
            assertTrue(runCatching { LocalHtmlPolicy.sanitize("<p>x</p>".repeat(21000)) }.isFailure)
            val longStyle = LocalHtmlPolicy.sanitize("<p style='${"x".repeat(17 * 1024)}'>p</p>")
            assertTrue(longStyle.omitted)
            assertFalse(Jsoup.parse(longStyle.html).selectFirst("p")!!.hasAttr("style"))
            val css = "p{}".repeat(90 * 1024)
            assertTrue(LocalHtmlPolicy.sanitize("<style>$css</style>").omitted)
        }

    /** Theming recolours only the policy's own header rules and leaves sender markup and sender CSS alone. */
    @Test
    fun themedRecoloursOnlyThePolicyHeader() =
        runBlocking {
            val sender = "<style>body{background:#000;color:#eee;}a{color:#8ac7ff}</style><p>text</p>"
            val document = LocalHtmlPolicy.sanitize(sender)
            val palette = LocalHtmlPalette(background = 0xF7F7F7, text = 0x1A1A1A, link = 0x123456, border = 0xABCDEF)
            val themed = LocalHtmlPolicy.themed(document, palette)
            val head = Jsoup.parse(themed.html).head().select("style")
            assertTrue("body{background:#f7f7f7;color:#1a1a1a;" in head.first()!!.data())
            assertTrue("a{color:#123456}" in head.first()!!.data())
            assertTrue("td,th{border:1px solid #abcdef;" in head.first()!!.data())
            val senderRules = head.last()!!.data()
            assertTrue("the sender's own rules are untouched", "body{background:#000;color:#eee;}" in senderRules)
            assertEquals(Jsoup.parse(document.html).body().html(), Jsoup.parse(themed.html).body().html())
            assertEquals(document.links, themed.links)
        }

    /** A sender's root `lang` or `dir` that spells the header's rules cannot divert the recolouring. */
    @Test
    fun themedIgnoresHeaderRulesSpelledInRootAttributes() =
        runBlocking {
            val sender = "<html lang=\"body{background:#000;color:#eee;\" dir=\"a{color:#8ac7ff}\"><p>text</p></html>"
            val document = LocalHtmlPolicy.sanitize(sender)
            val palette = LocalHtmlPalette(background = 0xF7F7F7, text = 0x1A1A1A, link = 0x123456, border = 0xABCDEF)
            val themed = Jsoup.parse(LocalHtmlPolicy.themed(document, palette).html)
            val root = Jsoup.parse(document.html).selectFirst("html")!!
            assertEquals(root.attributes(), themed.selectFirst("html")!!.attributes())
            val header = themed.head().selectFirst("style")!!.data()
            assertTrue("body{background:#f7f7f7;color:#1a1a1a;" in header)
            assertTrue("a{color:#123456}" in header)
        }

    @Test fun formattingTextAndBoundedTableSpansSurvive() =
        runBlocking {
            val doc =
                LocalHtmlPolicy.sanitize(
                    (
                        "<h1>Title</h1><p>&lt;safe&gt;</p><table><tr><td colspan=999 rowspan=0>x</td>" +
                            "</tr></table>"
                    ),
                )
            val body = Jsoup.parse(doc.html).body()
            assertEquals("Title", body.selectFirst("h1")!!.text())
            assertEquals("<safe>", body.selectFirst("p")!!.text())
            assertEquals("999", body.selectFirst("td")!!.attr("colspan"))
            assertFalse(body.selectFirst("td")!!.hasAttr("rowspan"))
        }

    @Test fun discardedRootAndBodyAttributesReportOmittedContent() =
        runBlocking {
            assertFalse(LocalHtmlPolicy.sanitize("<p>Notes</p>").omitted)
            for (source in listOf(
                "<body onload='alert(1)' background=https://evil.example><p>Notes</p></body>",
                "<html manifest=https://evil.example><body><p>Notes</p></body></html>",
                "<html class=wide style='background:red'><body><p>Notes</p></body></html>",
            )) {
                val doc = LocalHtmlPolicy.sanitize(source)
                assertTrue(source, doc.omitted)
                val output = Jsoup.parse(doc.html)
                assertEquals(source, 0, output.body().attributesSize())
                assertEquals(source, 0, output.selectFirst("html")!!.attributesSize())
            }
        }

    private companion object {
        const val AGENT_OVERVIEW =
            "<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport " +
                "content='width=device-width'><title>Overview</title><style>.card{border:1px solid #333}" +
                "</style></head><body class=page><header><h1>Sprint overview</h1></header>" +
                "<section class=card style='padding:8px'><table><thead><tr><th colspan=2 scope=col>Status</th>" +
                "</tr></thead><tbody><tr><td>Done</td><td><span class='pill ok'>12</span></td></tr></tbody>" +
                "</table><details open><summary>More</summary><ol start=3><li>Item</li></ol></details>" +
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50' width=100 role=img " +
                "aria-label=Chart><title>Revenue</title><defs><linearGradient id=g x1=0 x2=1>" +
                "<stop offset=0 stop-color=#f00 /></linearGradient></defs><rect width=10 height=20 " +
                "fill='url(#g)' /><path d='M0 0 L10 10' stroke=#fff stroke-width=2 /><text x=1 y=10 " +
                "text-anchor=start>Q1 <tspan dx=2>a</tspan></text></svg></section></body></html>"
    }
}
