package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScopedCacheObservableTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun targetedRemovalInvalidatesObserverWithoutClearingOtherEntriesOrCaches() {
        val registry = ScopedCacheRegistry()
        val hiddenMessages =
            ScopedCache<String, MutableState<Set<String>>>(
                registry = registry,
                name = "hidden-messages",
                maxEntries = 4,
                observable = true,
            )
        val profiles = ScopedCache<String, String>(registry, name = "profiles", maxEntries = 4)
        hiddenMessages.put("account-b:group", mutableStateOf(setOf("message-b")))
        profiles.put("profile", "Alice")
        var persistedAccountA = setOf("message-a")
        var rendered = emptySet<String>()

        composeRule.setContent {
            rendered = hiddenMessages["account-a:group"]?.value ?: persistedAccountA
        }
        composeRule.waitForIdle()
        assertEquals(setOf("message-a"), rendered)

        composeRule.runOnUiThread {
            persistedAccountA = emptySet()
            hiddenMessages.removeAll { it.startsWith("account-a:") }
        }
        composeRule.waitForIdle()

        assertTrue(rendered.isEmpty())
        assertEquals(setOf("message-b"), hiddenMessages["account-b:group"]?.value)
        assertEquals("Alice", profiles["profile"])
    }

    @Test
    fun registryClearInvalidatesObserver() {
        val registry = ScopedCacheRegistry()
        val cache =
            ScopedCache<String, MutableState<Set<String>>>(
                registry = registry,
                name = "hidden-messages",
                maxEntries = 2,
                observable = true,
            )
        cache.put("account-a:group", mutableStateOf(setOf("message-a")))
        var rendered = emptySet<String>()

        composeRule.setContent {
            rendered = cache["account-a:group"]?.value.orEmpty()
        }
        composeRule.waitForIdle()
        assertEquals(setOf("message-a"), rendered)

        composeRule.runOnUiThread { registry.clearAll() }
        composeRule.waitForIdle()

        assertTrue(rendered.isEmpty())
    }

    /**
     * Filling a key nobody saw missing, and writes that change no entry, leave readers of other keys alone: the first
     * outgoing bubble filling its colour slot must not recompose every other bubble.
     */
    @Test
    fun fillingAnUnseenKeyAndNoOpWritesDoNotInvalidateOtherReaders() {
        val cache = observableCache<Int>(maxEntries = 8)
        var compositions = 0
        composeRule.setContent {
            cache.getOrPut("incoming") { mutableStateOf(1) }.value
            compositions += 1
        }
        composeRule.waitForIdle()
        val settled = compositions

        composeRule.runOnUiThread {
            cache.getOrPut("outgoing") { mutableStateOf(2) }
            val held = cache.getOrPut("incoming") { mutableStateOf(3) }
            cache.put("incoming", held)
            cache.remove("absent")
            cache.removeAll { it == "absent" }
        }
        composeRule.waitForIdle()

        assertEquals(settled, compositions)
    }

    /** A reader that saw a key missing fell back to another source, so filling that key must reach it. */
    @Test
    fun fillingAKeyAReaderSawMissingInvalidatesThatReader() {
        val cache = observableCache<String>(maxEntries = 8)
        var rendered = ""
        composeRule.setContent {
            rendered = cache["key"]?.value ?: "fallback"
        }
        composeRule.waitForIdle()
        assertEquals("fallback", rendered)

        composeRule.runOnUiThread { cache.getOrPut("key") { mutableStateOf("cached") } }
        composeRule.waitForIdle()

        assertEquals("cached", rendered)
    }

    /** Evicting or replacing an entry a reader holds still invalidates that reader. */
    @Test
    fun evictingOrReplacingAHeldEntryInvalidatesItsReader() {
        val cache = observableCache<String>(maxEntries = 1)
        var rendered = ""
        var compositions = 0
        composeRule.setContent {
            rendered = cache.getOrPut("held") { mutableStateOf("first") }.value
            compositions += 1
        }
        composeRule.waitForIdle()
        val settled = compositions

        composeRule.runOnUiThread { cache.getOrPut("other") { mutableStateOf("evicts") } }
        composeRule.waitForIdle()
        assertTrue("an eviction must reach the reader of the evicted entry", compositions > settled)

        composeRule.runOnUiThread { cache.put("held", mutableStateOf("second")) }
        composeRule.waitForIdle()
        assertEquals("second", rendered)
    }

    /** Builds an observable cache of mutable states, the shape every observable account-scoped cache uses. */
    private fun <T> observableCache(maxEntries: Int): ScopedCache<String, MutableState<T>> =
        ScopedCache(
            registry = ScopedCacheRegistry(),
            name = "observable",
            maxEntries = maxEntries,
            observable = true,
        )
}
