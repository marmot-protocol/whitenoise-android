package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.AvatarLoadRecovery
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Production avatar composition must recover in place, without an identity change or navigation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AvatarRecoveryScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Isolates process-local loader state without touching any application storage. */
    @Before
    fun resetLoader() {
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
    }

    /** Retires detached test work after the composition has been exercised. */
    @After
    fun clearLoader() = resetLoader()

    /** The same mounted production avatar transitions from offline fallback to downloaded pixels. */
    @Test
    fun failedVisibleAvatarRecoversWithoutNavigation() {
        val online = AtomicBoolean(false)
        AvatarImageLoader.attachProfileImageFetcher { _, _ ->
            check(online.get()) { "synthetic offline request" }
            Base64.getDecoder().decode(PNG)
        }
        runBlocking { assertNull(AvatarImageLoader.load(URL)) }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Box(Modifier.size(96.dp).padding(16.dp).testTag(TAG)) {
                    Avatar(title = "Avatar Test", seed = "avatar-test", size = 64.dp, pictureUrl = URL)
                }
            }
        }
        composeRule.onNodeWithText("AT").assertExists()
        composeRule.onNodeWithTag(TAG).captureToImage()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/avatar_recovery_offline.png")
        composeRule.runOnIdle {
            online.set(true)
            AvatarLoadRecovery.onNetworkRestored()
        }
        composeRule.waitUntil(5_000) { AvatarImageLoader.peek(URL) != null }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AT").assertDoesNotExist()
        composeRule.onNodeWithTag(TAG).captureToImage()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/avatar_recovery_connected.png")
    }

    /** Native initialization wakes an already-mounted avatar whose first request had no fetcher. */
    @Test
    fun firstFetcherAttachmentRecoversUnavailableAvatar() {
        composeRule.setContent {
            Avatar(title = "Avatar Test", seed = "avatar-test", size = 64.dp, pictureUrl = URL)
        }
        composeRule.onNodeWithText("AT").assertExists()
        composeRule.runOnIdle {
            AvatarImageLoader.attachProfileImageFetcher { _, _ -> Base64.getDecoder().decode(PNG) }
        }
        composeRule.waitUntil(5_000) { AvatarImageLoader.peek(URL) != null }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AT").assertDoesNotExist()
    }

    /** A reused production slot cannot show pixels completed for its previous seed and URL. */
    @Test
    fun identityReplacementDoesNotPublishLateOldPixels() {
        val identity = mutableStateOf("old")
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        AvatarImageLoader.attachProfileImageFetcher { url, _ ->
            if (url.endsWith("old")) {
                oldStarted.complete(Unit)
                releaseOld.await()
                Base64.getDecoder().decode(PNG)
            } else {
                error("new identity has no remote image")
            }
        }
        try {
            composeRule.setContent {
                Avatar(
                    title = "${identity.value} avatar",
                    seed = identity.value,
                    size = 64.dp,
                    pictureUrl = "$URL/${identity.value}",
                )
            }
            composeRule.waitUntil(5_000) { oldStarted.isCompleted }
            composeRule.runOnIdle { identity.value = "new" }
            composeRule.onNodeWithText("NA").assertExists()
            composeRule.runOnIdle { releaseOld.complete(Unit) }
            composeRule.waitUntil(5_000) { AvatarImageLoader.peek("$URL/old") != null }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("NA").assertExists()
            composeRule.onNodeWithText("OA").assertDoesNotExist()
            assertEquals(null, AvatarImageLoader.peek("$URL/new"))
        } finally {
            releaseOld.complete(Unit)
        }
    }

    private companion object {
        const val URL = "https://profiles.example/avatar-test"
        const val TAG = "avatar-recovery"
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
