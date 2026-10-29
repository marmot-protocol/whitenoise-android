package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source ownership complements the real-bubble bounds and projected-state screenshot tests. */
class MessageBubbleMetadataFooterCoverageTest {
    @Test
    fun ordinaryAndCollapsibleBodiesHaveOneWarningOwnerAfterRecoveryControls() {
        val body = source("BubbleContentBlocks.kt").substringAfter("internal fun ColumnScope.BubbleBodyFooterAndRetry")
        assertTrue("one measured warning owner", Regex("MessageBubbleMetadataFooter\\(").findAll(body).count() == 1)
        assertTrue(body.contains("warning = invalidationWarning"))
        assertFalse("no detached warning after the timestamp", body.contains("MessageBubbleInvalidationWarning("))
        assertTrue(body.contains("val recoveryInFooter = showTimestamp && showOuterMetadata"))
        assertTrue(body.contains("if (recoveryInFooter) failedSendActions()"))
        assertTrue(body.contains("if (!recoveryInFooter) failedSendActions()"))
        val footerIndex = body.indexOf("MessageBubbleMetadataFooter(")
        assertTrue(body.indexOf("retryFailedSend(item)") in 0 until footerIndex)
        assertTrue(body.indexOf("discardFailedSend(item)") in 0 until footerIndex)
        assertTrue(
            body.contains(
                "val allowInlineFooter = invalidationWarning == null && " +
                    "!(mine && item.status == MessageStatus.Failed)",
            ),
        )
        assertTrue(
            "both body branches prevent tall footers rising into text",
            Regex("allowInline = allowInlineFooter").findAll(body).count() == 2,
        )
    }

    @Test
    fun bodyFooterMeasurementRetainsTheParentWidthAndLogicalTrailingPlacement() {
        val footer = source("MessageBubbleFooter.kt")
        assertTrue(footer.contains("measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))"))
        assertTrue(footer.contains("measurables[2].measure(constraints.copy(minWidth = 0, minHeight = 0))"))
        val placement = footer.functionBody("MeasureScope.layoutMeasuredBubbleFooter")
        assertTrue(placement.contains("footer.hasSingleFooterBaseline()"))
        assertTrue(placement.contains("footer.placeRelative(geometry.x, geometry.y)"))
        val collapsed = footer.functionBody("MeasureScope.layoutCollapsedBubbleFooter")
        assertTrue(collapsed.contains("footer.hasSingleFooterBaseline()"))
    }

    @Test
    fun warningReservesMetadataWidthAndUsesTheTerminalBaselineWithoutEllipsis() {
        val footer = source("MessageBubbleMetadataFooter.kt").functionBody("MessageBubbleMetadataFooter")
        assertTrue(footer.contains("constraints.hasBoundedWidth"))
        assertTrue(footer.contains("constraints.maxWidth - metadataPlaceable.width - gap"))
        assertTrue(footer.contains("metadataPlaceable[LastBaseline]"))
        assertTrue(footer.contains("warningPlaceable[LastBaseline]"))
        assertTrue(footer.contains("FirstBaseline to firstBaseline"))
        assertTrue(footer.contains("LastBaseline to baseline"))
        assertTrue(footer.contains(".coerceIn(constraints.minHeight, constraints.maxHeight)"))
        assertTrue(footer.contains("metadataPlaceable.placeRelative(width - metadataPlaceable.width, metadataY)"))
        assertFalse(footer.contains("TextOverflow.Ellipsis"))
        assertFalse(footer.contains("maxLines = 1"))
    }

    @Test
    fun allBodyFooterCallersUseTheCurrentLogicalLastLineExtent() {
        listOf("BubbleContentBlocks.kt", "FocusedMessagePreview.kt").forEach { name ->
            val body = source(name)
            assertTrue(
                "$name must use logical occupied extent",
                body.contains("bubbleFooterLastLineWidth(layoutDirection)"),
            )
            assertTrue("$name must observe current direction", body.contains("LocalLayoutDirection.current"))
            assertFalse("$name cannot pass a physical right edge to relative placement", body.contains("getLineRight("))
        }
    }

    private fun source(name: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/$name"),
        ).first { it.exists() }.readText()
}
