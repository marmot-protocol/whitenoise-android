package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.share.InboundShareImportState
import dev.ipf.whitenoise.android.share.PendingShareRequestStore
import dev.ipf.whitenoise.android.share.SerializedPendingShareRequestStore
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Who answers an inbound share, and how often. The Activity drops its share intent on the first acknowledgement, so
 * a second one for the same request, or none, is what these tests would catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellShareRouteContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)
    private val acknowledged = CopyOnWriteArrayList<String>()

    /**
     * A share aimed at one chat's shortcut opens that chat without showing the picker, and the request is
     * acknowledged exactly once.
     */
    @Test
    fun aShareToAChatShortcutOpensThatChatAndIsAcknowledgedOnce() {
        withShareRoutedShell { fixture, holder ->
            val shortcut = conversationShortcutId(ConversationTimelineTestIds.ACCOUNT_REF, fixture.target.groupIdHex)
            composeRule.runOnIdle { holder.acceptInboundShareRequest(shareRequest(shortcut)) }

            shell.awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
            shell.settle()

            assertEquals(listOf(REQUEST_ID), acknowledged.toList())
            assertNull("a direct share never shows the picker", holder.pendingShareRequest.value)
            assertNull(holder.inboundShareRequest.value)
            assertNull("staging must have succeeded, with nothing to report", fixture.appState.toast)
        }
    }

    /**
     * A share with no shortcut becomes the picker's request and is acknowledged exactly once, with no chat opened.
     * Closing the picker afterwards does not acknowledge it again.
     */
    @Test
    fun aShareWithoutAShortcutBecomesThePickerRequestAndIsAcknowledgedOnce() {
        withShareRoutedShell { _, holder ->
            composeRule.runOnIdle { holder.acceptInboundShareRequest(shareRequest(shortcutId = null)) }

            shell.awaitCondition { holder.pendingShareRequest.value?.requestId == REQUEST_ID }
            shell.settle()

            assertEquals(listOf(REQUEST_ID), acknowledged.toList())
            assertNull(holder.selectedChat.value)
            assertNull(holder.inboundShareRequest.value)

            Espresso.pressBack()
            shell.awaitCondition { holder.pendingShareRequest.value == null }
            shell.settle()
            assertEquals("closing the picker must not acknowledge again", listOf(REQUEST_ID), acknowledged.toList())
        }
    }

    /**
     * An account switch closes the open picker, whose chats belong to the account being left, and does not
     * acknowledge its request a second time.
     */
    @Test
    fun anAccountSwitchClosesTheOpenPicker() {
        withShareRoutedShell { fixture, holder ->
            composeRule.runOnIdle { holder.acceptInboundShareRequest(shareRequest(shortcutId = null)) }
            shell.awaitCondition { holder.pendingShareRequest.value?.requestId == REQUEST_ID }
            shell.settle()

            composeRule.runOnIdle {
                WhiteNoiseAppState::class.java
                    .getDeclaredMethod("setActiveAccountRef", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(fixture.appState, OTHER_ACCOUNT_REF)
            }
            shell.awaitCondition { holder.pendingShareRequest.value == null }
            shell.settle()

            assertEquals(listOf(REQUEST_ID), acknowledged.toList())
            assertNull(holder.selectedChat.value)
        }
    }

    /**
     * Mounts the shell on its ready surface, fed the holder's inbound share as the Activity feeds it, and records
     * every acknowledgement the shell sends back.
     */
    private fun withShareRoutedShell(exercise: (ReturnFixture, MainShellStateHolder) -> Unit) {
        val fixture = ReturnFixture()
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setPhase", AppPhase::class.java)
            .apply { isAccessible = true }
            .invoke(fixture.appState, AppPhase.Ready)
        val import = InboundShareImportState().apply { store = SerializedPendingShareRequestStore(MemoryStore()) }
        shell.withMountedShell(
            width = mutableStateOf(COMPACT_WIDTH),
            fixture = fixture,
            shell = { holder ->
                MainShell(
                    appState = fixture.appState,
                    stateHolder = holder,
                    inboundShareRequest = holder.inboundShareRequest.value,
                    shareImport = import,
                    onShareRequestHandled = { handled ->
                        acknowledged += handled.requestId
                        holder.acknowledgeInboundShareRequest(handled.requestId)
                    },
                )
            },
            exercise = exercise,
        )
    }

    /** A text share whose private import has already finished, aimed at [shortcutId] when there is one. */
    private fun shareRequest(shortcutId: String?) =
        ShareRequest(
            payload = SharePayload(SHARED_TEXT, emptyList(), "text/plain", importReady = true),
            shortcutId = shortcutId,
            requestId = REQUEST_ID,
        )

    /** A recovery store that keeps its one entry in memory. */
    internal class MemoryStore : PendingShareRequestStore {
        private var entry: ShareRequest? = null

        /** Keeps [request] as the one entry. */
        override fun save(request: ShareRequest): Boolean {
            entry = request
            return true
        }

        /** The entry, when it is the one asked for. */
        override fun load(requestId: String): ShareRequest? = entry?.takeIf { it.requestId == requestId }

        /** Drops the entry when it is the one named. */
        override fun remove(requestId: String) {
            if (entry?.requestId == requestId) entry = null
        }

        /** Drops the entry. */
        override fun clear() {
            entry = null
        }
    }

    private companion object {
        val COMPACT_WIDTH = 660.dp
        const val OTHER_ACCOUNT_REF = "other-contract-account"
        const val REQUEST_ID = "share-1"
        const val SHARED_TEXT = "shared straight to one chat"
    }
}
