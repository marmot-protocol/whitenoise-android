package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentDownloadIntentStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences("attachment-download-intent-test", Context.MODE_PRIVATE)
    }

    @Before
    fun reset() {
        AttachmentDownloadIntentStore(preferences).apply {
            abandonInstallPermissionRequest(OPEN_REQUEST_A)
            abandonInstallPermissionRequest(OPEN_REQUEST_B)
        }
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
            markOpenIntent(OPEN_REQUEST_A)
            markOpenIntent(OPEN_REQUEST_A)
        }
        val recreated = AttachmentDownloadIntentStore(preferences)

        assertTrue(recreated.hasOpenIntent(OPEN_REQUEST_A))
        assertFalse(recreated.hasOpenIntent(OPEN_REQUEST_B))
        assertTrue(recreated.consumeOpenIntent(OPEN_REQUEST_A))
        assertFalse(recreated.consumeOpenIntent(OPEN_REQUEST_A))
        assertFalse(AttachmentDownloadIntentStore(preferences).hasOpenIntent(OPEN_REQUEST_A))
    }

    @Test
    fun navigationSessionScopesFreshAndPermissionRecoveryIntents() {
        val store = AttachmentDownloadIntentStore(preferences)
        store.markOpenIntent(OPEN_REQUEST_A)

        assertFalse(store.hasDispatchableOpenIntent(OPEN_REQUEST_A.copy(navigationGeneration = 8L)))
        assertTrue(store.claimOpenIntent(OPEN_REQUEST_A) == AttachmentOpenIntentClaim.Fresh)
        assertTrue(store.beginInstallPermissionRequest(OPEN_REQUEST_A))
        store.abandonInstallPermissionRequest(OPEN_REQUEST_A)

        assertFalse(store.hasDispatchableOpenIntent(OPEN_REQUEST_A.copy(navigationGeneration = 8L)))
        assertTrue(store.hasDispatchableOpenIntent(OPEN_REQUEST_A))
    }

    @Test
    fun coldNavigationSessionCannotClaimAnOlderSessionsPersistedOpen() {
        val olderSession =
            OPEN_REQUEST_A.copy(
                navigationGeneration = newAttachmentOpenNavigationGeneration(UUID(1L, 2L)),
            )
        val coldSession =
            OPEN_REQUEST_A.copy(
                navigationGeneration = newAttachmentOpenNavigationGeneration(UUID(4L, 8L)),
            )
        val store = AttachmentDownloadIntentStore(preferences)

        store.markOpenIntent(olderSession)

        assertTrue(store.hasDispatchableOpenIntent(olderSession))
        assertFalse(store.hasDispatchableOpenIntent(coldSession))
        assertFalse(store.consumeOpenIntent(coldSession))
        assertTrue(store.hasDispatchableOpenIntent(olderSession))
    }

    @Test
    fun leavingTheOriginatingDestinationCancelsItsOpenButKeepsTheDownloadIntent() {
        val store = AttachmentDownloadIntentStore(preferences)
        store.markOpenIntent(OPEN_REQUEST_A)
        store.setInteractive(REQUEST_A, interactive = true)

        store.retainOpenIntentsForDestination(OPEN_REQUEST_B.destination)

        assertFalse(store.hasDispatchableOpenIntent(OPEN_REQUEST_A))
        assertTrue(store.isInteractive(REQUEST_A))
    }

    @Test
    fun installPermissionHandoffRecoversOnlyAfterItsProcessOwnerIsGone() {
        val store = AttachmentDownloadIntentStore(preferences)
        store.markOpenIntent(OPEN_REQUEST_A)

        assertTrue(store.claimOpenIntent(OPEN_REQUEST_A) == AttachmentOpenIntentClaim.Fresh)
        assertTrue(store.beginInstallPermissionRequest(OPEN_REQUEST_A))
        assertFalse(store.hasDispatchableOpenIntent(OPEN_REQUEST_A))
        assertFalse(AttachmentDownloadIntentStore(preferences).hasDispatchableOpenIntent(OPEN_REQUEST_A))

        store.abandonInstallPermissionRequest(OPEN_REQUEST_A)
        val recreated = AttachmentDownloadIntentStore(preferences)
        assertTrue(recreated.hasDispatchableOpenIntent(OPEN_REQUEST_A))
        assertTrue(
            recreated.claimOpenIntent(OPEN_REQUEST_A) == AttachmentOpenIntentClaim.InstallPermissionRecovery,
        )
        assertFalse(recreated.hasDispatchableOpenIntent(OPEN_REQUEST_A))
    }

    @Test
    fun failedFinalLaunchRestoresFreshIntentWithoutDuplicatingPermissionHandoff() {
        val store = AttachmentDownloadIntentStore(preferences)
        store.markOpenIntent(OPEN_REQUEST_A)
        assertTrue(store.claimOpenIntent(OPEN_REQUEST_A) == AttachmentOpenIntentClaim.Fresh)
        assertTrue(store.beginInstallPermissionRequest(OPEN_REQUEST_A))

        store.markOpenIntent(OPEN_REQUEST_A)
        store.restoreOpenIntent(OPEN_REQUEST_A)
        assertFalse(store.hasOpenIntent(OPEN_REQUEST_A))

        assertTrue(store.finishInstallPermissionRequest(OPEN_REQUEST_A))
        store.restoreOpenIntent(OPEN_REQUEST_A)
        assertTrue(store.hasOpenIntent(OPEN_REQUEST_A))
        assertTrue(store.hasDispatchableOpenIntent(OPEN_REQUEST_A))
    }

    @Test
    fun failedDiskCommitKeepsTheIntentPendingAcrossStoreRecreation() {
        AttachmentDownloadIntentStore(preferences).markOpenIntent(OPEN_REQUEST_A)
        val failingPreferences = CommitFailingSharedPreferences(preferences)
        val store = AttachmentDownloadIntentStore(failingPreferences)
        var observedMissingBeforeRecovery = false
        failingPreferences.onFailedCommit = {
            assertFalse(store.hasOpenIntent(OPEN_REQUEST_A))
            observedMissingBeforeRecovery = true
        }

        assertFalse(store.consumeOpenIntent(OPEN_REQUEST_A))
        assertTrue(observedMissingBeforeRecovery)
        assertTrue(failingPreferences.recoveryApplied)
        assertTrue(store.hasOpenIntent(OPEN_REQUEST_A))
        assertTrue(AttachmentDownloadIntentStore(preferences).hasOpenIntent(OPEN_REQUEST_A))

        assertTrue(AttachmentDownloadIntentStore(preferences).consumeOpenIntent(OPEN_REQUEST_A))
        assertFalse(AttachmentDownloadIntentStore(preferences).hasOpenIntent(OPEN_REQUEST_A))
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
        val OPEN_REQUEST_A = AttachmentOpenRequest(REQUEST_A, navigationGeneration = 7L)
        val OPEN_REQUEST_B = AttachmentOpenRequest(REQUEST_B, navigationGeneration = 7L)
    }
}

