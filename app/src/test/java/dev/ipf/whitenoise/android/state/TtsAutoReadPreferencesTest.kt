package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.functionBody
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsAutoReadPreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun freshInstallDefaultsToGlobalOffAndInherit() {
        val prefs = TtsAutoReadPreferences(context)

        assertFalse(prefs.state.value.globalDefaultEnabled)
        assertEquals(emptyMap<String, TtsAutoReadOverride>(), prefs.state.value.overrides)
        assertFalse(prefs.isConversationAutoRead("account-a", "group-a"))
    }

    @Test
    fun legacyEnabledKeysMigrateToExplicitOnOverrides() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(TtsAutoReadPreferences.KEY_LEGACY_ENABLED, setOf("account-a|group-a", "account-a|group-b"))
            .commit()

        val prefs = TtsAutoReadPreferences(context)

        assertEquals(
            mapOf(
                "account-a|group-a" to TtsAutoReadOverride.ON,
                "account-a|group-b" to TtsAutoReadOverride.ON,
            ),
            prefs.state.value.overrides,
        )
        assertTrue(prefs.isConversationAutoRead("account-a", "group-a"))
        assertFalse(prefs.isConversationAutoRead("account-a", "group-c"))

        val reloaded = TtsAutoReadPreferences(context)
        assertEquals(prefs.state.value.overrides, reloaded.state.value.overrides)
        assertNull(
            reloaded.preferencesForTest().getStringSet(TtsAutoReadPreferences.KEY_LEGACY_ENABLED, null),
        )
    }

    @Test
    fun legacyAbsentKeysBecomeInheritAfterMigration() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(TtsAutoReadPreferences.KEY_LEGACY_ENABLED, setOf("account-a|group-a"))
            .commit()

        val prefs = TtsAutoReadPreferences(context)

        assertNull(prefs.overrideFor("account-a", "group-b"))
        assertFalse(prefs.isConversationAutoRead("account-a", "group-b"))
    }

    @Test
    fun legacyMigrationMergesExistingOverridesWithoutDataLoss() {
        val raw =
            context.getSharedPreferences(
                TtsAutoReadPreferences.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
        raw
            .edit()
            .putStringSet("autoReadConversations", setOf("acct|legacy-on"))
            .putStringSet("overrideOnConversations", setOf("acct|existing-on"))
            .putStringSet("overrideOffConversations", setOf("acct|existing-off"))
            .putBoolean("legacyBinaryMigrated", false)
            .commit()

        val migrated = TtsAutoReadPreferences(context)

        assertEquals(TtsAutoReadOverride.ON, migrated.overrideFor("acct", "legacy-on"))
        assertEquals(TtsAutoReadOverride.ON, migrated.overrideFor("acct", "existing-on"))
        assertEquals(TtsAutoReadOverride.OFF, migrated.overrideFor("acct", "existing-off"))
    }

    @Test
    fun malformedLegacyKeysDoNotBecomeOverrides() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(
                TtsAutoReadPreferences.KEY_LEGACY_ENABLED,
                setOf("", "|group", "account|", "bad", "account-a|group-a"),
            ).commit()

        val prefs = TtsAutoReadPreferences(context)

        assertEquals(mapOf("account-a|group-a" to TtsAutoReadOverride.ON), prefs.state.value.overrides)
    }

    @Test
    fun globalDefaultPersistsInOneWrite() {
        val prefs = TtsAutoReadPreferences(context)
        prefs.setGlobalDefaultEnabled(true)

        assertTrue(prefs.state.value.globalDefaultEnabled)
        assertTrue(TtsAutoReadPreferences(context).state.value.globalDefaultEnabled)

        prefs.setGlobalDefaultEnabled(false)
        assertFalse(TtsAutoReadPreferences(context).state.value.globalDefaultEnabled)
    }

    @Test
    fun explicitOverridesPersistPerAccountGroup() {
        val prefs = TtsAutoReadPreferences(context)

        prefs.setConversationOverride("account-a", "group-a", TtsAutoReadOverride.ON)
        prefs.setConversationOverride("account-b", "group-a", TtsAutoReadOverride.OFF)

        assertTrue(prefs.isConversationAutoRead("account-a", "group-a"))
        assertFalse(prefs.isConversationAutoRead("account-b", "group-a"))
        assertFalse(prefs.isConversationAutoRead("account-a", "group-b"))

        val reloaded = TtsAutoReadPreferences(context)
        assertEquals(prefs.state.value.overrides, reloaded.state.value.overrides)
    }

    @Test
    fun clearOverrideReturnsToInherit() {
        val prefs = TtsAutoReadPreferences(context)
        prefs.setGlobalDefaultEnabled(true)
        prefs.setConversationOverride("account-a", "group-a", TtsAutoReadOverride.OFF)

        assertFalse(prefs.isConversationAutoRead("account-a", "group-a"))

        prefs.clearConversationOverride("account-a", "group-a")

        assertNull(prefs.overrideFor("account-a", "group-a"))
        assertTrue(prefs.isConversationAutoRead("account-a", "group-a"))
        assertFalse("account-a|group-a" in TtsAutoReadPreferences(context).state.value.overrides)
    }

    @Test
    fun accountIsolation() {
        val prefs = TtsAutoReadPreferences(context)
        prefs.setConversationOverride("account-a", "GROUP-A", TtsAutoReadOverride.ON)

        assertTrue(prefs.isConversationAutoRead("account-a", "group-a"))
        assertFalse(prefs.isConversationAutoRead("account-b", "group-a"))
    }

    @Test
    fun concurrentUpdatesRetainEveryOverrideAndPersist() {
        val prefs = TtsAutoReadPreferences(context)
        val writerCount = 32
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val expectedOverrides =
            (0 until writerCount).associate { index ->
                "account-a|group-$index" to
                    if (index % 2 == 0) TtsAutoReadOverride.ON else TtsAutoReadOverride.OFF
            }
        val futures =
            (0 until writerCount).map { index ->
                executor.submit {
                    start.await()
                    prefs.setConversationOverride(
                        accountRef = "account-a",
                        groupIdHex = "group-$index",
                        override = expectedOverrides.getValue("account-a|group-$index"),
                    )
                }
            }

        try {
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(expectedOverrides, prefs.state.value.overrides)
        assertEquals(expectedOverrides, TtsAutoReadPreferences(context).state.value.overrides)
    }

    @Test
    fun migrationIsIdempotentAcrossConcurrentLoads() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(TtsAutoReadPreferences.KEY_LEGACY_ENABLED, setOf("account-a|group-a"))
            .commit()

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val futures =
            (0 until 16).map {
                executor.submit {
                    start.await()
                    TtsAutoReadPreferences(context)
                }
            }
        try {
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val prefs = TtsAutoReadPreferences(context)
        assertEquals(mapOf("account-a|group-a" to TtsAutoReadOverride.ON), prefs.state.value.overrides)
    }

    @Test
    fun mutationsRouteThroughSinglePublisher() {
        val source =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/state/TtsAutoReadPreferences.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/state/TtsAutoReadPreferences.kt"),
            ).firstOrNull(File::exists)?.readText() ?: error("Missing TtsAutoReadPreferences.kt")
        val setOverride = source.functionBody("setConversationOverride")
        val publishLocked = source.functionBody("publishLocked")

        assertTrue("mutations must be serialized", "synchronized(mutationLock)" in setOverride)
        assertTrue("state must publish atomically", "publishLocked(" in setOverride)
        assertTrue("_state.value = state" in publishLocked)
    }
}
