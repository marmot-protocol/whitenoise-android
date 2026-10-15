package dev.ipf.whitenoise.android.state

import androidx.compose.material3.SnackbarHostState
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.TopNoticeClaim
import dev.ipf.whitenoise.android.ui.common.TopNoticeHost
import dev.ipf.whitenoise.android.ui.common.topNoticeFor
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransientNoticeOwnershipTest {
    /** Clearing a returned conversation notice never retires a newer replacement. */
    @Test
    fun returnedConversationNoticeCannotClearANewerSurfaceNotice() {
        val state = emptyAppState()
        val recovery = state.presentConversationTransient("account", "group", R.string.translation_recovery_notice)
        assertSame(recovery, state.transientNotice)
        state.presentTransient("New unrelated notice")
        val replacement = state.transientNotice
        state.clearTransientNotice(recovery)
        assertSame(replacement, state.transientNotice)
        state.clearTransientNotice(replacement)
        assertNull(state.transientNotice)
    }

    /** The newest live slot owns the app's banners, and leaving hands them back to the slot below (#3312). */
    @Test
    fun theNewestSlotOwnsTheBannersAndLeavingHandsThemBack() {
        val host = TopNoticeHost(SnackbarHostState(), notice = { null })
        val shell = TopNoticeClaim()
        val conversation = TopNoticeClaim()
        val modal = TopNoticeClaim()

        host.claim(shell)
        host.claim(conversation)
        assertTrue(host.owns(conversation))
        assertFalse(host.owns(shell))

        host.claim(modal)
        assertTrue("a modal takes the banners", host.owns(modal))
        assertFalse(host.owns(conversation))

        host.release(modal)
        assertTrue("closing the modal hands them back", host.owns(conversation))
        host.release(conversation)
        assertTrue(host.owns(shell))
    }

    /** Under app lock no slot owns the banners, so no stale text or action can show. */
    @Test
    fun appLockLeavesEverySlotWithoutTheBanners() {
        var locked = false
        val host = TopNoticeHost(SnackbarHostState(), notice = { null }, presentationAllowed = { !locked })
        val slot = TopNoticeClaim()
        host.claim(slot)
        assertTrue(host.owns(slot))

        locked = true

        assertFalse(host.owns(slot))
    }

    /** A conversation-scoped notice shows only in the owning slot of its own account and conversation. */
    @Test
    fun aScopedNoticeShowsOnlyInsideItsOwnConversation() {
        val state = emptyAppState()
        val scoped = state.presentConversationTransient("account", "group-a", R.string.translation_recovery_notice)
        val here = ConversationNoticeDestination("account", "GROUP-A")
        val elsewhere = ConversationNoticeDestination("account", "group-b")
        val otherAccount = ConversationNoticeDestination("other", "group-a")

        assertSame(scoped, topNoticeFor(scoped, owner = true, scope = here))
        assertNull(topNoticeFor(scoped, owner = true, scope = elsewhere))
        assertNull(topNoticeFor(scoped, owner = true, scope = otherAccount))
        assertNull("the shell has no conversation scope", topNoticeFor(scoped, owner = true, scope = null))
        assertNull("a slot that does not own the banners shows nothing", topNoticeFor(scoped, owner = false, here))

        state.presentTransient("Saved")
        val global = state.transientNotice
        assertSame(global, topNoticeFor(global, owner = true, scope = elsewhere))
        assertSame(global, topNoticeFor(global, owner = true, scope = null))
    }
}
