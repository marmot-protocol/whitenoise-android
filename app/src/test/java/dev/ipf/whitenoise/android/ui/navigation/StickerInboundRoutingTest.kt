package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.core.InboundStickerRequest
import dev.ipf.whitenoise.android.core.StickerInput
import dev.ipf.whitenoise.android.core.StickerInputKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StickerInboundRoutingTest {
    private val input = StickerInput("30031:author:cats", StickerInputKind.Pack)
    private val request = InboundStickerRequest(input, accountRef = "alice")

    @Test
    fun inboundStickerWaitsUntilAppLockIsHidden() {
        assertEquals(
            InboundStickerRoutingDecision.Wait,
            inboundStickerRoutingDecision(request, activeAccountRef = "alice", appLockScreenVisible = true),
        )
        assertEquals(
            InboundStickerRoutingDecision.Route,
            inboundStickerRoutingDecision(request, activeAccountRef = "alice", appLockScreenVisible = false),
        )
    }

    @Test
    fun deferredStickerIsDiscardedAfterAccountSwitch() {
        assertEquals(
            InboundStickerRoutingDecision.Discard,
            inboundStickerRoutingDecision(request, activeAccountRef = "bob", appLockScreenVisible = true),
        )
    }

    @Test
    fun coldStartRequestWaitsForFirstActiveAccount() {
        val unscoped = InboundStickerRequest(input, accountRef = null)
        assertEquals(
            InboundStickerRoutingDecision.Wait,
            inboundStickerRoutingDecision(unscoped, activeAccountRef = null, appLockScreenVisible = false),
        )
        assertEquals(
            InboundStickerRoutingDecision.Route,
            inboundStickerRoutingDecision(unscoped, activeAccountRef = "alice", appLockScreenVisible = false),
        )
    }
}
