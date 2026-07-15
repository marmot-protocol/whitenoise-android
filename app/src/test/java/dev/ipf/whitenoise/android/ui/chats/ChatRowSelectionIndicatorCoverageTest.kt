package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatRowSelectionIndicatorCoverageTest {
    @Test
    fun selectionIndicatorReplacesTimestampAndBadgesInTrailingContent() {
        val source = chatRowSource().readText()
        val trailingContent =
            source.requiredSection(
                start = "trailingContent = {",
                end = "\n            },\n        )",
            )
        val branchSeparator = "\n                } else {\n                    Column"
        val selectionContent = trailingContent.substringBefore(branchSeparator)
        val normalContent = trailingContent.substringAfter(branchSeparator)
        val selectionOverlay = source.substringAfter(trailingContent)

        assertTrue("selection mode must branch inside the trailing slot", "if (selectionMode) {" in selectionContent)
        assertTrue("selected rows must show a filled checkmark", "Icons.Default.CheckCircle" in selectionContent)
        assertTrue("unselected rows must keep an outlined selection affordance", "Icons.Default.RadioButtonUnchecked" in selectionContent)
        assertFalse("selection content must replace, not retain, the timestamp", "rememberedRelativeTime" in selectionContent)
        assertFalse("selection content must replace, not retain, badges", "Badge" in selectionContent)

        assertTrue("normal rows must retain their timestamp", "rememberedRelativeTime" in normalContent)
        assertTrue("normal invited rows must retain their badge", "item.group.pendingConfirmation" in normalContent)
        assertTrue("normal unread rows must retain their badge", "rowHasUnread" in normalContent)
        assertFalse("the full-row tint overlay must not draw another checkmark", "Icons.Default.CheckCircle" in selectionOverlay)
    }

    private fun chatRowSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatRow.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatRow.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatRow.kt source file")

    private fun String.requiredSection(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        require(startIndex >= 0) { "Missing section start: $start" }
        val endIndex = indexOf(end, startIndex + start.length)
        require(endIndex >= 0) { "Missing section end: $end" }
        return substring(startIndex, endIndex)
    }
}
