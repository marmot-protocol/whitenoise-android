package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import dev.ipf.whitenoise.android.propertyInitializerCall
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #3780: chat-list changes that arrived while the app was in the background
 * are a resume baseline. The production list must feed that baseline to every
 * motion input gate so the first resumed frame accepts row taps.
 * Behaviour is exercised by [ChatListResumeBaselineTest].
 */
class ChatListResumeInputCoverageTest {
    private val baselineBranchSkipsCorrection =
        Regex("""if \(current\.resumeBaseline\) \{[\s\S]*?\} else if \(shouldCorrectHeadScroll""")
    private val chatsDir = "src/main/java/dev/ipf/whitenoise/android/ui/chats"
    private val screen = appModuleSource("$chatsDir/ChatsScreen.kt").functionBody("ChatsScreen")

    @Test
    fun chatsScreenFeedsTheResumeBaselineToEveryMotionGate() {
        assertTrue(
            "ChatsScreen must derive one resume baseline per composition",
            "val resumeBaseline = rememberChatListResumeBaseline()" in screen,
        )
        val headEffectStart = screen.indexOf("ChatListActiveHeadScrollEffect(")
        assertTrue("missing ChatListActiveHeadScrollEffect call", headEffectStart >= 0)
        val headEffect =
            screen.kotlinBlockFrom(
                openDelimiter = screen.indexOf('(', headEffectStart),
                description = "ChatListActiveHeadScrollEffect call",
                opening = '(',
                closing = ')',
            )
        val gatedCalls =
            mapOf(
                "head scroll correction" to headEffect,
                "head reorder input gate" to screen.propertyInitializerCall("headReorderInProgress"),
                "row placement duration" to screen.propertyInitializerCall("rowPlacementDurationMillis"),
            )
        gatedCalls.forEach { (owner, call) ->
            assertTrue("$owner must receive the resume baseline", "resumeBaseline = resumeBaseline" in call)
        }
        val placementGate = screen.propertyInitializerCall("rowPlacementInProgress")
        assertTrue(
            "the row placement gate must follow the baseline-aware duration",
            "placementDurationMillis = rowPlacementDurationMillis" in placementGate,
        )
        assertTrue(
            "a placement gate closed before the app was hidden must reset on return",
            "resumeEpoch = resumeBaseline.epoch" in placementGate,
        )
    }

    @Test
    fun zeroPlacementDurationSnapsRowsAndLeavesInputOpen() {
        val gate =
            appModuleSource("$chatsDir/ChatListRowPlacementGate.kt").functionBody("rememberChatListRowPlacementGate")
        assertTrue("a zero-duration placement must not close row input", "placementDurationMillis > 0" in gate)

        val motion = appModuleSource("$chatsDir/ChatListHeadReorderMotion.kt")
        val rowMotion = appModuleSource("$chatsDir/ChatListRowMotionDuration.kt")
        assertTrue(
            "zero-duration rows must snap instead of running a placement tween",
            "placementSpec = if (placementDurationMillis > 0) tween(placementDurationMillis) else null" in rowMotion,
        )
        assertTrue(
            "zero-duration rows must drop the item animation so inserted rows are drawn opaque at once",
            "fadeInSpec = if (placementDurationMillis > 0) tween(CHAT_LIST_MEMBERSHIP_FADE_MILLIS) else null" in rowMotion,
        )
        assertTrue(
            "the resumed head must not open the head reorder gate",
            "!resumeBaseline" in motion.functionBody("rememberChatListHeadReorderGate"),
        )
        val headEffect = motion.functionBody("ChatListActiveHeadScrollEffect")
        assertTrue(
            "a hidden head promotion must be placed before layout, not by the async collector",
            "chatListResumeHeadSnap(" in headEffect &&
                "listState.requestScrollToItem(0)" in
                appModuleSource("$chatsDir/ChatListResumeBaseline.kt").functionBody("chatListResumeHeadSnap"),
        )
        assertTrue(
            "the resume baseline must never launch the animated head correction",
            baselineBranchSkipsCorrection.containsMatchIn(headEffect),
        )
    }
}
