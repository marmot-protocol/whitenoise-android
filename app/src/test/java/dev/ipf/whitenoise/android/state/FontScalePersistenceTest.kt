package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
        states.forEach { it.mutationsScope.cancel() }
        Dispatchers.resetMain()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

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
