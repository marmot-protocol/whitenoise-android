package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Removing a pre-installed set from the picker survives a process restart and can be undone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BuiltinEmojiSetChoicesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun hiddenSetsPersistAcrossProcesses() {
        val first = BuiltinEmojiSetChoices(context)
        runBlocking { first.load() }
        first.setHidden(BuiltinEmojiSet.Marmot, hide = true)

        val restarted = BuiltinEmojiSetChoices(context)
        runBlocking { restarted.load() }
        assertEquals(setOf(BuiltinEmojiSet.Marmot), restarted.hidden)

        restarted.setHidden(BuiltinEmojiSet.Marmot, hide = false)
        val restored = BuiltinEmojiSetChoices(context)
        runBlocking { restored.load() }
        assertTrue(restored.hidden.isEmpty())
    }

    @Test
    fun changeBeforeLoadKeepsStoredChoice() {
        val first = BuiltinEmojiSetChoices(context)
        runBlocking { first.load() }
        first.setHidden(BuiltinEmojiSet.Marmot, hide = true)

        val restarted = BuiltinEmojiSetChoices(context)
        restarted.setHidden(BuiltinEmojiSet.WhiteNoise, hide = true)
        runBlocking { restarted.load() }
        assertEquals(setOf(BuiltinEmojiSet.Marmot), restarted.hidden)
    }

    /** A removed set cannot appear in the picker while the stored choice is still being read. */
    @Test
    fun pickerHidesEverySetUntilLoaded() {
        val first = BuiltinEmojiSetChoices(context)
        runBlocking { first.load() }
        first.setHidden(BuiltinEmojiSet.Marmot, hide = true)

        val restarted = BuiltinEmojiSetChoices(context)
        assertEquals(BuiltinEmojiSet.entries.toSet(), restarted.pickerHidden)
        runBlocking { restarted.load() }
        assertEquals(setOf(BuiltinEmojiSet.Marmot), restarted.pickerHidden)
    }

    /** An id from a retired or future set is skipped without dropping the known ones beside it. */
    @Test
    fun unknownStoredIdsAreIgnored() {
        context
            .getSharedPreferences("whitenoise_ui", Context.MODE_PRIVATE)
            .edit()
            .putString("hidden_builtin_emoji_sets", "set.retired\nset.marmot")
            .commit()
        val choices = BuiltinEmojiSetChoices(context)
        runBlocking { choices.load() }
        assertEquals(setOf(BuiltinEmojiSet.Marmot), choices.hidden)
    }
}
