package dev.ipf.whitenoise.android.notifications

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationVibrationPreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences("whitenoise.conversation_vibration", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun missingOrInvalidSelectionsUseSystemDefault() {
        context
            .getSharedPreferences("whitenoise.conversation_vibration", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("selections", setOf("REMOVED_PATTERN\u0000account-a|group-a"))
            .commit()
        val preferences = ConversationVibrationPreferences(context)

        assertEquals(
            ConversationVibrationPattern.SYSTEM_DEFAULT,
            preferences.pattern("account-a", "group-a"),
        )
        assertEquals(
            ConversationVibrationPattern.SYSTEM_DEFAULT,
            preferences.pattern("", "group-a"),
        )
    }

    @Test
    fun selectionIsScopedByAccountAndConversation() {
        val preferences = ConversationVibrationPreferences(context)

        preferences.setPattern("account-a", "group-a", ConversationVibrationPattern.DOUBLE)

        assertEquals(ConversationVibrationPattern.DOUBLE, preferences.pattern("account-a", "group-a"))
        assertEquals(ConversationVibrationPattern.SYSTEM_DEFAULT, preferences.pattern("account-b", "group-a"))
        assertEquals(ConversationVibrationPattern.SYSTEM_DEFAULT, preferences.pattern("account-a", "group-b"))
    }

    @Test
    fun selectionSurvivesProcessRestartAndNormalizesGroupIdCase() {
        ConversationVibrationPreferences(context).setPattern(
            "account-a",
            "ABC123",
            ConversationVibrationPattern.LONG,
        )

        val reloaded = ConversationVibrationPreferences(context)

        assertEquals(ConversationVibrationPattern.LONG, reloaded.pattern("account-a", "abc123"))
    }

    @Test
    fun selectingSystemDefaultRemovesTheStoredOverride() {
        val preferences = ConversationVibrationPreferences(context)
        preferences.setPattern("account-a", "group-a", ConversationVibrationPattern.SHORT)

        preferences.setPattern("account-a", "group-a", ConversationVibrationPattern.SYSTEM_DEFAULT)

        assertEquals(ConversationVibrationPattern.SYSTEM_DEFAULT, preferences.pattern("account-a", "group-a"))
        assertEquals(emptyMap<String, ConversationVibrationPattern>(), preferences.state.value)
    }

    @Test
    fun staleStoreInstanceDoesNotEraseAnotherInstancesSelection() {
        val first = ConversationVibrationPreferences(context)
        val stale = ConversationVibrationPreferences(context)

        first.setPattern("account-a", "group-a", ConversationVibrationPattern.SHORT)
        stale.setPattern("account-b", "group-b", ConversationVibrationPattern.LONG)

        val reloaded = ConversationVibrationPreferences(context)
        assertEquals(ConversationVibrationPattern.SHORT, reloaded.pattern("account-a", "group-a"))
        assertEquals(ConversationVibrationPattern.LONG, reloaded.pattern("account-b", "group-b"))
    }
}
