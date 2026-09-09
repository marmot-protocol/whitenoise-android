package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.graphics.Color
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Captures every rendered top-bar frame while startup publishes its local identity. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListTopBarColdStartSelfProfileFirstFrameTest {
    @get:Rule val composeRule = createComposeRule()

    /** Network refresh cannot supply identity; even the first drawn frame must use local metadata. */
    @Test
    @Suppress("LongMethod") // Keep the held read, frame recorder, and cleanup in one ordered sequence.
    fun cachedSelfAvatarReferenceNeverFallsBackBeforeBlockedRefresh() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val frames = CopyOnWriteArrayList<Pair<String, String?>>()
        val localReadStarted = CountDownLatch(1)
        val releaseLocalRead = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture =
            NotificationBootstrapTestFixture(
                context = context,
                accounts = listOf(AccountSummaryFfi("self", SELF, true, false, false, true)),
                emitStartupNotification = false,
                onUserProfile = {
                    localReadStarted.countDown()
                    check(releaseLocalRead.await(10, TimeUnit.SECONDS))
                    UserProfileMetadataFfi(
                        name = null,
                        displayName = "Alice",
                        about = null,
                        picture = AVATAR,
                        nip05 = null,
                        lud16 = null,
                    )
                },
            )
        AvatarImageLoader.clear()
        AvatarImageLoader.putCached(AVATAR, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.GREEN))
        try {
            composeRule.setContent {
                if (fixture.appState.phase == AppPhase.Ready) {
                    val presentation = fixture.appState.chatMemberTitleCached(SELF) to fixture.appState.avatarUrl(SELF)
                    WhiteNoiseTheme(darkTheme = false) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Box(
                                Modifier.fillMaxWidth().testTag(TAG).drawWithContent {
                                    frames += presentation
                                    drawContent()
                                },
                            ) {
                                ChatListTopBar(
                                    appState = fixture.appState,
                                    searchOpen = false,
                                    searchQuery = "",
                                    searchFocusRequester = remember { FocusRequester() },
                                    onSearchQueryChange = {},
                                    onSearchOpen = {},
                                    onSearchClose = {},
                                    onMic = {},
                                    onOpenSettings = {},
                                    onSwitchAccount = {},
                                )
                            }
                        }
                    }
                }
            }
            scope.launch { fixture.appState.bootstrap() }
            composeRule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                localReadStarted.count == 0L
            }
            assertEquals(AppPhase.Bootstrapping, fixture.appState.phase)
            assertTrue("no first frame may precede the held local read", frames.isEmpty())
            releaseLocalRead.countDown()
            composeRule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                fixture.appState.phase == AppPhase.Ready
            }
            // Normal unit runs disable Roborazzi capture; still require an actual draw in that mode.
            composeRule.onNodeWithTag(TAG).captureToImage()
            composeRule.onNodeWithTag(TAG).captureRoboImage(
                "src/test/snapshots/startup_self_profile_first_frame_light.png",
            )
            assertTrue(frames.isNotEmpty())
            frames.forEach { assertEquals("Alice" to AVATAR, it) }
            assertEquals("metadata readiness does not require roster reads", 0, fixture.memberProjectionCalls.get())
        } finally {
            releaseLocalRead.countDown()
            scope.cancel()
            fixture.close()
            AvatarImageLoader.clear()
            AvatarImageLoader.resetProfileImageFetcherForTests()
        }
    }

    private companion object {
        val SELF = "11".repeat(32)
        const val AVATAR = "https://profiles.example/alice.png"
        const val TAG = "startup-self-profile"
    }
}
