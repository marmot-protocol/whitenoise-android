package dev.ipf.whitenoise.android

import dev.ipf.whitenoise.android.core.StickerInputKind
import dev.ipf.whitenoise.android.core.StickerLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundStickerIntentTest {
    @Test
    fun acceptedSignalIntentIsClearedImmediately() {
        val input =
            StickerLinks.classify(
                "https://signal.art/addstickers/#" +
                    "pack_id=abcdefabcdef1234567890abcdef1234&" +
                    "pack_key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            )

        assertTrue(input?.kind == StickerInputKind.SignalImport)
        assertTrue(shouldClearInboundActivityIntent(hasNotificationTarget = false, stickerInput = input))
    }

    @Test
    fun unrelatedIntentIsNotCleared() {
        assertFalse(shouldClearInboundActivityIntent(hasNotificationTarget = false, stickerInput = null))
    }

    @Test
    fun rejectedSignalRouteIsScrubbedInsteadOfBecomingProfilePayload() {
        val rejected = "https://signal.art/addstickers/#pack_key=secret"

        assertNull(StickerLinks.classify(rejected))
        assertTrue(StickerLinks.isSignalStickerRoute(rejected))
        assertNull(
            profilePayloadDataString(
                dataString = rejected,
                stickerInput = null,
                sensitiveSignalStickerRoute = true,
            ),
        )
        assertTrue(
            shouldClearInboundActivityIntent(
                hasNotificationTarget = false,
                stickerInput = null,
                sensitiveSignalStickerRoute = true,
            ),
        )
    }

    @Test
    fun ordinaryProfilePayloadStillRoutesNormally() {
        val profile = "marmot://profile/npub1example"
        assertEquals(
            profile,
            profilePayloadDataString(
                dataString = profile,
                stickerInput = null,
                sensitiveSignalStickerRoute = false,
            ),
        )
    }
}
