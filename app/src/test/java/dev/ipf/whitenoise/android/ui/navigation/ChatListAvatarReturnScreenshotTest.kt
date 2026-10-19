package dev.ipf.whitenoise.android.ui.navigation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AvatarAcquisitionStateFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.AvatarBytesFfi
import dev.ipf.whitenoise.android.core.AvatarCacheDiagnostics
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.durableAvatar
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Exercises the production list/conversation/Back route after capacity eviction, with local reads held. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListAvatarReturnScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** Releases only synthetic process pixels and counters. */
    @After
    fun clearPixels() {
        AvatarCacheDiagnostics.stop()
        AvatarImageLoader.clear()
    }

    /** A light chat list keeps known local pixels through conversation navigation and LRU churn. */
    @Test
    fun knownAvatarsSurviveConversationReturnInLightTheme() = verifyReturn(dark = false)

    /** Dark rendering follows the same retained working-set contract. */
    @Test
    fun knownAvatarsSurviveConversationReturnInDarkTheme() = verifyReturn(dark = true)

    /** Holds every post-eviction local read so a late decode cannot disguise a missing returning avatar. */
    @Suppress("LongMethod") // One navigation fixture owns the read gate, retained shell, and cleanup throughout the case.
    private fun verifyReturn(dark: Boolean) {
        AvatarImageLoader.clear()
        val bytes = avatarPng()
        val holdReads = AtomicBoolean(false)
        val releaseReads = CompletableDeferred<Unit>()
        val fixture =
            ReturnFixture(
                avatarAssetForRow = { asset(it.groupIdHex) },
                readAvatars = { references ->
                    if (holdReads.get()) releaseReads.await()
                    references.map { reference ->
                        AvatarBytesFfi(
                            reference,
                            AvatarAvailabilityFfi.READY,
                            1uL,
                            bytes.size.toULong(),
                            false,
                            bytes,
                            "image/png",
                            64u,
                            64u,
                        )
                    }
                },
            )
        runBlocking {
            fixture.rows.forEach { row ->
                assertNotNull(fixture.appState.durableAvatar(asset(row.groupIdHex), acquireMissing = false))
            }
        }
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(fixture.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.runtimeGeneration)
        fixture.appState.setAppInForeground(true)
        rule.setContent {
            if (mounted.value) WhiteNoiseTheme(darkTheme = dark) { MainShell(fixture.appState, holder) }
        }
        try {
            rule.waitUntil(10_000) {
                rule.waitForIdle()
                controller.items.size == fixture.rows.size && !controller.isLoading
            }
            rule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            assertAvatarPixels(fixture.rows.first().groupIdHex)
            rule.onNodeWithTag("chat.row.${fixture.target.groupIdHex}").performClick()
            rule.waitUntil(10_000) {
                rule.waitForIdle()
                holder.selectedChat.value?.id == fixture.target.groupIdHex
            }
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            rule.onNodeWithTag("chat.row.${fixture.rows.first().groupIdHex}").assertDoesNotExist()
            holdReads.set(true)
            val readsBeforeReturn = fixture.avatarReads.get()
            AvatarCacheDiagnostics.start()
            evictWorkingSet()
            fixture.rows.forEach { row ->
                assertNull(AvatarImageLoader.cachedImage(checkNotNull(asset(row.groupIdHex).cacheKey(ACCOUNT))))
            }
            assertTrue(
                "the fixture must cause capacity eviction",
                AvatarCacheDiagnostics.snapshot().profile.evictions > 0,
            )

            rule.mainClock.autoAdvance = false
            rule.onNodeWithContentDescription("Back").performClick()
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            fixture.rows.forEach { assertAvatarPixels(it.groupIdHex) }
            val theme = if (dark) "dark" else "light"
            rule.onNodeWithTag("chat.row.${fixture.rows.first().groupIdHex}")
                .captureRoboImage("src/test/snapshots/chat_list_avatar_return_$theme.png")
            assertEquals("retained pixels need no repeat local read", readsBeforeReturn, fixture.avatarReads.get())
            assertEquals("READY local assets need no acquisition", 0, fixture.avatarRequests.get())
            assertEquals("navigation needs no repeat decode", 0L, AvatarCacheDiagnostics.snapshot().profile.decodeCalls)
        } finally {
            releaseReads.complete(Unit)
            rule.mainClock.autoAdvance = true
            rule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            rule.waitForIdle()
        }
    }

    /** Checks real row pixels, so a correctly laid-out initials placeholder cannot satisfy the regression. */
    private fun assertAvatarPixels(groupId: String) {
        val pixels = rule.onNodeWithTag("chat.row.$groupId").captureToImage().toPixelMap()
        var matching = 0
        for (y in 0 until pixels.height step 2) {
            for (x in 0 until pixels.width step 2) {
                val pixel = pixels[x, y]
                if (
                    abs(pixel.red - 227f / 255f) < 0.01f &&
                    abs(pixel.green - 11f / 255f) < 0.01f &&
                    abs(pixel.blue - 92f / 255f) < 0.01f
                ) {
                    matching++
                }
            }
        }
        assertTrue("known avatar pixels missing for $groupId ($matching samples)", matching > 100)
    }

    /** Exceeds the existing 16 MiB LRU without calling the explicit cache-clear invalidation boundary. */
    private fun evictWorkingSet() {
        repeat(20) { index ->
            AvatarImageLoader.putCached(
                "marmot-avatar:churn:$index@1",
                Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).asImageBitmap(),
            )
        }
    }

    /** A stable MDK-selected asset whose durable bytes remain available independently of decoded eviction. */
    private fun asset(groupId: String) =
        AvatarAssetFfi(
            groupId,
            "retained-$groupId",
            AvatarAvailabilityFfi.READY,
            AvatarAcquisitionStateFfi.IDLE,
            1uL,
            1_024uL,
        )

    /** Encodes fixture data before any bounded preparation or navigation measurement starts. */
    private fun avatarPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(227, 11, 92))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ACCOUNT = ConversationTimelineTestIds.ACCOUNT_REF
    }
}
