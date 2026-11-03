package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageFullScreenBody
import dev.ipf.whitenoise.android.ui.conversation.messages.SwipeTestBubbleHost
import dev.ipf.whitenoise.android.ui.conversation.messages.swipeTestSurface
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Registered gate for #3562: every caller of the shared Markdown table primitive sizes columns from the
 * visible cells' rendered text and gives the header and every row the same column boundaries. The
 * assertions read real text and cell bounds from the production renderer, not modifier source.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MarkdownTableColumnSizingCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A real message bubble gives the short ID column its text width and the paragraph column the rest. */
    @Test
    fun messageBubbleGivesTheShortColumnItsTextWidthAndTheLongColumnTheRest() {
        val fixture =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = false, media = false)
        val record = fixture.item.record.copy(plaintext = ID_DESCRIPTION_SOURCE, contentTokens = idDescriptionDoc())
        val surface = fixture.copy(item = fixture.item.copy(record = record, projected = null))
        composeRule.setContent { SwipeTestBubbleHost(surface = surface, collapseLongMessages = false) }
        composeRule.waitForIdle()

        assertShortColumnYieldsToTheLongColumn()
    }

    /** The full-screen reader shares the same allocation as the bubble. */
    @Test
    fun fullScreenReaderGivesTheShortColumnItsTextWidthAndTheLongColumnTheRest() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageFullScreenBody(
                    body = ID_DESCRIPTION_SOURCE,
                    markdownDocument = idDescriptionDoc(),
                    mentionDisplayName = null,
                    onNostrProfileTap = null,
                    onCopyMarkdownLink = {},
                    modifier = Modifier.width(300.dp),
                )
            }
        }
        composeRule.waitForIdle()

        assertShortColumnYieldsToTheLongColumn()
    }

    /** A wider header, a wider later body cell, ragged and empty rows all share one column model. */
    @Test
    fun headerAndEveryRowShareOneColumnModelAcrossRaggedRows() {
        renderBody(raggedTable(), width = 320.dp)

        val columns =
            listOf(
                listOf("Identifier", "x1", "x2", "x3"),
                listOf("Qty", "y1", "a much wider later body cell", "y3"),
                listOf("Price", "z1", "z2"),
            )
        columns.forEach { column ->
            val first = bounds(column.first())
            column.drop(1).forEach { text ->
                val cell = bounds(text)
                assertEquals("$text shares the left edge of ${column.first()}", first.left, cell.left, EDGE_TOLERANCE)
                assertEquals("$text shares the width of ${column.first()}", first.width, cell.width, EDGE_TOLERANCE)
            }
        }
        // The wider header and the wider later body cell each set their own column's demand.
        assertTrue(bounds("Identifier").width < bounds("a much wider later body cell").width)
        assertTrue(bounds("Price").width < bounds("a much wider later body cell").width)
        // Right alignment still ends each line at the column's end edge.
        val price = textLayout("z1")
        assertEquals(price.size.width.toFloat(), price.getLineRight(0), EDGE_TOLERANCE)
        // One rule under the header and one between each adjacent body-row pair.
        composeRule.onAllNodesWithTag(MARKDOWN_TABLE_DIVIDER_TAG, useUnmergedTree = true).assertCountEquals(3)
    }

    /** Large RTL text, inline styling and an unbroken token stay inside a narrow host and mirror columns. */
    @Test
    fun narrowLargeRtlTableStaysInsideItsHost() {
        renderBody(inlineTable(), width = 160.dp, fontScale = 2f, rtl = true)

        val host = composeRule.onNodeWithTag(HOST_TAG).fetchSemanticsNode().boundsInRoot
        listOf("Key", "Notes", "id", "emphasis", "link", UNBROKEN).forEach { text ->
            val cell = bounds(text)
            assertTrue("$text left ${cell.left} inside ${host.left}", cell.left >= host.left - EDGE_TOLERANCE)
            assertTrue("$text right ${cell.right} inside ${host.right}", cell.right <= host.right + EDGE_TOLERANCE)
        }
        // RTL places the first column at the start (right) edge.
        assertTrue(bounds("Key").right > bounds("Notes").right)
        assertTrue(textLayout(UNBROKEN).lineCount > 1)
    }

    /** Narrowing and restoring the host returns the exact same allocation, with no width oscillation. */
    @Test
    fun repeatedLayoutSettlesOnTheSameWidths() {
        var width by mutableStateOf(300.dp)
        renderBody(idDescriptionDoc().blocks.single(), widthProvider = { width })
        val original = TRACKED.map(::bounds)

        composeRule.runOnIdle { width = 200.dp }
        composeRule.waitForIdle()
        val narrowed = TRACKED.map(::bounds)
        composeRule.runOnIdle { width = 300.dp }
        composeRule.waitForIdle()
        composeRule.waitForIdle()

        assertTrue(narrowed[1].width < original[1].width)
        assertEquals(original, TRACKED.map(::bounds))
    }

    /** Changing the width measures only the bounded window and never composes hidden table content. */
    @Test
    fun widthChangesOnlyMeasureTheBoundedVisibleWindow() {
        var width by mutableStateOf(320.dp)
        val columns = MARKDOWN_MAX_TABLE_COLUMNS + 3
        val table =
            MarkdownBlockFfi.Table(
                alignments = List(columns) { MarkdownAlignmentFfi.NONE },
                header = List(columns) { cell("head-$it") },
                rows = List(MARKDOWN_MAX_CONTAINER_SIBLINGS + 40) { row -> List(columns) { cell("cell-$row-$it") } },
            )
        renderBody(table, widthProvider = { width })
        val visibleBody = MARKDOWN_MAX_TABLE_CELLS - MARKDOWN_MAX_TABLE_COLUMNS
        assertVisibleCells(visibleBody)

        composeRule.runOnIdle { width = 180.dp }
        composeRule.waitForIdle()

        assertVisibleCells(visibleBody)
        val lastRow = visibleBody / MARKDOWN_MAX_TABLE_COLUMNS
        composeRule.onNodeWithText("cell-$lastRow-${visibleBody % MARKDOWN_MAX_TABLE_COLUMNS}").assertDoesNotExist()
        composeRule.onNodeWithText("cell-0-$MARKDOWN_MAX_TABLE_COLUMNS").assertDoesNotExist()
    }

    /** Selection and speech consumers receive the reflowed layout and its new coordinates. */
    @Test
    fun selectionAndSpeechReceiveFreshGeometryAfterReflow() {
        var width by mutableStateOf(320.dp)
        val selection = mutableMapOf<String, Pair<TextLayoutResult, LayoutCoordinates>>()
        val speech = mutableMapOf<String, Pair<TextLayoutResult, LayoutCoordinates>>()
        composeRule.setContent {
            WhiteNoiseTheme {
                MarkdownMessageBody(
                    document = MarkdownDocumentFfi(idDescriptionDoc().blocks, false, byteArrayOf()),
                    modifier = Modifier.width(width),
                    onSelectableTextLayoutChanged = { _, layout, coordinates ->
                        if (layout != null && coordinates != null) {
                            selection[layout.layoutInput.text.text] = layout to coordinates
                        }
                    },
                    ttsSentenceLayoutReporter = { _, text, layout, coordinates ->
                        if (layout != null && coordinates != null) speech[text] = layout to coordinates
                    },
                )
            }
        }
        composeRule.waitForIdle()
        val wideLines = selection.getValue(DESCRIPTION_A).first.lineCount

        composeRule.runOnIdle { width = 200.dp }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            listOf(selection, speech).forEach { reports ->
                val (layout, coordinates) = reports.getValue(DESCRIPTION_A)
                val cell = bounds(DESCRIPTION_A)
                assertTrue(layout.lineCount > wideLines)
                assertEquals(cell.width, layout.size.width.toFloat(), EDGE_TOLERANCE)
                assertEquals(cell.left, coordinates.boundsInRoot().left, EDGE_TOLERANCE)
                assertEquals(cell.top, coordinates.boundsInRoot().top, EDGE_TOLERANCE)
            }
        }
    }

    /** Shared assertions for the ID/Description fixture in whichever caller rendered it. */
    private fun assertShortColumnYieldsToTheLongColumn() {
        val id = bounds("ID")
        val description = bounds("Description")
        val budget = id.width + description.width
        assertTrue("ID ${id.width} takes less than half of $budget", id.width < budget / 2)
        assertTrue("Description gains at least twice the ID width", description.width > id.width * 2)
        listOf("Q1", "Q2").forEach { text ->
            assertEquals(id.left, bounds(text).left, EDGE_TOLERANCE)
            assertEquals(id.width, bounds(text).width, EDGE_TOLERANCE)
        }
        listOf(DESCRIPTION_A, DESCRIPTION_B).forEach { text ->
            assertEquals(description.left, bounds(text).left, EDGE_TOLERANCE)
            assertEquals(description.width, bounds(text).width, EDGE_TOLERANCE)
        }
    }

    /** Requires exactly the visible header and body cells to be composed. */
    private fun assertVisibleCells(visibleBody: Int) {
        composeRule
            .onAllNodesWithText("head-", substring = true, useUnmergedTree = true)
            .assertCountEquals(MARKDOWN_MAX_TABLE_COLUMNS)
        composeRule.onAllNodesWithText("cell-", substring = true, useUnmergedTree = true).assertCountEquals(visibleBody)
    }

    /** Renders one table block through the production Markdown body inside a fixed-width tagged host. */
    private fun renderBody(
        block: MarkdownBlockFfi,
        width: Dp = 300.dp,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        widthProvider: () -> Dp = { width },
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme {
                    Box(Modifier.width(widthProvider()).testTag(HOST_TAG)) {
                        MarkdownMessageBody(document = MarkdownDocumentFfi(listOf(block), false, byteArrayOf()))
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Root bounds of the rendered text leaf showing exactly [text]. */
    private fun bounds(text: String): Rect = leaf(text).boundsInRoot

    /** The unmerged semantics node of the rendered text leaf showing exactly [text]. */
    private fun leaf(text: String) = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()

    /** The measured layout of the rendered text leaf showing exactly [text]. */
    private fun textLayout(text: String): TextLayoutResult {
        val measured = mutableListOf<TextLayoutResult>()
        val node = leaf(text)
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(measured))
        return measured.single()
    }

    private companion object {
        const val HOST_TAG = "markdown-table-host"
        const val EDGE_TOLERANCE = 1.5f
        const val DESCRIPTION_A =
            "Describes the first record in a full paragraph so that this column needs far more room than its label."
        const val DESCRIPTION_B = "A second paragraph that also wraps across several lines of the narrow bubble."
        const val UNBROKEN = "https://example.com/an-unbroken-token-without-any-spaces-at-all"
        const val ID_DESCRIPTION_SOURCE =
            "| ID | Description |\n| --- | --- |\n| Q1 | $DESCRIPTION_A |\n| Q2 | $DESCRIPTION_B |"
        val TRACKED = listOf("ID", "Description", "Q1", DESCRIPTION_A)

        /** One plain-text cell. */
        fun cell(text: String) = MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text(text)))

        /** The issue's two-column fixture: short labels beside paragraph descriptions. */
        fun idDescriptionDoc() =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Table(
                            alignments = listOf(MarkdownAlignmentFfi.NONE, MarkdownAlignmentFfi.NONE),
                            header = listOf(cell("ID"), cell("Description")),
                            rows =
                                listOf(
                                    listOf(cell("Q1"), cell(DESCRIPTION_A)),
                                    listOf(cell("Q2"), cell(DESCRIPTION_B)),
                                ),
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(),
            )

        /** Three columns with a wide header, a wide later body cell, an empty cell and a short row. */
        fun raggedTable() =
            MarkdownBlockFfi.Table(
                alignments = listOf(MarkdownAlignmentFfi.NONE, MarkdownAlignmentFfi.CENTER, MarkdownAlignmentFfi.RIGHT),
                header = listOf(cell("Identifier"), cell("Qty"), cell("Price")),
                rows =
                    listOf(
                        listOf(cell("x1"), cell("y1"), cell("z1")),
                        listOf(cell("x2"), cell("a much wider later body cell"), cell("z2")),
                        listOf(cell("x3"), cell("y3")),
                    ),
            )

        /** Emphasis, a link, inline code and an unbroken token under large RTL text. */
        fun inlineTable() =
            MarkdownBlockFfi.Table(
                alignments = listOf(MarkdownAlignmentFfi.NONE, MarkdownAlignmentFfi.NONE),
                header = listOf(cell("Key"), cell("Notes")),
                rows =
                    listOf(
                        listOf(
                            MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Code("id"))),
                            MarkdownTableCellFfi(
                                listOf(MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("emphasis")))),
                            ),
                        ),
                        listOf(
                            MarkdownTableCellFfi(
                                listOf(
                                    MarkdownInlineFfi.Link(
                                        dest = "https://example.com",
                                        title = null,
                                        children = listOf(MarkdownInlineFfi.Text("link")),
                                        classification = MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                ),
                            ),
                            cell(UNBROKEN),
                        ),
                    ),
            )
    }
}
