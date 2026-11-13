package dev.ipf.whitenoise.android.ui.navigation

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListAvatarFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.media.PresentationMemoryTrim
import dev.ipf.whitenoise.android.state.AccountSwitchProfileSeed
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
import dev.ipf.whitenoise.android.ui.account.otherAccountAvatarTag
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the production chat-list Back route while evicted images cannot reload. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListAvatarReloadScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** Back must redraw both signed-in accounts and both compatibility group picture sources. */
    @Test
    fun returnKeepsAccountAndCompatibilityGroupAvatars() {
        val fixture = returnFixture()
        val app = fixture.appState
        val holder = MainShellStateHolder(app, SavedStateHandle())
        val controller = holder.chatsController(ACCOUNT, app.runtimeGeneration)
        val mounted = mutableStateOf(true)
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> awaitCancellation() }
        try {
            seedAvatars(fixture)
            app.updateQuickAccountSwitching(true)
            app.setAppInForeground(true)
            rule.setContent {
                if (mounted.value) WhiteNoiseTheme { MainShell(app, holder) }
            }
            rule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                controller.items.size == fixture.rows.size && !controller.isLoading
            }
            rule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            assertAvatars(fixture, "before opening the chat")
            rule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").performClick()
            rule.waitUntil(10_000) { holder.selectedChat.value?.id == fixture.target.groupIdHex }
            rule.mainClock.advanceTimeBy(1_000L)
            rule.onNodeWithTag("chat.row.${fixture.rows.first().groupIdHex}").assertDoesNotExist()
            evictAvatars(fixture)

            rule.mainClock.autoAdvance = false
            rule.onNodeWithContentDescription("Back").performClick()
            val missingOnFirstFrame = missingFirstVisibleAvatars(fixture)
            rule.mainClock.advanceTimeBy(1_000L)
            assertAvatars(fixture, "after Back with empty caches")
            assertTrue(
                "missing avatar pixels on the first return frame: $missingOnFirstFrame",
                missingOnFirstFrame.isEmpty(),
            )
            rule.onRoot().captureRoboImage("src/test/snapshots/chat_list_compatibility_avatars_return.png")
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            rule.waitForIdle()
            AvatarImageLoader.resetProfileImageFetcherForTests()
            AvatarImageLoader.clear()
            GroupAvatarImageLoader.clear()
        }
    }

    /** Models a URL-backed group and an encrypted group without a durable avatar selection. */
    private fun returnFixture(): ReturnFixture =
        ReturnFixture(
            additionalAccounts = listOf(AccountSummaryFfi(OTHER_ACCOUNT, OTHER_ID, true, false, false, true, false)),
            customizeRow = { index, row ->
                when (index) {
                    0 -> row.copy(avatarUrl = LEGACY_URL)
                    1 -> row.copy(avatar = ChatListAvatarFfi(ENCRYPTED_HASH, "", "", "", "image/png"))
                    else -> row
                }
            },
        )

    /** Seeds the existing decoders' presentation caches and the two authoritative profile URLs. */
    private fun seedAvatars(fixture: ReturnFixture) {
        fixture.appState.applyAccountSwitchProfileSeed(
            AccountSwitchProfileSeed(ConversationTimelineTestIds.ACCOUNT_ID, null, "First account", ACTIVE_URL),
        )
        fixture.appState.applyAccountSwitchProfileSeed(
            AccountSwitchProfileSeed(OTHER_ID, null, "Second account", OTHER_URL),
        )
        AvatarImageLoader.putCached(ACTIVE_URL, solidAvatar(Color.RED))
        AvatarImageLoader.putCached(OTHER_URL, solidAvatar(Color.BLUE))
        AvatarImageLoader.putCached(LEGACY_URL, solidAvatar(Color.GREEN))
        GroupAvatarImageLoader.putCached(encryptedKey(fixture), solidAvatar(Color.MAGENTA))
    }

    /** Trims the real caches without advancing the explicit clear boundary. */
    private fun evictAvatars(fixture: ReturnFixture) {
        AvatarImageLoader.trimMemory(PresentationMemoryTrim.EMPTY)
        GroupAvatarImageLoader.trimMemory(PresentationMemoryTrim.EMPTY)
        listOf(ACTIVE_URL, OTHER_URL, LEGACY_URL).forEach { assertNull(AvatarImageLoader.peek(it)) }
        assertNull(GroupAvatarImageLoader.peek(encryptedKey(fixture)))
    }

    /** Checks all four actual rendered bitmaps, so an initials placeholder cannot satisfy the test. */
    private fun assertAvatars(
        fixture: ReturnFixture,
        phase: String,
    ) {
        val missing = avatarColors(fixture).filterNot { (tag, color) -> containsAvatar(tag, color) }.map { it.first }
        assertTrue("missing avatar pixels $phase: $missing", missing.isEmpty())
    }

    /** Samples each target as soon as the route draws it through the return animation. */
    private fun missingFirstVisibleAvatars(fixture: ReturnFixture): List<String> {
        val pending = avatarColors(fixture).toMap(mutableMapOf())
        val missing = mutableListOf<String>()
        repeat(75) {
            rule.mainClock.advanceTimeByFrame()
            val visible =
                pending.filterKeys { tag ->
                    if (tag.startsWith("chat.row.")) {
                        hasPaintedRow(tag)
                    } else {
                        rule.onNodeWithTag(tag, useUnmergedTree = true).isDisplayed()
                    }
                }
            visible.forEach { (tag, color) ->
                if (!containsAvatar(tag, color, exact = false)) missing += tag
                pending.remove(tag)
            }
            if (pending.isEmpty()) return missing
        }
        error("Return never drew the avatar targets ${pending.keys}")
    }

    /** Skips rows that are measured but still invisible behind the returning route's graphics layer. */
    private fun hasPaintedRow(tag: String): Boolean {
        val target = rule.onNodeWithTag(tag, useUnmergedTree = true)
        return runCatching { target.captureToImage().toPixelMap() }.getOrNull()?.let { pixels ->
            // Require the title to be visible. The background may already be opaque from the old route.
            (0 until pixels.height).any { y ->
                (pixels.width / 4 until pixels.width / 2).any { x ->
                    val pixel = pixels[x, y]
                    pixel.alpha > 0.5f && pixel.red < 0.25f && pixel.green < 0.25f && pixel.blue < 0.25f
                }
            }
        } ?: false
    }

    /** All four expected identities have different synthetic colors. */
    private fun avatarColors(fixture: ReturnFixture): List<Pair<String, Int>> =
        listOf(
            "chats.switchProfile" to Color.RED,
            otherAccountAvatarTag(OTHER_ACCOUNT) to Color.BLUE,
            "chat.row.${fixture.rows[0].groupIdHex}" to Color.GREEN,
            "chat.row.${fixture.rows[1].groupIdHex}" to Color.MAGENTA,
        )

    /** Samples interior pixels without depending on avatar corners, rings or text antialiasing. */
    private fun containsAvatar(
        tag: String,
        color: Int,
        exact: Boolean = true,
    ): Boolean {
        val pixels = rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        return (0 until pixels.height step 2).any { y ->
            (0 until pixels.width step 2).any { x ->
                val pixel = pixels[x, y]
                if (exact) {
                    pixel.toArgb() == color
                } else if (pixel.alpha > 0f) {
                    val channels = listOf(pixel.red, pixel.green, pixel.blue)
                    val expected = listOf(Color.red(color), Color.green(color), Color.blue(color))
                    val middle = (channels.min() + channels.max()) / 2f
                    val sameHue =
                        channels.zip(expected).all { (actual, channel) -> (actual > middle) == (channel == 255) }
                    channels.max() - channels.min() > 0.4f && sameHue
                } else {
                    false
                }
            }
        }
    }

    /** Names the same account, group and hash as the production encrypted avatar loader. */
    private fun encryptedKey(fixture: ReturnFixture): String = encryptedGroupAvatarCacheKey(
        ACCOUNT,
        fixture.rows[1].groupIdHex,
        ENCRYPTED_HASH,
    )

    /** Builds a distinguishable synthetic decoded avatar. */
    private fun solidAvatar(color: Int) = Bitmap
        .createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }
        .asImageBitmap()

    private companion object {
        const val ACCOUNT = ConversationTimelineTestIds.ACCOUNT_REF
        const val OTHER_ACCOUNT = "avatar-return-other"
        val OTHER_ID = "ab".repeat(32)
        val ENCRYPTED_HASH = "bc".repeat(32)
        const val ACTIVE_URL = "https://example.invalid/active-avatar.png"
        const val OTHER_URL = "https://example.invalid/other-avatar.png"
        const val LEGACY_URL = "https://example.invalid/legacy-avatar.png"
    }
}
