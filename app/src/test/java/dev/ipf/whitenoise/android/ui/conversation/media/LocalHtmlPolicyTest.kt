package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHtmlPolicyTest {
    @Test fun hostileContentHasNoSenderResourcesOrAttributes() =
        runBlocking {
            val vectors =
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
                    (
                        "<style>@import 'https://evil.example';p{background:url(x)}</style><script>" +
                            "fetch('https://evil.example')</script>"
                    ),
                    "<form action=https://evil.example method=post><input name=x><button>send</button></form>",
                    "<p>&lt;script&gt; &amp; <custom-el onclick=x>kept text</custom-el></p>",
                    "<a href='https://ok.example/\"><img src=https://evil.example>' ping=x target=_blank>quote</a>",
                )
            for (source in vectors) {
                val result = LocalHtmlPolicy.sanitize(source)
                val body = Jsoup.parse(result.html).body()
                assertTrue(
                    source,
                    body.select("script,style,img,svg,math,iframe,object,embed,meta,link,input,form").isEmpty(),
                )
                for (element in body.allElements) {
                    for (attr in element.attributes()) {
                        assertTrue(source, attr.key in setOf("href", "colspan", "rowspan"))
                        if (attr.key == "href") assertNotNull(result.destination(attr.value))
                    }
                }
            }
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
            for (href in listOf("https://bank.example@evil.example/", "https://bank.example\u202Eelpmaxe.live")) {
                val doc = LocalHtmlPolicy.sanitize("<a href=\"$href\">bank</a>")
                assertTrue(doc.links.isEmpty())
                assertFalse("wn-document-link" in doc.html)
            }
        }

    @Test fun extremeDepthAndStructureAreBounded() =
        runBlocking {
            val deep = "<div>".repeat(1000) + "text" + "</div>".repeat(1000)
            val result = runCatching { LocalHtmlPolicy.sanitize(deep) }
            result.getOrNull()?.let { document ->
                for (element in Jsoup.parse(document.html).body().allElements) {
                    assertTrue(element.parents().size <= 130)
                }
            }
            assertTrue(runCatching { LocalHtmlPolicy.sanitize("<p>x</p>".repeat(21000)) }.isFailure)
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
                "<body style='color:red'><p>Notes</p></body>",
                "<html style='background:black'><body><p>Notes</p></body></html>",
            )) {
                val doc = LocalHtmlPolicy.sanitize(source)
                assertTrue(source, doc.omitted)
                val output = Jsoup.parse(doc.html)
                assertEquals(source, 0, output.body().attributesSize())
                assertFalse(source, output.selectFirst("html")!!.hasAttr("style"))
            }
        }
}
