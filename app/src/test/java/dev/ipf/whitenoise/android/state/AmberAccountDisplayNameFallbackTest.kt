package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AmberAccountDisplayNameFallbackTest {
    @Test
    fun noMetadataHexLabelRendersShortNpubWithoutCreatingProfileName() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        var profileRefreshRequests = 0
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = listOf(amberAccountSummary()),
                activeAccountRef = ACCOUNT_HEX,
                profileReader = { null },
                profileDisplayNameReader = { null },
                profileRefreshRequest = { profileRefreshRequests += 1 },
            )
        seedNpub(appState, ACCOUNT_HEX, CANONICAL_NPUB)

        val displayName = appState.networkDisplayName(ACCOUNT_HEX)
        val expectedShortNpub = IdentityFormatter.short(CANONICAL_NPUB, prefix = 10, suffix = 8)

        assertEquals(expectedShortNpub, displayName)
        assertNotEquals(ACCOUNT_HEX, displayName)
        assertEquals(1, profileRefreshRequests)
    }

    private fun amberAccountSummary(): AccountSummaryFfi =
        AccountSummaryFfi(
            label = ACCOUNT_HEX,
            accountIdHex = ACCOUNT_HEX,
            localSigning = false,
            externalSigning = true,
            signedOut = false,
            running = true,
        )

    private fun seedNpub(
        appState: WhiteNoiseAppState,
        accountIdHex: String,
        npub: String,
    ) {
        val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
        field.isAccessible = true
        val cache = field.get(appState) as BoundedNpubCache
        cache.put(accountIdHex, npub)
    }

    private companion object {
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val CANONICAL_NPUB = "npub1qy352hw5xrsq5k6x5t5vnpqx4lhfv3q8jqk9x0h5q6x5t5vnpq"
    }
}
