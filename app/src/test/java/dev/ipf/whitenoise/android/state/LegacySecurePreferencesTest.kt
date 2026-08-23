package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacySecurePreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(FILE_NAME)
        legacyPrefsFile().delete()
    }

    @Test
    fun readReturnsNullWhenLegacyFileWasNeverCreated() {
        assertNull(LegacySecurePreferences.read(context, FILE_NAME))
    }

    @Test
    fun readReturnsDecryptedStringEntries() {
        markLegacyFilePresent()
        val opener: (Context, String) -> android.content.SharedPreferences = { ctx, _ ->
            ctx.getSharedPreferences("legacy-test-string", Context.MODE_PRIVATE).apply {
                edit().putString(LAST_UNLOCKED_AT_KEY, "12345").commit()
            }
        }

        assertEquals(
            mapOf(LAST_UNLOCKED_AT_KEY to "12345"),
            LegacySecurePreferences.read(context, FILE_NAME, opener),
        )
    }

    @Test
    fun readCoercesLongValuesToStrings() {
        markLegacyFilePresent()
        val opener: (Context, String) -> android.content.SharedPreferences = { ctx, _ ->
            ctx.getSharedPreferences("legacy-test-long", Context.MODE_PRIVATE).apply {
                edit().putLong(LAST_UNLOCKED_AT_KEY, 9_876L).commit()
            }
        }

        assertEquals(
            mapOf(LAST_UNLOCKED_AT_KEY to "9876"),
            LegacySecurePreferences.read(context, FILE_NAME, opener),
        )
    }

    @Test
    fun readDoesNotReturnEntriesWhenLegacyPayloadIsCorrupted() {
        legacyPrefsFile().writeText("corrupt legacy payload")

        val result = runCatching { LegacySecurePreferences.read(context, FILE_NAME) }

        assertTrue(result.isFailure || result.getOrNull() == null)
    }

    private fun markLegacyFilePresent() {
        context
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("marker", "1")
            .commit()
    }

    private fun legacyPrefsFile(): File = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$FILE_NAME.xml")

    private companion object {
        const val FILE_NAME = "test.legacy.secure"
        const val LAST_UNLOCKED_AT_KEY = "last_unlocked_at_millis"
    }
}
