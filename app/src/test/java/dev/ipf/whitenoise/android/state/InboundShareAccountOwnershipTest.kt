package dev.ipf.whitenoise.android.state

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.share.SharePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InboundShareAccountOwnershipTest {
    @Test
    fun explicitChosenAccountOwnsStagedStreamsEvenWhenAnotherAccountIsActive() {
        val personal = account("personal", "a0".repeat(32))
        val work = account("work", "a1".repeat(32))
        val appState = appState(listOf(personal, work), activeAccountRef = personal.label)
        val uri = Uri.parse("content://example/shared.jpg")

        assertTrue(
            appState.stageInboundShare(
                accountRef = work.label,
                targetGroupIds = listOf("group-work"),
                payload = SharePayload(text = null, streamUris = listOf(uri), intentMimeType = "image/jpeg"),
            ),
        )

        assertNull(appState.shareStaging.consume(personal.accountIdHex, "group-work"))
        assertEquals(listOf(uri), appState.shareStaging.consume(work.accountIdHex, "group-work")?.mediaUris)
    }

    @Test
    fun unavailableOrSignedOutChosenAccountCannotReceiveStagedContent() {
        val personal = account("personal", "a0".repeat(32))
        val signedOut = account("signed-out", "a2".repeat(32), signedOut = true)
        val appState = appState(listOf(personal, signedOut), activeAccountRef = personal.label)
        val payload = SharePayload(text = "private draft", streamUris = emptyList(), intentMimeType = "text/plain")

        assertFalse(appState.stageInboundShare("missing", listOf("group"), payload))
        assertFalse(appState.stageInboundShare(signedOut.label, listOf("group"), payload))
        assertFalse(appState.stageInboundShare(personal.label, listOf(""), payload))
    }

    private fun appState(
        accounts: List<AccountSummaryFfi>,
        activeAccountRef: String,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = accounts,
            activeAccountRef = activeAccountRef,
        )

    private fun account(
        label: String,
        accountIdHex: String,
        signedOut: Boolean = false,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = signedOut,
        running = !signedOut,
    )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
