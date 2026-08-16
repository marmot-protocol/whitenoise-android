package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HiddenMessageStateConcurrencyTest {
    private val context
        get() = RuntimeEnvironment.getApplication().applicationContext
    private val backingPreferences
        get() = context.getSharedPreferences("hidden-message-state-concurrency-test", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        backingPreferences.edit().clear().commit()
    }

    @Test
    fun concurrentSuccessfulHidesCannotPublishAnOlderSnapshotLast() =
        runBlocking {
            val preferences = GatedHiddenMessagePreferences(backingPreferences)
            val appState = appState(preferences)
            val firstExecutor = Executors.newSingleThreadExecutor()
            val secondExecutor = Executors.newSingleThreadExecutor()
            val firstDispatcher = firstExecutor.asCoroutineDispatcher()
            val secondDispatcher = secondExecutor.asCoroutineDispatcher()
            val firstContinuationBlocked = CountDownLatch(1)
            val releaseFirstContinuation = CountDownLatch(1)

            try {
                val first =
                    async(firstDispatcher) {
                        appState.hideMessageForMe(ACCOUNT_REF, GROUP_ID, MESSAGE_ONE)
                    }
                assertTrue(preferences.firstHiddenPutCommitEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                firstExecutor.execute {
                    firstContinuationBlocked.countDown()
                    check(releaseFirstContinuation.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
                assertTrue(firstContinuationBlocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                preferences.releaseFirstHiddenPutCommit.countDown()
                assertTrue(preferences.firstHiddenPutCommitFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                val second =
                    async(secondDispatcher) {
                        appState.hideMessageForMe(ACCOUNT_REF, GROUP_ID, MESSAGE_TWO)
                    }
                releaseFirstContinuation.countDown()
                assertTrue(first.await())
                assertTrue(second.await())

                assertEquals(
                    setOf(MESSAGE_ONE, MESSAGE_TWO),
                    appState.hiddenMessageIdsInGroup(ACCOUNT_REF, GROUP_ID),
                )
            } finally {
                preferences.releaseFirstHiddenPutCommit.countDown()
                releaseFirstContinuation.countDown()
                firstDispatcher.close()
                secondDispatcher.close()
            }
        }

    @Test
    fun destructiveClearWinsOverAnInFlightHideAndCommitsDurably() =
        runBlocking {
            val preferences = GatedHiddenMessagePreferences(backingPreferences)
            val appState = appState(preferences)
            val hide =
                async(Dispatchers.Default) {
                    appState.hideMessageForMe(ACCOUNT_REF, GROUP_ID, MESSAGE_ONE)
                }
            assertTrue(preferences.firstHiddenPutCommitEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            // Queued on this runBlocking event loop. Without shared serialization,
            // clear returns before this release and the older hide resurrects state.
            val releaseCommit = launch { preferences.releaseFirstHiddenPutCommit.countDown() }
            assertTrue(appState.clearHiddenMessagesForAccount(ACCOUNT_REF))

            releaseCommit.join()
            assertTrue(hide.await())
            assertTrue(appState.hiddenMessageIdsInGroup(ACCOUNT_REF, GROUP_ID).isEmpty())
            assertTrue(MessageHidePreferences.readHiddenMessageIds(backingPreferences, ACCOUNT_REF, GROUP_ID).isEmpty())
            assertEquals(0, preferences.hiddenClearApplyCalls.get())
            assertEquals(1, preferences.hiddenClearCommitCalls.get())
        }

    @Test
    fun failedDestructiveClearPreservesPublishedAndDurableHiddenState() =
        runBlocking {
            val preferences = ClearFailingPreferences(backingPreferences)
            val appState = appState(preferences)
            assertTrue(appState.hideMessageForMe(ACCOUNT_REF, GROUP_ID, MESSAGE_ONE))

            val cleared = appState.clearHiddenMessagesForAccount(ACCOUNT_REF)

            assertFalse(cleared)
            assertEquals(
                setOf(MESSAGE_ONE),
                appState.hiddenMessageIdsInGroup(ACCOUNT_REF, GROUP_ID),
            )
            assertEquals(
                setOf(MESSAGE_ONE),
                MessageHidePreferences.readHiddenMessageIds(backingPreferences, ACCOUNT_REF, GROUP_ID),
            )
        }

    private fun appState(preferences: SharedPreferences) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
            preferences = preferences,
        )

    private class GatedHiddenMessagePreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        val firstHiddenPutCommitEntered = CountDownLatch(1)
        val releaseFirstHiddenPutCommit = CountDownLatch(1)
        val firstHiddenPutCommitFinished = CountDownLatch(1)
        val hiddenClearApplyCalls = AtomicInteger()
        val hiddenClearCommitCalls = AtomicInteger()
        private val gateFirstHiddenPut = AtomicBoolean(true)

        override fun edit(): SharedPreferences.Editor = Editor(delegate.edit(), this)

        private class Editor(
            private val delegate: SharedPreferences.Editor,
            private val owner: GatedHiddenMessagePreferences,
        ) : SharedPreferences.Editor by delegate {
            private var writesHiddenMessages = false
            private var removesHiddenMessages = false

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                if (key?.startsWith(HIDDEN_MESSAGE_KEY_PREFIX) == true) writesHiddenMessages = true
                delegate.putStringSet(key, values)
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key?.startsWith(HIDDEN_MESSAGE_KEY_PREFIX) == true) removesHiddenMessages = true
                delegate.remove(key)
                return this
            }

            override fun commit(): Boolean {
                val gated = writesHiddenMessages && owner.gateFirstHiddenPut.compareAndSet(true, false)
                if (gated) {
                    owner.firstHiddenPutCommitEntered.countDown()
                    check(owner.releaseFirstHiddenPutCommit.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
                val result = delegate.commit()
                if (removesHiddenMessages) owner.hiddenClearCommitCalls.incrementAndGet()
                if (gated) owner.firstHiddenPutCommitFinished.countDown()
                return result
            }

            override fun apply() {
                if (removesHiddenMessages) owner.hiddenClearApplyCalls.incrementAndGet()
                delegate.apply()
            }
        }
    }

    private class ClearFailingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = Editor(delegate.edit())

        private class Editor(
            private val delegate: SharedPreferences.Editor,
        ) : SharedPreferences.Editor by delegate {
            private var removesHiddenMessages = false

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key?.startsWith(HIDDEN_MESSAGE_KEY_PREFIX) == true) removesHiddenMessages = true
                delegate.remove(key)
                return this
            }

            override fun commit(): Boolean {
                val result = delegate.commit()
                return result && !removesHiddenMessages
            }
        }
    }

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_ONE = "05" + "00".repeat(31)
        val MESSAGE_TWO = "06" + "00".repeat(31)
        const val HIDDEN_MESSAGE_KEY_PREFIX = "hidden_messages:"
        const val TIMEOUT_SECONDS = 10L
    }
}
