package dev.ipf.whitenoise.android.updates

import android.content.Context
import dev.ipf.whitenoise.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

private typealias FetchLatestRelease = suspend (String, String?) -> ZapstoreLatestRelease?

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateRepositoryTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    private var nowMillis = BASE_TIME_MS

    /** Isolates persisted scheduling outcomes while retaining shared admission across repository instances. */
    @Before
    fun clearPreferences() {
        nowMillis = BASE_TIME_MS
        context
            .getSharedPreferences(APP_UPDATE_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun freshRepositoryShouldCheckImmediately() {
        val repository = repository()

        assertTrue(repository.shouldCheck(nowMillis))
    }

    @Test
    fun persistedSuccessfulCheckHonorsTwentyFourHourInterval() =
        runBlocking {
            val repository = repository { _, _ -> ZapstoreLatestRelease("2026.2.0", 1) }
            val installed = "2026.1.0"

            repository.refresh(installed)
            val reloaded = repository()
            val checkedAt = reloaded.loadInfo(installed).checkedAtMillis!!
            assertEquals(nowMillis, checkedAt)

            assertFalse(reloaded.shouldCheck(checkedAt + AppUpdateRepository.CHECK_INTERVAL_MS - 1L))
            assertTrue(reloaded.shouldCheck(checkedAt + AppUpdateRepository.CHECK_INTERVAL_MS))
        }

    @Test
    fun refreshPersistsReleaseStateAndNoReleaseClearsStaleLatest() =
        runBlocking {
            val releases = ArrayDeque(listOf(ZapstoreLatestRelease("2026.2.0", 2), null))
            val repository =
                repository { _, _ -> releases.removeFirstOrNull() }
            val installed = "2026.1.0"

            repository.refresh(installed)
            val withRelease = repository().loadInfo(installed)
            val checkedAt = withRelease.checkedAtMillis!!
            assertEquals("2026.2.0", withRelease.latestVersion)
            assertEquals(2, withRelease.releasesBehind)
            assertEquals(nowMillis, checkedAt)

            nowMillis += 60_000L
            repository.refresh(installed)
            val withoutRelease = repository().loadInfo(installed)

            assertEquals(checkedAt + 60_000L, withoutRelease.checkedAtMillis)
            assertNull(withoutRelease.latestVersion)
            assertNull(withoutRelease.releasesBehind)
        }

    @Test
    fun dismissLatestSurvivesNewRepositoryInstance() =
        runBlocking {
            val installed = "2026.1.0"
            val repository = repository { _, _ -> ZapstoreLatestRelease("2026.2.0", 1) }
            repository.refresh(installed)

            val dismissed = repository.dismissLatest(installed)
            assertEquals("2026.2.0", dismissed.dismissedVersion)

            val reloaded = repository().loadInfo(installed)
            assertEquals("2026.2.0", reloaded.dismissedVersion)
        }

    @Test
    fun failedAttemptPersistsBoundedPrivacySafeOutcomeUntilSuccess() =
        runBlocking {
            val credentialUrl = "https://user:pass@example.test"
            val failing = repository { _, _ -> throw IOException("failed $credentialUrl") }

            runCatching { failing.refresh("2026.1.0") }
            val failed = repository().loadInfo("2026.1.0")

            assertEquals(nowMillis, failed.lastAttemptAtMillis)
            assertTrue(failed.lastAttemptErrorReport.orEmpty().contains("operation=BACKGROUND_UPDATE_CHECK"))
            assertFalse(failed.lastAttemptErrorReport.orEmpty().contains("user:pass"))
            assertTrue(failed.lastAttemptErrorReport.orEmpty().length <= 600)

            nowMillis += 1_000L
            repository { _, _ -> null }.refresh("2026.1.0")
            assertNull(repository().loadInfo("2026.1.0").lastAttemptErrorReport)
        }

    /** Repeated lifecycle callbacks cannot retry a stale failed check until the persisted cooldown ends. */
    @Test
    fun failedAutomaticAttemptThrottlesNewRepositoryUntilCooldownExpires() =
        runBlocking {
            var fetches = 0
            val failing =
                repository { _, _ ->
                    fetches++
                    throw IOException("offline")
                }
            assertTrue(runCatching { failing.refreshAutomatically() }.exceptionOrNull() is IOException)
            val retry =
                repository { _, _ ->
                    fetches++
                    null
                }
            repeat(5) { assertNull(retry.refreshAutomatically()) }
            nowMillis += AppUpdateRepository.FAILURE_COOLDOWN_MS - 1L
            assertNull(retry.refreshAutomatically())
            assertEquals(1, fetches)

            nowMillis++
            assertTrue(retry.refreshAutomatically() != null)
            assertEquals(2, fetches)
            assertNull(retry.loadInfo().lastAttemptErrorReport)
            assertNull(retry.refreshAutomatically())
        }

    /** A successful automatic check suppresses actual fetches for the full 24-hour interval. */
    @Test
    fun automaticSuccessIsSharedAcrossInstancesForTwentyFourHours() =
        runBlocking {
            var fetches = 0
            val fetch: FetchLatestRelease = { _, _ ->
                fetches++
                null
            }
            repository(fetchLatestRelease = fetch).refreshAutomatically()
            nowMillis += AppUpdateRepository.CHECK_INTERVAL_MS - 1L
            assertNull(repository(fetchLatestRelease = fetch).refreshAutomatically())
            assertEquals(1, fetches)
            nowMillis++
            repository(fetchLatestRelease = fetch).refreshAutomatically()
            assertEquals(2, fetches)
        }

    /** Explicit Check now retries immediately after failure or success, even with automatic data use denied. */
    @Test
    fun explicitRetryBypassesTimingAndAutomaticNetworkPolicy() =
        runBlocking {
            var fetches = 0
            val repository =
                repository(networkAllowed = false) { _, _ ->
                    fetches++
                    if (fetches == 1) throw IOException("offline")
                    null
                }
            assertNull(repository.refreshAutomatically())
            assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is IOException)
            repository.refresh()
            repository.refresh()
            assertEquals(3, fetches)
        }

    /** Play and other store-managed distributions cannot fetch even through an explicit repository call. */
    @Test
    fun disabledDistributionNeverFetches() =
        runBlocking {
            var fetches = 0
            val repository =
                repository(enabled = false) { _, _ ->
                    fetches++
                    null
                }
            assertNull(repository.refreshAutomatically())
            repository.refresh()
            assertEquals(0, fetches)
            assertNull(repository.loadInfo().lastAttemptAtMillis)
        }

    /** Exercises the real generated flavor default so Play parity CI guards the production wiring. */
    @Test
    fun productionDistributionDefaultControlsAutomaticFetches() =
        runBlocking {
            var fetches = 0
            val repository =
                AppUpdateRepository(
                    context,
                    fetchLatestRelease = { _, _ ->
                        fetches++
                        null
                    },
                    automaticNetworkAllowed = { true },
                    ioDispatcher = Dispatchers.Unconfined,
                )
            repository.refreshAutomatically()
            assertEquals(if (BuildConfig.SELF_UPDATE_ENABLED) 1 else 0, fetches)
        }

    /** A denied trigger neither starts a fetch nor records an attempt, so a restored network can check. */
    @Test
    fun automaticNetworkDenialDoesNotConsumeTheCheckInterval() =
        runBlocking {
            var fetches = 0
            val blocked =
                repository(networkAllowed = false) { _, _ ->
                    fetches++
                    null
                }
            assertNull(blocked.refreshAutomatically())
            assertEquals(0, fetches)
            assertNull(repository().loadInfo().lastAttemptAtMillis)
            repository { _, _ ->
                fetches++
                null
            }.refreshAutomatically()
            assertEquals(1, fetches)
        }

    /** Lifecycle, worker and manual callers share one owner even when they use different repositories. */
    @Test
    fun overlappingRepositoriesAndManualCheckShareOneFetch() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            var fetches = 0
            val fetch: FetchLatestRelease = { _, _ ->
                fetches++
                release.await()
                ZapstoreLatestRelease("2026.2.0", 1)
            }
            val owner = async(start = CoroutineStart.UNDISPATCHED) { repository(fetchLatestRelease = fetch).refreshAutomatically() }
            val automatic = async(start = CoroutineStart.UNDISPATCHED) { repository(fetchLatestRelease = fetch).refreshAutomatically() }
            val manual = async(start = CoroutineStart.UNDISPATCHED) { repository(fetchLatestRelease = fetch).refresh() }
            assertEquals(1, fetches)
            release.complete(Unit)
            assertEquals("2026.2.0", owner.await()?.latestVersion)
            assertEquals(owner.await(), automatic.await())
            assertEquals(owner.await(), manual.await())
            repository(fetchLatestRelease = fetch).refresh()
            assertEquals(2, fetches)
        }

    /** Cancelling a joining lifecycle task must not abort the worker's active request. */
    @Test
    fun cancellingWaiterLeavesOwnerAndOtherWaitersUsable() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            var fetches = 0
            val fetch: FetchLatestRelease = { _, _ ->
                fetches++
                release.await()
                null
            }
            val owner = async(start = CoroutineStart.UNDISPATCHED) { repository(fetchLatestRelease = fetch).refreshAutomatically() }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { repository(fetchLatestRelease = fetch).refreshAutomatically() }
            cancelled.cancelAndJoin()
            val survivor = async(start = CoroutineStart.UNDISPATCHED) { repository(fetchLatestRelease = fetch).refreshAutomatically() }
            release.complete(Unit)
            assertTrue(owner.await() != null)
            assertEquals(owner.await(), survivor.await())
            assertEquals(1, fetches)
        }

    /** Owner cancellation releases the shared slot without persisting a failure or leaving waiters hung. */
    @Test
    fun cancelledOwnerReleasesWaitersAndAllowsImmediateAutomaticRetry() =
        runBlocking {
            val owner =
                async(start = CoroutineStart.UNDISPATCHED) {
                    repository { _, _ -> awaitCancellation() }.refreshAutomatically()
                }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { repository().refreshAutomatically() }
            owner.cancelAndJoin()
            assertTrue(runCatching { waiter.await() }.exceptionOrNull() is CancellationException)
            assertNull(repository().loadInfo().lastAttemptAtMillis)
            assertTrue(repository().refreshAutomatically() != null)
        }

    /** Failure reaches all joined callers once, and a subsequent manual retry can clear it. */
    @Test
    fun sharedFailureReleasesAdmissionForExplicitRetry() =
        runBlocking {
            supervisorScope {
                val release = CompletableDeferred<Unit>()
                var fetches = 0
                val owner =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        repository { _, _ ->
                            fetches++
                            release.await()
                            throw IOException("offline")
                        }.refreshAutomatically()
                    }
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { repository().refreshAutomatically() }
                release.complete(Unit)
                assertTrue(runCatching { owner.await() }.exceptionOrNull() is IOException)
                assertTrue(runCatching { waiter.await() }.exceptionOrNull() is IOException)
                assertEquals(1, fetches)
                repository().refresh()
                assertNull(repository().loadInfo().lastAttemptErrorReport)
            }
        }

    /** Moving wall time backwards cannot leave a persisted success or failure blocked indefinitely. */
    @Test
    fun clockRollbackAllowsOneFreshAttemptThenRestoresCooldown() =
        runBlocking {
            repository().refresh()
            nowMillis--
            assertTrue(repository().shouldCheck())
            val failing = repository { _, _ -> throw IOException("offline") }
            runCatching { failing.refresh() }
            assertFalse(failing.shouldCheck())
            nowMillis--
            assertTrue(failing.shouldCheck())
            runCatching { failing.refreshAutomatically() }
            assertFalse(failing.shouldCheck())
        }

    /** Creates a self-updating fixture independent of the compiled flavor, with a deterministic clock. */
    private fun repository(
        enabled: Boolean = true,
        networkAllowed: Boolean = true,
        fetchLatestRelease: FetchLatestRelease = { _, _ -> null },
    ): AppUpdateRepository =
        AppUpdateRepository(
            context = context,
            fetchLatestRelease = fetchLatestRelease,
            currentTimeMillis = { nowMillis },
            selfUpdateEnabled = enabled,
            automaticNetworkAllowed = { networkAllowed },
            ioDispatcher = Dispatchers.Unconfined,
        )

    private companion object {
        const val BASE_TIME_MS = 1_700_000_000_000L
        const val APP_UPDATE_PREFERENCES_NAME = "darkmatter_app_updates"
    }
}
