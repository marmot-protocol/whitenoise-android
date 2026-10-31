package dev.ipf.whitenoise.android.media.sandbox

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-level checks of the installed still-image lane: a real Compose media grid scrolled by touch,
 * notification artwork against its deadlines, and viewer/editor cancellation mid-decode. Synthetic
 * images only; no account, relay or installed-data change.
 */
@RunWith(AndroidJUnit4::class)
@MediaContainmentAcceptance
class MediaWorkerUiDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Restores the production facade and the avatar loader's real fetcher. */
    @After
    fun tearDown() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    /** Every tile still visible after a touch scroll to the grid's end gets pixels, and no decode fails. */
    @Test
    fun mediaGridScrollRendersEveryVisibleTileWithoutDecodeFailures() {
        val result = UiLane.install("default").use { lane -> MediaWorkerUiScenarios.gridScroll(rule, lane) }
        MediaWorkerUiReport.emit(result)
        assertTrue(result.toString(), result.getBoolean("reached_end"))
        assertEquals(result.toString(), 0, result.getInt("failed"))
        assertEquals(result.toString(), 0, result.getInt("visible_unloaded"))
    }

    /**
     * Idle artwork meets both deadlines. Behind an image burst in another conversation, avatar and emoji
     * artwork still meet theirs, as that burst never shares their admission.
     */
    @Test
    fun notificationArtworkMeetsItsDeadlines() {
        AvatarImageLoader.clear()
        val avatar = MediaWorkerUiScenarios.png(AVATAR_EDGE, AVATAR_SEED)
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> avatar }
        UiLane.install("default").use { lane ->
            val idle = MediaWorkerUiScenarios.notificationArtwork(lane, BurstPlacement.NONE, attempt = 0)
            val busy = MediaWorkerUiScenarios.notificationArtwork(lane, BurstPlacement.OTHER, attempt = 1)
            MediaWorkerUiReport.emit(idle)
            MediaWorkerUiReport.emit(busy)
            assertEquals(idle.toString(), idle.getInt("requests"), idle.getInt("avatar_hits"))
            assertEquals(idle.toString(), idle.getInt("requests"), idle.getInt("emoji_hits"))
            assertEquals(busy.toString(), busy.getInt("requests"), busy.getInt("avatar_hits"))
            assertEquals(busy.toString(), busy.getInt("requests"), busy.getInt("emoji_hits"))
        }
    }

    /** Cancelling a viewer or editor decode mid-flight delivers no pixels and leaves the lane able to serve. */
    @Test
    fun viewerAndEditorCancellationMidDecodeLeaveTheLaneUsable() {
        UiLane.install("default").use { lane ->
            for (result in listOf(
                MediaWorkerUiScenarios.viewerCancellation(lane),
                MediaWorkerUiScenarios.editorCancellation(lane),
            )) {
                MediaWorkerUiReport.emit(result)
                assertTrue(result.toString(), result.getBoolean("cancelled_mid_decode"))
                assertFalse(result.toString(), result.getBoolean("cancelled_delivered_pixels"))
                assertTrue(result.toString(), result.getBoolean("follow_up_success"))
            }
        }
    }

    private companion object {
        const val AVATAR_EDGE = 256
        const val AVATAR_SEED = 7
    }
}