private class CommitFailingSharedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    private val visibleStringSets = mutableMapOf<String, Set<String>?>()
    var onFailedCommit: (() -> Unit)? = null
    var recoveryApplied: Boolean = false
        private set

    override fun getStringSet(
        key: String?,
        defValues: Set<String>?,
    ): Set<String>? =
        synchronized(visibleStringSets) {
            if (key != null && visibleStringSets.containsKey(key)) {
                visibleStringSets[key]?.toSet()
            } else {
                delegate.getStringSet(key, defValues)?.toSet()
            }
        }

    override fun edit(): SharedPreferences.Editor = CommitFailingEditor(this, delegate.edit())

    private fun publishInMemory(values: Map<String, Set<String>?>) {
        synchronized(visibleStringSets) {
            values.forEach { (key, value) -> visibleStringSets[key] = value?.toSet() }
        }
    }

    private fun restore(
        values: Map<String, Set<String>?>,
        delegateEditor: SharedPreferences.Editor,
    ) {
        publishInMemory(values)
        values.forEach { (key, value) -> delegateEditor.putStringSet(key, value) }
        delegateEditor.apply()
        recoveryApplied = true
    }

    private class CommitFailingEditor(
        private val owner: CommitFailingSharedPreferences,
        private val delegateEditor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by delegateEditor {
        private val stringSets = mutableMapOf<String, Set<String>?>()

        override fun putStringSet(
            key: String?,
            values: Set<String>?,
        ): SharedPreferences.Editor {
            if (key != null) stringSets[key] = values?.toSet()
            return this
        }

        override fun commit(): Boolean {
            // Android may publish the staged removal to its in-memory map even
            // when the durable write fails. Expose that state to the store,
            // while leaving the delegate's persisted value untouched.
            owner.publishInMemory(stringSets)
            owner.onFailedCommit?.invoke()
            return false
        }

        override fun apply() {
            owner.restore(stringSets, delegateEditor)
        }
    }
}
