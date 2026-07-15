package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerAssetAccountGuardTest {
    @Test
    fun acceptsOnlyTheSameAccountAndCacheEpoch() {
        assertTrue(shouldAcceptStickerAssetResult("acct-a", 4, "acct-a", 4))
        assertFalse(shouldAcceptStickerAssetResult("acct-a", 4, "acct-b", 4))
        assertFalse(shouldAcceptStickerAssetResult("acct-a", 4, null, 4))
        assertFalse(shouldAcceptStickerAssetResult("acct-a", 4, "acct-a", 5))
    }
}
