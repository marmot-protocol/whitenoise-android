package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The device's record of offered default packs is per account and goes when that account is wiped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickerDefaultPackLedgerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Recording keeps each account's offers apart, and forgetting one account leaves the other's intact. */
    @Test
    fun forgettingAnAccountDropsOnlyItsRecord() {
        val coordinates = StickerDefaultPacks.coordinates
        StickerDefaultPackLedger.forAccount(context, "alice").record(coordinates)
        StickerDefaultPackLedger.forAccount(context, "bob").record(coordinates.take(1))

        StickerDefaultPackLedger.forget(context, "alice")

        assertEquals(emptySet<String>(), StickerDefaultPackLedger.forAccount(context, "alice").offered())
        assertEquals(coordinates.take(1).toSet(), StickerDefaultPackLedger.forAccount(context, "bob").offered())
    }
}
