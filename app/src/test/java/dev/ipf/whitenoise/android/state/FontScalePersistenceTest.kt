package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontScalePersistenceTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun rapidSelectionsPersistTheLastStepAndRestoreOnReopen() {
        val preferences = context.getSharedPreferences("font-scale-success", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val state = state(preferences)
        composeRule.setContent {}
        composeRule.runOnIdle {
            state.updateFontScale(AppFontScale.Large)
            state.updateFontScale(AppFontScale.Small)
            state.updateFontScale(AppFontScale.ExtraLarge)
        }
        composeRule.waitUntil(5000) { preferences.getString("font_scale", null) == AppFontScale.ExtraLarge.preferenceValue }
        assertEquals(AppFontScale.ExtraLarge, state(preferences).fontScale)
        composeRule.runOnIdle { assertEquals(AppFontScale.ExtraLarge, state.fontScale) }
    }

    @Test
    fun failedDiskCommitRestoresPreviousSelectionAndPreferenceMemory() {
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
        composeRule.setContent {}
        composeRule.runOnIdle { state.updateFontScale(AppFontScale.ExtraLarge) }
        composeRule.waitUntil(5000) { attempts.get() >= 2 && state.fontScale == AppFontScale.Default }
        composeRule.runOnIdle { assertEquals(AppFontScale.Default, state(backing).fontScale) }
    }

    private fun state(preferences: SharedPreferences) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = null,
            preferences = preferences,
        )
}
