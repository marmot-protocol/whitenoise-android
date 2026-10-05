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

        assertTrue(
            appState.stageInboundShare(
                accountRef = work.label,
                targetGroupIds = listOf("", "group-text", "  "),
                payload = SharePayload(text = "private draft", streamUris = emptyList(), intentMimeType = "text/plain"),
            ),
        )
        assertNull(appState.draftStore.get(personal.label, "group-text"))
        assertEquals("private draft", appState.draftStore.get(work.label, "group-text"))
        assertNull(appState.draftStore.get(work.label, ""))
    }

    @Test
    fun unavailableOrSignedOutChosenAccountCannotReceiveStagedContent() {
        val personal = account("personal", "a0".repeat(32))
        val signedOut = account("signed-out", "a2".repeat(32), signedOut = true)
        val persistence = InMemoryDraftPersistence()
        val appState =
            appState(
                accounts = listOf(personal, signedOut),
                activeAccountRef = personal.label,
                persistence = persistence,
            )
        val payload = SharePayload(text = "private draft", streamUris = emptyList(), intentMimeType = "text/plain")

        assertFalse(appState.stageInboundShare("missing", listOf("group"), payload))
        assertTrue(persistence.isEmpty())
        assertFalse(appState.stageInboundShare(signedOut.label, listOf("group"), payload))
        assertTrue(persistence.isEmpty())
        assertFalse(appState.stageInboundShare(personal.label, listOf(""), payload))
        assertTrue(persistence.isEmpty())
    }

    /** Duplicate recipient ids cannot stage the same payload into one draft twice. */
    @Test
    fun duplicateRecipientIdsStageThePayloadExactlyOnce() {
        val personal = account("personal", "a0".repeat(32))
        val appState = appState(listOf(personal), activeAccountRef = personal.label)

        assertTrue(
            appState.stageInboundShare(
                accountRef = personal.label,
                targetGroupIds = listOf("group", "group", "group"),
                payload = SharePayload("private draft", emptyList(), "text/plain"),
            ),
        )

        assertEquals("private draft", appState.draftStore.get(personal.label, "group"))
        assertEquals(1, appState.shareStaging.revision)
    }

    /** Changes request ownership between validation points and verifies no draft is staged from the stale picker. */
    @Test
    fun supersededPickerCannotCommitAfterProviderPreparation() =
        kotlinx.coroutines.runBlocking {
            val personal = account("personal", "a0".repeat(32))
            val state = appState(listOf(personal), personal.label)
            var validations = 0
            assertFalse(
                state.stageInboundShareForFirstFrame(
                    personal.label,
                    listOf("chat"),
                    SharePayload("private draft", emptyList(), "text/plain"),
                ) { ++validations == 1 },
            )
            assertNull(state.draftStore.get(personal.label, "chat"))
            assertNull(state.shareStaging.consume(personal.accountIdHex, "chat"))
        }

    /** Signs out the chosen account at final validation; the prepared request must not enter its draft. */
    @Test
    fun signOutAtCommitKeepsTheShareRecoverableWithoutStaging() =
        kotlinx.coroutines.runBlocking {
            val personal = account("personal", "a0".repeat(32))
            val accounts = mutableListOf(personal)
            val state = appState(accounts, personal.label)
            var validations = 0
            assertFalse(
                state.stageInboundShareForFirstFrame(
                    personal.label,
                    listOf("chat"),
                    SharePayload("private draft", emptyList(), "text/plain"),
                ) {
                    if (++validations > 1) {
                        accounts[0] = account(personal.label, personal.accountIdHex, signedOut = true)
                    }
                    true
                },
            )
            assertNull(state.draftStore.get(personal.label, "chat"))
        }

    private fun appState(
        accounts: List<AccountSummaryFfi>,
        activeAccountRef: String,
        persistence: InMemoryDraftPersistence = InMemoryDraftPersistence(),
    ): WhiteNoiseAppState {
        val draftStore = DraftStore(persistence)
        return WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = draftStore,
            accountIdHexResolver = { null },
            accounts = accounts,
            activeAccountRef = activeAccountRef,
            inboundShareTextStager = draftStore::mergeText,
        )
    }

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
        private val values = mutableMapOf<String, String>()

        override fun read(): Map<String, String> = values.toMap()

        override fun write(
            key: String,
            value: String?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }

        fun isEmpty(): Boolean = values.isEmpty()
    }
}
