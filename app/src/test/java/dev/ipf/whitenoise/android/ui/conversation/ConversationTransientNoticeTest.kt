package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.TRANSIENT_NOTICE_DURATION_MILLIS
import dev.ipf.whitenoise.android.ui.TransientNoticeTimeoutEffect
import dev.ipf.whitenoise.android.ui.common.CONVERSATION_TRANSIENT_NOTICE_TAG
import dev.ipf.whitenoise.android.ui.common.GLOBAL_TRANSIENT_NOTICE_TAG
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeHost
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeScope
import dev.ipf.whitenoise.android.ui.common.TopNoticeHost
import dev.ipf.whitenoise.android.ui.common.TopNoticeSlot
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationTransientNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var timelineState: LazyListState

    @Test
    fun otherConversationDoesNotRenderScopedNoticeOrMoveHeader() {
        val visibleGroup = mutableStateOf(GROUP_B)
        val notice = mutableStateOf<TransientNotice?>(null)
        renderHarness(visibleGroup, notice)
        val headerBefore = headerBounds()

        composeRule.runOnUiThread {
            notice.value = scopedNotice(GROUP_A)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertDoesNotExist()
        assertEquals(headerBefore, headerBounds())
    }

    @Test
    fun originatingConversationRendersNoticeBelowStableHeader() {
        val notice = mutableStateOf<TransientNotice?>(null)
        renderHarness(mutableStateOf(GROUP_A), notice)
        val headerBefore = headerBounds()
        val initialIndex = timelineState.firstVisibleItemIndex
        val initialOffset = timelineState.firstVisibleItemScrollOffset

        composeRule.runOnUiThread {
            notice.value = scopedNotice(GROUP_A)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertIsDisplayed()
        val noticeBounds =
            composeRule
                .onNodeWithTag(CONVERSATION_TRANSIENT_NOTICE_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(headerBefore, headerBounds())
        assertEquals(headerBefore.bottom, noticeBounds.top, POSITION_TOLERANCE)
        val daySeparatorBounds =
            composeRule
                .onNodeWithTag(DAY_SEPARATOR_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(noticeBounds.bottom <= daySeparatorBounds.top)
        assertEquals(initialIndex, timelineState.firstVisibleItemIndex)
        assertEquals(initialOffset, timelineState.firstVisibleItemScrollOffset)

        composeRule.runOnUiThread {
            notice.value = null
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertDoesNotExist()
        assertEquals(initialIndex, timelineState.firstVisibleItemIndex)
        assertEquals(initialOffset, timelineState.firstVisibleItemScrollOffset)
    }

    /** The conversation's row shows a delivered scoped notice and clears it when its timeout retires it. */
    @Test
    fun deliveredNoticeClearsAfterTransientWindow() {
        composeRule.mainClock.autoAdvance = false
        val notice = mutableStateOf<TransientNotice?>(scopedNotice(GROUP_A))
        composeRule.setContent {
            TransientNoticeTimeoutEffect(notice.value) { delivered ->
                if (notice.value === delivered) notice.value = null
            }
            WhiteNoiseTheme {
                ConversationTransientNoticeLayout(
                    notice = notice.value,
                    accountRef = ACCOUNT,
                    groupIdHex = GROUP_A,
                ) {}
            }
        }
        composeRule.onNodeWithText(NOTICE_TEXT).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(TRANSIENT_NOTICE_DURATION_MILLIS + 100L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertDoesNotExist()
    }

    /** Without a slot of its own below a bar, a destination receives a global banner at the shell's top. */
    @Test
    fun globalNoticeSitsAboveTheDestinationWhoseBottomStaysPut() {
        val notice = mutableStateOf<TransientNotice?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                ShellTransientNoticeLayout(notice = notice.value) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(CONTENT_TAG),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag(HEADER_TAG),
                        )
                    }
                }
            }
        }
        val contentBefore = composeRule.onNodeWithTag(CONTENT_TAG).fetchSemanticsNode().boundsInRoot

        composeRule.runOnUiThread {
            notice.value = TransientNotice(id = 3L, title = AppText.Plain("Global confirmation"))
        }
        composeRule.waitForIdle()

        val noticeBounds =
            composeRule
                .onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val contentAfter = composeRule.onNodeWithTag(CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(noticeBounds.bottom, contentAfter.top, POSITION_TOLERANCE)
        assertEquals(contentBefore.bottom, contentAfter.bottom, POSITION_TOLERANCE)
    }

    /**
     * The open conversation owns the app's banners: a global confirmation and a snackbar both render once, in the
     * row below its header and playback strip, while the shell's own slot renders nothing (#3312).
     */
    @Test
    fun theConversationRowIsTheOnlyHostOfGlobalBannersWhileItIsOpen() {
        val fixture = renderHostedConversation()
        val headerBefore = headerBounds()

        composeRule.runOnUiThread {
            fixture.notice.value = TransientNotice(id = 4L, title = AppText.Plain(GLOBAL_TEXT))
        }
        composeRule.waitForIdle()

        val nodes = composeRule.onAllNodesWithTag(GLOBAL_TRANSIENT_NOTICE_TAG).fetchSemanticsNodes()
        assertEquals("one presentation per notice", 1, nodes.size)
        val strip = composeRule.onNodeWithTag(PLAYBACK_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(headerBefore, headerBounds())
        assertEquals(strip.bottom, nodes.single().boundsInRoot.top, POSITION_TOLERANCE)
    }

    /** A banner appearing, a snackbar arriving and both clearing leave the composer exactly where it was. */
    @Test
    fun theComposerKeepsItsBoundsWhileBannersAppearClearAndAreDismissed() {
        val fixture = renderHostedConversation()
        val composerBefore = composerBounds()
        val transcriptBefore = composeRule.onNodeWithTag(TRANSCRIPT_TAG).fetchSemanticsNode().boundsInRoot

        composeRule.runOnUiThread {
            fixture.notice.value = scopedNotice(GROUP_A)
        }
        composeRule.waitForIdle()
        assertEquals(composerBefore, composerBounds())
        val transcriptWithNotice = composeRule.onNodeWithTag(TRANSCRIPT_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the transcript absorbs the row", transcriptWithNotice.top > transcriptBefore.top)
        assertEquals(transcriptBefore.bottom, transcriptWithNotice.bottom, POSITION_TOLERANCE)

        composeRule.runOnUiThread { fixture.showSnackbar(SNACKBAR_TEXT) }
        composeRule.onNodeWithText(SNACKBAR_TEXT).assertIsDisplayed()
        assertEquals(composerBefore, composerBounds())

        composeRule.runOnUiThread {
            fixture.notice.value = null
            fixture.host.snackbarHostState.currentSnackbarData
                ?.dismiss()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SNACKBAR_TEXT).assertDoesNotExist()
        assertEquals(composerBefore, composerBounds())
        assertEquals(transcriptBefore, composeRule.onNodeWithTag(TRANSCRIPT_TAG).fetchSemanticsNode().boundsInRoot)
    }

    /**
     * A modal over the conversation, such as a profile or a full-screen viewer, takes the banners while it is open
     * and hands them back when it closes. Production modals compose inside the routed conversation's scope, so a
     * notice scoped to this conversation shows in the modal's own slot too, while one scoped to another chat never
     * renders anywhere over this conversation.
     */
    @Test
    fun aModalTakesTheBannersAndHandsThemBack() {
        val fixture = renderHostedConversation()
        composeRule.runOnUiThread {
            fixture.notice.value = TransientNotice(id = 5L, title = AppText.Plain(GLOBAL_TEXT))
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread { fixture.modalOpen.value = true }
        composeRule.waitForIdle()
        val inModal = composeRule.onAllNodesWithTag(GLOBAL_TRANSIENT_NOTICE_TAG).fetchSemanticsNodes()
        assertEquals(1, inModal.size)
        val modal = composeRule.onNodeWithTag(MODAL_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("the modal slot presents it", modal.top, inModal.single().boundsInRoot.top, POSITION_TOLERANCE)

        composeRule.runOnUiThread { fixture.notice.value = scopedNotice(GROUP_A) }
        composeRule.waitForIdle()
        val scopedInModal = composeRule.onAllNodesWithTag(CONVERSATION_TRANSIENT_NOTICE_TAG).fetchSemanticsNodes()
        assertEquals("the modal shows this conversation's notice once", 1, scopedInModal.size)
        assertEquals("in its own slot", modal.top, scopedInModal.single().boundsInRoot.top, POSITION_TOLERANCE)

        composeRule.runOnUiThread { fixture.notice.value = scopedNotice(GROUP_B) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(NOTICE_TEXT).assertDoesNotExist()

        composeRule.runOnUiThread {
            fixture.notice.value = scopedNotice(GROUP_A)
            fixture.modalOpen.value = false
        }
        composeRule.waitForIdle()
        val handedBack = composeRule.onNodeWithTag(CONVERSATION_TRANSIENT_NOTICE_TAG).assertIsDisplayed()
        val strip = composeRule.onNodeWithTag(PLAYBACK_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(strip.bottom, handedBack.fetchSemanticsNode().boundsInRoot.top, POSITION_TOLERANCE)
    }

    /** Under app lock no slot presents anything, so a locked screen exposes no stale banner text or action. */
    @Test
    fun appLockHidesEveryBanner() {
        val fixture = renderHostedConversation()
        composeRule.runOnUiThread {
            fixture.notice.value = TransientNotice(id = 6L, title = AppText.Plain(GLOBAL_TEXT))
            fixture.showSnackbar(SNACKBAR_TEXT)
        }
        composeRule.onNodeWithText(GLOBAL_TEXT).assertIsDisplayed()

        composeRule.runOnUiThread { fixture.locked.value = true }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(GLOBAL_TEXT).assertDoesNotExist()
        composeRule.onNodeWithText(SNACKBAR_TEXT).assertDoesNotExist()
    }

    /** One hosted shell around one conversation with a header, playback strip, transcript and composer. */
    private class HostedConversation(
        val host: TopNoticeHost,
        val notice: MutableState<TransientNotice?>,
        val modalOpen: MutableState<Boolean>,
        val locked: MutableState<Boolean>,
        private val scope: CoroutineScope,
    ) {
        /** Queues one plain snackbar on the app's host state. */
        fun showSnackbar(message: String) {
            scope.launch { host.snackbarHostState.showSnackbar(message) }
        }
    }

    /** Renders the production shell and conversation slots under one top-notice host, as the app root provides. */
    private fun renderHostedConversation(): HostedConversation {
        val notice = mutableStateOf<TransientNotice?>(null)
        val modalOpen = mutableStateOf(false)
        val locked = mutableStateOf(false)
        val host =
            TopNoticeHost(
                snackbarHostState = SnackbarHostState(),
                notice = { notice.value },
                presentationAllowed = { !locked.value },
            )
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalTopNoticeHost provides host) {
                WhiteNoiseTheme {
                    ShellTransientNoticeLayout(notice = notice.value) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(56.dp).testTag(HEADER_TAG))
                            Box(Modifier.fillMaxWidth().height(40.dp).testTag(PLAYBACK_TAG))
                            ConversationTransientNoticeLayout(
                                notice = notice.value,
                                accountRef = ACCOUNT,
                                groupIdHex = GROUP_A,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            ) {
                                Box(Modifier.fillMaxSize().testTag(TRANSCRIPT_TAG))
                            }
                            Box(Modifier.fillMaxWidth().height(72.dp).testTag(COMPOSER_TAG))
                        }
                        if (modalOpen.value) {
                            // Production modals compose inside MainShell's scope for the routed conversation.
                            CompositionLocalProvider(
                                LocalTopNoticeScope provides ConversationNoticeDestination(ACCOUNT, GROUP_A),
                            ) {
                                Column(Modifier.fillMaxSize().testTag(MODAL_TAG)) { TopNoticeSlot() }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return HostedConversation(host, notice, modalOpen, locked, scope)
    }

    /** Bounds of the fixture composer. */
    private fun composerBounds() = composeRule.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot

    private fun renderHarness(
        visibleGroupId: State<String>,
        notice: State<TransientNotice?>,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag(HEADER_TAG),
                    )
                    ConversationTransientNoticeLayout(
                        notice = notice.value,
                        accountRef = ACCOUNT,
                        groupIdHex = visibleGroupId.value,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        timelineState =
                            rememberLazyListState(
                                initialFirstVisibleItemIndex = 1,
                                initialFirstVisibleItemScrollOffset = 8,
                            )
                        LazyColumn(
                            state = timelineState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item {
                                Text(
                                    "Earlier message",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                            item {
                                Text(
                                    "Visible message before separator",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                            item {
                                Box(Modifier.fillMaxWidth().testTag(DAY_SEPARATOR_TAG)) {
                                    DaySeparator("Today")
                                }
                            }
                            items(12) { index ->
                                Text(
                                    "Visible message $index",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun headerBounds() = composeRule.onNodeWithTag(HEADER_TAG).fetchSemanticsNode().boundsInRoot

    private fun scopedNotice(groupIdHex: String) =
        TransientNotice(
            id = 1L,
            title = AppText.Plain(NOTICE_TEXT),
            conversation = ConversationNoticeDestination(ACCOUNT, groupIdHex),
        )

    private companion object {
        const val ACCOUNT = "account-a"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
        const val HEADER_TAG = "conversation-header"
        const val CONTENT_TAG = "shell-content"
        const val DAY_SEPARATOR_TAG = "conversation-day-separator"
        const val NOTICE_TEXT = "Admin removed"
        const val GLOBAL_TEXT = "Invitation accepted"
        const val SNACKBAR_TEXT = "Couldn't send message"
        const val PLAYBACK_TAG = "conversation-playback"
        const val TRANSCRIPT_TAG = "conversation-transcript"
        const val COMPOSER_TAG = "conversation-composer"
        const val MODAL_TAG = "conversation-modal"
        const val POSITION_TOLERANCE = 0.5f
    }
}
