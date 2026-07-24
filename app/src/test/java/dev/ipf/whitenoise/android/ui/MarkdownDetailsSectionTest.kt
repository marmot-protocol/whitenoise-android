package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownDetailsSectionTest {
    @Test
    fun extractsOwnLineSummaryAndContent() {
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("<summary>Build log</summary>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("line one"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("line two"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            )

        assertEquals("Build log", section?.summary)
        assertEquals(
            listOf(
                MarkdownInlineFfi.Text("line one"),
                MarkdownInlineFfi.SoftBreak,
                MarkdownInlineFfi.Text("line two"),
            ),
            section?.content,
        )
    }

    @Test
    fun extractsSummarySharingTheOpeningLine() {
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details><summary>Same line</summary>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("body"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            )

        assertEquals("Same line", section?.summary)
        assertEquals(listOf<MarkdownInlineFfi>(MarkdownInlineFfi.Text("body")), section?.content)
    }

    @Test
    fun missingSummaryYieldsNullSummary() {
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("no summary here"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            )

        assertNull(section?.summary)
        assertEquals(listOf<MarkdownInlineFfi>(MarkdownInlineFfi.Text("no summary here")), section?.content)
    }

    @Test
    fun styledContentSurvivesExtraction() {
        val strong = MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold")))
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("some "),
                    strong,
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            )

        assertEquals(listOf(MarkdownInlineFfi.Text("some "), strong), section?.content)
    }

    @Test
    fun tagsMatchCaseInsensitively() {
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<DETAILS>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("shouty"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</Details>"),
                ),
            )

        assertNull(section?.summary)
        assertEquals(listOf<MarkdownInlineFfi>(MarkdownInlineFfi.Text("shouty")), section?.content)
    }

    @Test
    fun nonDetailsTextPassesThrough() {
        assertNull(markdownDetailsSection(listOf(MarkdownInlineFfi.Text("just some prose"))))
        assertNull(
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("prefix <details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            ),
        )
    }

    @Test
    fun unterminatedBlockPassesThrough() {
        assertNull(
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("<summary>open</summary>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("never closed"),
                ),
            ),
        )
        assertNull(markdownDetailsSection(listOf(MarkdownInlineFfi.Text("<details>"))))
    }

    @Test
    fun closingTagMustEndTheParagraph() {
        assertNull(
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("trailing prose"),
                ),
            ),
        )
    }
}
