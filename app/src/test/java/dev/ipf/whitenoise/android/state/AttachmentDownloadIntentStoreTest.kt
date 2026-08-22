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

    @Test
    fun failedDiskCommitKeepsTheIntentPendingAcrossStoreRecreation() {
        AttachmentDownloadIntentStore(preferences).markOpenIntent(REQUEST_A)
        val failingPreferences = CommitFailingSharedPreferences(preferences)
        val store = AttachmentDownloadIntentStore(failingPreferences)
        var observedMissingBeforeRecovery = false
        failingPreferences.onFailedCommit = {
            assertFalse(store.hasOpenIntent(REQUEST_A))
            observedMissingBeforeRecovery = true
        }

        assertFalse(store.consumeOpenIntent(REQUEST_A))
        assertTrue(observedMissingBeforeRecovery)
        assertTrue(failingPreferences.recoveryApplied)
        assertTrue(store.hasOpenIntent(REQUEST_A))
        assertTrue(AttachmentDownloadIntentStore(preferences).hasOpenIntent(REQUEST_A))

        assertTrue(AttachmentDownloadIntentStore(preferences).consumeOpenIntent(REQUEST_A))
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
