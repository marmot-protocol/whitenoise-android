package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransientNoticeOwnershipTest {
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
}
