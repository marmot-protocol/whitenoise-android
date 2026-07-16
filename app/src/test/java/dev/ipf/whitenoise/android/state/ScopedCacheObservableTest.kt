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
}
