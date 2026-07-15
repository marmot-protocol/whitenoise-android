package dev.ipf.whitenoise.android

import dev.ipf.whitenoise.android.core.StickerInputKind
import dev.ipf.whitenoise.android.core.StickerLinks
import org.junit.Assert.assertFalse
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
}
