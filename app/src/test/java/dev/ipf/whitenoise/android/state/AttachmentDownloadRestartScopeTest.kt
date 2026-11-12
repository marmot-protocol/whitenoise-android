package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Resuming the active account's backlog through the app state leaves other accounts' cancels in place. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentDownloadRestartScopeTest {
    private lateinit var preferences: SharedPreferences

    /** Uses private preferences so seeded cancels reach the app state's own intent store. */
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        preferences =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences("attachment-restart-scope-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    /** Restores the shared dispatcher after the scenario. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun restartingTheActiveAccountKeepsAnotherAccountsCancel() {
        val seeded = AttachmentDownloadIntentStore(preferences)
        seeded.suppressAutomatic(REQUEST_A)
        seeded.suppressAutomatic(REQUEST_B)
        val appState =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext<Context>(),
                draftStore = DraftStore(EmptyDraftPersistence),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = ACCOUNT_A,
                preferences = preferences,
            )

        appState.restartAutomaticAttachmentDownloads()

        assertFalse(appState.automaticAttachmentDownloadSuppressed(REQUEST_A))
        assertTrue("another account's cancel survives", appState.automaticAttachmentDownloadSuppressed(REQUEST_B))
    }

    /** Keeps the fixture independent of persisted drafts and native initialization. */
    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        val REQUEST_A =
            AttachmentTransferRequest(
                accountRef = ACCOUNT_A,
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
        val REQUEST_B = REQUEST_A.copy(accountRef = "account-b")
    }
}
