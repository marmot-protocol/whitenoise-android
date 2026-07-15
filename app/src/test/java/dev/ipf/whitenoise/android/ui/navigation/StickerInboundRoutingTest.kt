package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.core.StickerInput
import dev.ipf.whitenoise.android.core.StickerInputKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerInboundRoutingTest {
    private val input = StickerInput("30031:author:cats", StickerInputKind.Pack)

    @Test
    fun inboundStickerWaitsUntilAppLockIsHidden() {
        assertFalse(shouldRouteInboundStickerInput(input, appLockScreenVisible = true))
        assertTrue(shouldRouteInboundStickerInput(input, appLockScreenVisible = false))
    }

    @Test
    fun missingInputNeverRoutes() {
        assertFalse(shouldRouteInboundStickerInput(null, appLockScreenVisible = false))
    }
}
