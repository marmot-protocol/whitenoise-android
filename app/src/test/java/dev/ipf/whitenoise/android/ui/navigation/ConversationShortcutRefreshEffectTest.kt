package dev.ipf.whitenoise.android.ui.navigation

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The mounted host refresh reacts to presentation changes even while its visible chat rows remain frozen. */
@RunWith(RobolectricTestRunner::class)
class ConversationShortcutRefreshEffectTest {
    @get:Rule val composeRule = createComposeRule()

    /** Nicknames, native off-window projections and both decoded-avatar caches refresh without acquiring media. */
    @Test
    fun presentationChangesRefreshMountedPins() {
        val profile = mutableIntStateOf(0)
        val targets = mutableIntStateOf(0)
        val title = mutableStateOf("Original")
        val publications = mutableListOf<Triple<String, Int?, Int?>>()
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
        composeRule.setContent {
            ConversationShortcutRefreshEffect(this, "personal", true, targets.intValue.toLong(), profile.intValue) {
                publications +=
                    Triple(
                        title.value,
                        AvatarImageLoader.peekBitmap(PROFILE)?.getPixel(0, 0),
                        GroupAvatarImageLoader.peek(GROUP)?.asAndroidBitmap()?.getPixel(0, 0),
                    )
            }
        }
        awaitPublication { publications.lastOrNull()?.first == "Original" }
        composeRule.runOnIdle {
            title.value = "Private nickname"
            profile.intValue += 1
        }
        awaitPublication { publications.lastOrNull()?.first == "Private nickname" }
        composeRule.runOnIdle {
            title.value = "Native rename"
            targets.intValue += 1
        }
        awaitPublication { publications.lastOrNull()?.first == "Native rename" }
        composeRule.runOnIdle { AvatarImageLoader.putCached(PROFILE, pixels(Color.BLUE)) }
        awaitPublication { publications.lastOrNull()?.second == Color.BLUE }
        composeRule.runOnIdle { GroupAvatarImageLoader.putCached(GROUP, pixels(Color.RED)) }
        awaitPublication { publications.lastOrNull()?.third == Color.RED }
        composeRule.runOnIdle { GroupAvatarImageLoader.clear() }
        awaitPublication { publications.lastOrNull()?.third == null }
    }

    /** An unavailable account cancels queued publication, and disposal ends all observation. */
    @Test
    fun unavailableAndDisposedOwnersCannotPublish() {
        val ready = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        var publications = 0
        composeRule.setContent {
            if (mounted.value) {
                ConversationShortcutRefreshEffect(this, "personal", ready.value, 0, 0) { publications += 1 }
            }
        }
        awaitPublication { publications == 1 }
        composeRule.runOnIdle { ready.value = false }
        composeRule.waitForIdle()
        AvatarImageLoader.putCached(PROFILE, pixels(Color.GREEN))
        settle()
        assertEquals(1, publications)
        composeRule.runOnIdle { mounted.value = false }
        composeRule.waitForIdle()
        GroupAvatarImageLoader.putCached(GROUP, pixels(Color.BLUE))
        settle()
        assertEquals(1, publications)
    }

    /** Pump Android's paused main queue so the production coalescing delay reaches its publication boundary. */
    private fun awaitPublication(predicate: () -> Boolean) {
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            predicate()
        }
    }

    /** Advance beyond the production debounce after recomposition to prove an absent publication stays absent. */
    private fun settle() {
        composeRule.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)) }
        composeRule.waitForIdle()
    }

    /** Synthetic decoded pixels exercise the cache's real publication path without any network loader. */
    private fun pixels(color: Int) =
        Bitmap
            .createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            .apply {
                eraseColor(color)
            }.asImageBitmap()

    private companion object {
        const val PROFILE = "https://example.invalid/avatar.png"
        const val GROUP = "personal|group|hash"
    }
}
