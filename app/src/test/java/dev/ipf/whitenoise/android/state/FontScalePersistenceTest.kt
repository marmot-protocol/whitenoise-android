package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontScalePersistenceTest {
    private val states = mutableListOf<WhiteNoiseAppState>()

    @Before fun setUpMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDownMain() {
        // runTest drives Main's paused scheduler while background cancellation finishes.
        runTest {
            states.forEach { it.mutationsScope.cancel() }
            states.map { it.mutationsScope.coroutineContext.job }.joinAll()
        }
        Dispatchers.resetMain()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun teardownWaitsForCancellation() {
        val state = state(context.getSharedPreferences("font-teardown", Context.MODE_PRIVATE))
        var cleanedUp = false
        val job =
            state.mutationsScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable + Dispatchers.Default) {
                        withContext(Dispatchers.Main) {
                            delay(1)
                            cleanedUp = true
                        }
                    }
                }
            }

        try {
            tearDownMain()
            assertTrue(job.isCompleted)
            assertTrue(cleanedUp)
        } finally {
            setUpMain()
        }
    }

    @Test
    fun rapidSelectionsPersistTheLastStepAndRestoreOnReopen() =
        runTest {
            val preferences = context.getSharedPreferences("font-scale-success", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val state = state(preferences)
            state.updateFontScale(AppFontScale.Large)
            state.updateFontScale(AppFontScale.Small)
            state.updateFontScale(AppFontScale.ExtraLarge)
            state.mutationsScope.coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(AppFontScale.ExtraLarge.preferenceValue, preferences.getString("font_scale", null))
            assertEquals(AppFontScale.ExtraLarge, state(preferences).fontScale)
            assertEquals(AppFontScale.ExtraLarge, state.fontScale)
        }

    @Test
    fun failedDiskCommitRestoresPreviousSelectionAndPreferenceMemory() =
        runTest {
            val backing = context.getSharedPreferences("font-scale-failure", Context.MODE_PRIVATE)
            backing.edit().clear().commit()
            val attempts = AtomicInteger()
            val preferences =
                object : SharedPreferences by backing {
                    override fun edit(): SharedPreferences.Editor =
                        object : SharedPreferences.Editor by backing.edit() {
                            private val editor = backing.edit()

                            override fun putString(
                                key: String?,
                                value: String?,
                            ): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                editor.commit()
                                attempts.incrementAndGet()
                                return false
                            }
                        }
                }
            val state = state(preferences)
            state.updateFontScale(AppFontScale.ExtraLarge)
            state.mutationsScope.coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(2, attempts.get())
            assertEquals(AppFontScale.Default, state.fontScale)
            assertEquals(AppFontScale.Default, state(backing).fontScale)
        }

    /** An older failed save cannot claim that a newer visible selection was rolled back. */
    @Test fun supersededSaveFailureDoesNotReplaceTheNewerSelectionOrPresentItsFailureNotice() =
        runTest {
            val backing = context.getSharedPreferences("font-superseded-failure", Context.MODE_PRIVATE)
            backing.edit().clear().commit()
            var commits = 0
            val preferences =
                object : SharedPreferences by backing {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = backing.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(
                                key: String?,
                                value: String?,
                            ): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                editor.commit()
                                return ++commits != 1
                            }
                        }
                    }
                }
            val state = state(preferences)
            state.updateFontScale(AppFontScale.Large)
            state.updateFontScale(AppFontScale.ExtraLarge)
            state.mutationsScope.coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(AppFontScale.ExtraLarge, state.fontScale)
            assertEquals(AppFontScale.ExtraLarge, state(backing).fontScale)
            assertNull(state.transientNotice)
        }

    /** The real launch completion hook closes attempts even when the launched body never starts. */
    @Test fun cancellationBeforeDispatchClosesSettingsSaveExactlyOnce() =
        runTest {
            val preferences = context.getSharedPreferences("font-cancel-before", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val state = state(preferences)
            val initial = state.fontScale
            val outcomes = settingsOutcomes(state)
            val existingChildren =
                state.mutationsScope.coroutineContext.job.children
                    .toSet()
            state.updateFontScale(AppFontScale.Large)
            val job =
                state.mutationsScope.coroutineContext.job.children
                    .filterNot(existingChildren::contains)
                    .single()
            job.cancel()
            runCurrent()
            job.join()
            assertEquals(listOf(HostPerformanceOutcomeFfi.CANCELLED), outcomes)
            assertEquals(initial, state.fontScale)
            assertEquals(initial, state(preferences).fontScale)
        }

    /** Cancellation while waiting for the existing commit mutex has the same completion lifetime. */
    @Test fun cancellationWaitingForCommitMutexClosesSettingsSaveExactlyOnce() =
        runTest {
            val preferences = context.getSharedPreferences("font-cancel-mutex", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val state = state(preferences)
            val initial = state.fontScale
            val outcomes = settingsOutcomes(state)
            val field =
                WhiteNoiseAppState::class.java
                    .getDeclaredField("hostPreferenceCommitMutex")
                    .apply { isAccessible = true }
            val mutex = field.get(state) as Mutex
            mutex.lock()
            try {
                val existingChildren =
                    state.mutationsScope.coroutineContext.job.children
                        .toSet()
                state.updateFontScale(AppFontScale.ExtraLarge)
                runCurrent()
                val job =
                    state.mutationsScope.coroutineContext.job.children
                        .filterNot(existingChildren::contains)
                        .single()
                job.cancel()
                runCurrent()
                assertTrue(job.isActive.not())
                assertTrue(job.isCompleted.not())
            } finally {
                mutex.unlock()
            }
            state.mutationsScope.coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(listOf(HostPerformanceOutcomeFfi.CANCELLED), outcomes)
            assertEquals(initial, state.fontScale)
            assertEquals(initial, state(preferences).fontScale)
        }

    @Test
    fun cancellationDuringSuccessfulCommitRetainsDurableBaselineForLaterFailure() =
        runTest {
            val backing = context.getSharedPreferences("font-cancel-commit", Context.MODE_PRIVATE)
            backing.edit().clear().commit()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val commits = AtomicInteger()
            val preferences = blockingFirstCommit(backing, entered, release, commits, failSecond = true)
            val state = state(preferences)
            val outcomes = settingsOutcomes(state)
            val existingChildren =
                state.mutationsScope.coroutineContext.job.children
                    .toSet()
            state.updateFontScale(AppFontScale.Large)
            runCurrent()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val job =
                    state.mutationsScope.coroutineContext.job.children
                        .filterNot(existingChildren::contains)
                        .single()
                job.cancel()
            } finally {
                release.countDown()
            }
            state.mutationsScope.coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(AppFontScale.Large, state(backing).fontScale)
            assertEquals(listOf(HostPerformanceOutcomeFfi.SUCCESS), outcomes)
            state.updateFontScale(AppFontScale.ExtraLarge)
            state.mutationsScope.coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(AppFontScale.Large, state.fontScale)
            assertEquals(AppFontScale.Large, state(backing).fontScale)
            assertEquals(listOf(HostPerformanceOutcomeFfi.SUCCESS, HostPerformanceOutcomeFfi.FAILURE), outcomes)
        }

    @Test
    fun cancelledOlderCommitCannotRollBackNewerSelection() =
        runTest {
            val backing = context.getSharedPreferences("font-cancel-newer", Context.MODE_PRIVATE)
            backing.edit().clear().commit()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val state = state(blockingFirstCommit(backing, entered, release, AtomicInteger()))
            val existingChildren =
                state.mutationsScope.coroutineContext.job.children
                    .toSet()
            state.updateFontScale(AppFontScale.Large)
            runCurrent()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                state.mutationsScope.coroutineContext.job.children
                    .filterNot(existingChildren::contains)
                    .single()
                    .cancel()
                state.updateFontScale(AppFontScale.ExtraLarge)
                assertEquals(AppFontScale.ExtraLarge, state.fontScale)
            } finally {
                release.countDown()
            }
            state.mutationsScope.coroutineContext.job.children
                .toList()
                .joinAll()
            assertEquals(AppFontScale.ExtraLarge, state.fontScale)
            assertEquals(AppFontScale.ExtraLarge, state(backing).fontScale)
            assertNull(state.transientNotice)
        }

    private fun blockingFirstCommit(
        backing: SharedPreferences,
        entered: CountDownLatch,
        release: CountDownLatch,
        commits: AtomicInteger,
        failSecond: Boolean = false,
    ): SharedPreferences =
        object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(
                        key: String?,
                        value: String?,
                    ): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        val number = commits.incrementAndGet()
                        if (number == 1) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        val saved = editor.commit()
                        return saved && !(failSecond && number == 2)
                    }
                }
            }
        }

    /** Observe actual production attempts without requiring a native runtime or changing its persistence owner. */
    private fun settingsOutcomes(state: WhiteNoiseAppState): MutableList<HostPerformanceOutcomeFfi> {
        val field = WhiteNoiseAppState::class.java.getDeclaredField("hostPerformance").apply { isAccessible = true }
        val recorder = field.get(state) as HostPerformanceRecorder
        val outcomes = mutableListOf<HostPerformanceOutcomeFfi>()
        recorder.publishEmitter(
            Any(),
            state.runtimeGeneration,
            HostPerformanceEmitter { operation, _, outcome ->
                if (operation == HostPerformanceOperationFfi.SETTINGS_SAVE) outcomes.add(outcome)
            },
        )
        return outcomes
    }

    private fun state(preferences: SharedPreferences) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = null,
            preferences = preferences,
        ).also(states::add)
}
