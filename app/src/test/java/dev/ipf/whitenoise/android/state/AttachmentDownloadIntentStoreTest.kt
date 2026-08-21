package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentDownloadIntentStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences("attachment-download-intent-test", Context.MODE_PRIVATE)
    }

    @Before
    fun reset() {
        preferences.edit().clear().commit()
    }

    @Test
    fun stoppedAutomaticBacklogSurvivesRecreationUntilExplicitRestart() {
        AttachmentDownloadIntentStore(preferences).pauseAutomatic(ACCOUNT_A)

        val recreated = AttachmentDownloadIntentStore(preferences)
        assertTrue(recreated.isAutomaticPaused(ACCOUNT_A))
        assertFalse(recreated.isAutomaticPaused(ACCOUNT_B))

        recreated.restartAutomatic(ACCOUNT_A)
        assertFalse(AttachmentDownloadIntentStore(preferences).isAutomaticPaused(ACCOUNT_A))
    }

    @Test
    fun promotedIdentityRemainsDistinctFromAutomaticAccountStop() {
        val store = AttachmentDownloadIntentStore(preferences)
        store.setInteractive(REQUEST_A, interactive = true)
        store.pauseAutomatic(ACCOUNT_A)

        val recreated = AttachmentDownloadIntentStore(preferences)
        assertTrue(recreated.isInteractive(REQUEST_A))
        assertFalse(recreated.isInteractive(REQUEST_B))
        assertTrue(recreated.isAutomaticPaused(ACCOUNT_A))
    }

    @Test
    fun persistedOpenIntentIsConsumedExactlyOnceAndScopedToIdentity() {
        AttachmentDownloadIntentStore(preferences).apply {
            markOpenIntent(REQUEST_A)
            markOpenIntent(REQUEST_A)
        }
        val recreated = AttachmentDownloadIntentStore(preferences)

        assertTrue(recreated.hasOpenIntent(REQUEST_A))
        assertFalse(recreated.hasOpenIntent(REQUEST_B))
        assertTrue(recreated.consumeOpenIntent(REQUEST_A))
        assertFalse(recreated.consumeOpenIntent(REQUEST_A))
        assertFalse(AttachmentDownloadIntentStore(preferences).hasOpenIntent(REQUEST_A))
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        val REQUEST_A =
            AttachmentTransferRequest(
                accountRef = ACCOUNT_A,
                groupIdHex = "ab".repeat(32),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
        val REQUEST_B = REQUEST_A.copy(accountRef = ACCOUNT_B)
    }
}
