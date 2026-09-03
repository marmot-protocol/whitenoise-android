package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentDownloadIntentStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences("attachment-download-intent-test", Context.MODE_PRIVATE)
    }
    private val installerHandoffRecords = VolatileAttachmentInstallerHandoffRecordStore()

    /** Clears durable and process-local ownership between examples. */
    @Before
    fun reset() {
        intentStore().apply {
            abandonInstallPermissionRequest(OPEN_REQUEST_A)
            abandonInstallPermissionRequest(OPEN_REQUEST_B)
            abandonInstallerPermissionHandoff(INSTALLER_REQUEST_A)
            abandonInstallerPermissionHandoff(INSTALLER_REQUEST_B)
        }
        installerHandoffRecords.replaceAllDurably(emptyMap())
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

    /** Installer handoff is app-owned and must not be pruned with route-scoped viewers. */
    @Test
    fun installerHandoffSurvivesNavigationCleanupAndProcessRecreation() {
        val store = intentStore()
        assertTrue(store.markInstallerHandoff(INSTALLER_REQUEST_A))
        store.markOpenIntent(OPEN_REQUEST_A)

        store.retainOpenIntentsForDestination(OPEN_REQUEST_B.destination)

        assertFalse(store.hasDispatchableOpenIntent(OPEN_REQUEST_A))
        assertEquals(INSTALLER_REQUEST_A, intentStore().pendingInstallerHandoff())
    }

    /** A different projection epoch cannot claim a persisted attachment handoff. */
    @Test
    fun installerHandoffPersistsAndMatchesTheExactSourceEpoch() {
        val store = intentStore()
        val differentEpoch = INSTALLER_REQUEST_A.copy(sourceEpoch = 8uL)
        assertTrue(store.markInstallerHandoff(INSTALLER_REQUEST_A))

        val recreated = intentStore()
        assertEquals(INSTALLER_REQUEST_A, recreated.pendingInstallerHandoff())
        assertNull(recreated.claimInstallerHandoff(differentEpoch))
        assertEquals(INSTALLER_REQUEST_A, recreated.pendingInstallerHandoff())
    }

    /** A later APK tap replaces an older pending installer destination atomically. */
    @Test
    fun latestInstallerHandoffSupersedesThePreviousAttachment() {
        val store = intentStore()
        assertTrue(store.markInstallerHandoff(INSTALLER_REQUEST_A))
        assertTrue(store.markInstallerHandoff(INSTALLER_REQUEST_B))

        assertEquals(INSTALLER_REQUEST_B, store.pendingInstallerHandoff())
        assertNull(store.claimInstallerHandoff(INSTALLER_REQUEST_A))
        assertEquals(AttachmentOpenIntentClaim.Fresh, store.claimInstallerHandoff(INSTALLER_REQUEST_B))
        assertNull(intentStore().pendingInstallerHandoff())
    }

    /** The durable record can be accepted only once across recreated app owners. */
    @Test
    fun installerHandoffClaimIsExactlyOnceAcrossProcessOwners() {
        intentStore().markInstallerHandoff(INSTALLER_REQUEST_A)

        assertEquals(
            AttachmentOpenIntentClaim.Fresh,
            intentStore().claimInstallerHandoff(INSTALLER_REQUEST_A),
        )
        assertNull(intentStore().claimInstallerHandoff(INSTALLER_REQUEST_A))
    }

    /** A stale claimed launch cannot overwrite a newer tap before dispatch. */
    @Test
    fun supersedingTapWinsTheClaimToPermissionHandoffRace() {
        val store = intentStore()
        store.markInstallerHandoff(INSTALLER_REQUEST_A)
        assertEquals(AttachmentOpenIntentClaim.Fresh, store.claimInstallerHandoff(INSTALLER_REQUEST_A))

        store.markInstallerHandoff(INSTALLER_REQUEST_B)

        assertFalse(store.beginInstallerPermissionHandoff(INSTALLER_REQUEST_A))
        assertFalse(store.restoreInstallerHandoff(INSTALLER_REQUEST_A))
        assertEquals(INSTALLER_REQUEST_B, store.pendingInstallerHandoff())
    }

    /** Settings recovery stays durable but only a replacement process may claim it. */
    @Test
    fun installerPermissionHandoffRecoversAfterItsActiveOwnerIsAbandoned() {
        val store = intentStore()
        store.markInstallerHandoff(INSTALLER_REQUEST_A)
        assertEquals(AttachmentOpenIntentClaim.Fresh, store.claimInstallerHandoff(INSTALLER_REQUEST_A))
        assertTrue(store.beginInstallerPermissionHandoff(INSTALLER_REQUEST_A))
        assertNull(store.pendingInstallerHandoff())

        store.abandonInstallerPermissionHandoff(INSTALLER_REQUEST_A)

        val recreated = intentStore()
        assertEquals(INSTALLER_REQUEST_A, recreated.pendingInstallerHandoff())
        assertEquals(
            AttachmentOpenIntentClaim.InstallPermissionRecovery,
            recreated.claimInstallerHandoff(INSTALLER_REQUEST_A),
        )
        assertNull(recreated.claimInstallerHandoff(INSTALLER_REQUEST_A))
    }

    /** Keeps installer recovery durable across instances without exposing its identity at rest. */
    @Test
    fun encryptedInstallerHandoffSurvivesRecreationWithoutPlaintextPreferences() {
        val fileName = "attachment-installer-handoff-encrypted-test"
        context.deleteSharedPreferences(fileName)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keyProvider =
            object : SecureStoreKeyProvider {
                override fun secretKey() = key
            }
        val firstRecords =
            EncryptedAttachmentInstallerHandoffRecordStore(
                KeystoreSecureStore(context, fileName, keyProvider),
            )
        assertTrue(AttachmentDownloadIntentStore(preferences, firstRecords).markInstallerHandoff(INSTALLER_REQUEST_A))

        val raw = context.getSharedPreferences(fileName, Context.MODE_PRIVATE).all
        val sealed = raw.values.joinToString()
        assertEquals(setOf("payload"), raw.keys)
        assertFalse(sealed.contains(INSTALLER_REQUEST_A.transfer.accountRef))
        assertFalse(sealed.contains(INSTALLER_REQUEST_A.transfer.groupIdHex))
        assertFalse(sealed.contains(INSTALLER_REQUEST_A.transfer.messageIdHex))

        val recreatedRecords =
            EncryptedAttachmentInstallerHandoffRecordStore(
                KeystoreSecureStore(context, fileName, keyProvider),
            )
        assertEquals(
            INSTALLER_REQUEST_A,
            AttachmentDownloadIntentStore(preferences, recreatedRecords).pendingInstallerHandoff(),
        )
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

    @Test
    fun aCancelledAttachmentStaysSuppressedUntilItIsExplicitlyRequested() {
        val store = AttachmentDownloadIntentStore(preferences)
        store.suppressAutomatic(REQUEST_A)

        val recreated = AttachmentDownloadIntentStore(preferences)
        assertTrue(recreated.isAutomaticSuppressed(REQUEST_A))
        assertFalse("suppression is per attachment", recreated.isAutomaticSuppressed(REQUEST_B))

        recreated.restoreAutomatic(REQUEST_A)
        assertFalse(AttachmentDownloadIntentStore(preferences).isAutomaticSuppressed(REQUEST_A))
    }

    @Test
    fun restartingTheBacklogDropsEveryCancelRecord() {
        val store = AttachmentDownloadIntentStore(preferences)
        store.suppressAutomatic(REQUEST_A)
        store.suppressAutomatic(REQUEST_B)

        store.clearSuppressedAutomatic()

        val recreated = AttachmentDownloadIntentStore(preferences)
        assertFalse(recreated.isAutomaticSuppressed(REQUEST_A))
        assertFalse(recreated.isAutomaticSuppressed(REQUEST_B))
    }

    @Test
    fun aFreshTapSupersedesACancelWhoseRevocationHasNotLandedYet() {
        val store = AttachmentDownloadIntentStore(preferences)
        store.markOpenIntent(OPEN_REQUEST_A)

        // The cancel's durable revocation is still in flight when the user taps
        // again; it must not remove the intent that second tap just created.
        assertFalse(store.consumeOpenIntentUnlessSuperseded(OPEN_REQUEST_A) { true })
        assertTrue(store.hasOpenIntent(OPEN_REQUEST_A))

        assertTrue(store.consumeOpenIntentUnlessSuperseded(OPEN_REQUEST_A) { false })
        assertFalse(store.hasOpenIntent(OPEN_REQUEST_A))
    }

    /** Creates a store whose installer record survives simulated process-owner recreation. */
    private fun intentStore(): AttachmentDownloadIntentStore =
        AttachmentDownloadIntentStore(
            preferences,
            installerHandoffRecords,
        )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        val REQUEST_A =
            AttachmentTransferRequest(
                accountRef = ACCOUNT_A,
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
        val REQUEST_B = REQUEST_A.copy(accountRef = ACCOUNT_B)
        val INSTALLER_REQUEST_A = AttachmentInstallerHandoffRequest(REQUEST_A, sourceEpoch = 7uL)
        val INSTALLER_REQUEST_B = AttachmentInstallerHandoffRequest(REQUEST_B, sourceEpoch = 9uL)
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
