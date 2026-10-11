package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.notifications.ReversedListSpec
import dev.ipf.whitenoise.android.notifications.ReversedListTarget
import dev.ipf.whitenoise.android.notifications.ReversedReadingListFixture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Physical bounds, not screenshots alone, prove the production command's reversed-list destination. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationMentionNavigationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shortMentionStartsAtThePhysicalTop() {
        assertMentionTop(target = Target(80), viewportHeight = 420, padding = 12)
    }

    @Test
    fun oversizedMentionStartsAtThePhysicalTop() {
        assertMentionTop(target = Target(720), viewportHeight = 420, padding = 12)
    }

    @Test
    fun keyboardReducedViewportStillShowsTheBeginning() {
        assertMentionTop(target = Target(720), viewportHeight = 260, padding = 32)
    }

    @Test
    fun expandedComposerOverlapDoesNotShiftTheReadingTop() {
        assertMentionTop(target = Target(80), viewportHeight = 420, padding = 144, overlap = 120)
    }

    @Test
    fun tallMentionWithExpandedComposerAndRtlShowsTheBeginning() {
        assertMentionTop(target = Target(720), viewportHeight = 420, padding = 144, overlap = 120, rtl = true)
    }

    @Test
    fun farUnmeasuredMentionUsesBoundedNavigationAndFreshGeometry() {
        assertMentionTop(target = Target(720, 150), viewportHeight = 420, padding = 12)
    }

    @Test
    fun endMentionAcrossMoreThanFiftyMixedRowsStillStartsAtThePhysicalTop() {
        assertMentionTop(target = Target(720), viewportHeight = 420, padding = 12, initialIndex = 90, mixedRows = true)
    }

    @Test
    fun endMentionWithKeyboardAndComposerStillStartsAtThePhysicalTop() {
        assertMentionTop(
            target = Target(80),
            viewportHeight = 260,
            padding = 144,
            overlap = 120,
            initialIndex = 90,
            mixedRows = true,
        )
    }

    /** Runs the production mention command against the shared reversed list and asserts the physical top edge. */
    private fun assertMentionTop(
        target: Target,
        viewportHeight: Int,
        padding: Int,
        overlap: Int = 0,
        rtl: Boolean = false,
        initialIndex: Int = 0,
        mixedRows: Boolean = false,
    ) {
        val spec =
            ReversedListSpec(
                target = ReversedListTarget(heightDp = target.height, index = target.index),
                viewportHeightDp = viewportHeight,
                paddingDp = padding,
                overlapDp = overlap,
                rtl = rtl,
                initialIndex = initialIndex,
                mixedRows = mixedRows,
            )
        val fixture = ReversedReadingListFixture(composeRule)
        fixture.mount(spec) {
            coordinator.jumpToMentionReadingStart(
                targetMessageId = targetMessageId,
                resolveTargetIndex = { targetIndex },
                readLayout = this::readLayout,
            )
        }
        fixture.trigger()
        fixture.assertTargetAtPhysicalTop(spec)
    }

    private data class Target(
        val height: Int,
        val index: Int = 8,
    )
}
