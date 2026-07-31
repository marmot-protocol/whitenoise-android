package dev.ipf.whitenoise.android.updates

import android.content.Context
import kotlinx.coroutines.runBlocking
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

private typealias FetchLatestRelease = suspend (String, String?) -> ZapstoreLatestRelease?

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateRepositoryTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    private var nowMillis = BASE_TIME_MS

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

    private fun repository(): AppUpdateRepository = repository { _, _ -> null }

    private fun repository(fetchLatestRelease: FetchLatestRelease): AppUpdateRepository =
        AppUpdateRepository(
            context = context,
            fetchLatestRelease = fetchLatestRelease,
            currentTimeMillis = { nowMillis },
        )

    private companion object {
        const val BASE_TIME_MS = 1_700_000_000_000L
        const val APP_UPDATE_PREFERENCES_NAME = "darkmatter_app_updates"
    }
}
