package dev.ipf.whitenoise.android.ui.account

import android.app.Application
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for #2155: the interactive quick-switch path must publish
 * the other accounts' locally persisted names and avatars before the target
 * account's first frame is drawn, not a frame later.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class QuickAccountSwitchFirstFrameAvatarTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** One drawn top-bar frame: which account owned it and what each other-account chip could paint. */
    private data class Frame(
        val activeAccountRef: String?,
        val avatarColors: Map<String, Int?>,
    )

    /**
     * The real INTERACTIVE_LOCAL_ROWS lifecycle with three accounts: every frame
     * belonging to the target account already carries the other accounts' decoded
     * avatars, with no profile read or image download left to complete.
     */
    @Test
    @Suppress("LongMethod") // One ordered lifecycle: warm the source account, switch, then inspect every frame.
    fun quickSwitchPaintsOtherAccountAvatarsOnEveryTargetFrame() {
        val reads = RecordedProfileReads()
        val downloads = AtomicInteger()
        val fixture = fixture(reads, downloads)
        val frames = CopyOnWriteArrayList<Frame>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        AvatarImageLoader.clear()
        try {
            runBlocking { fixture.bootstrap() }
            val app = fixture.appState
            app.updateQuickAccountSwitching(true)
            composeRule.setContent { RecordingTopBar(app, frames) }

            // Account A is on screen with B and C beside it: the app itself reads
            // those profiles and decodes their avatars, so nothing below depends on
            // a hand-primed image cache.
            composeRule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                paintedAvatarColor(app.avatarUrl(B_ID)) == Color.RED &&
                    paintedAvatarColor(app.avatarUrl(C_ID)) == Color.BLUE
            }
            val sourceFrame = drawTopBar()
            assertTrue("the source account must already paint its neighbours", sourceFrame.contains(Color.RED))
            val downloadsBeforeSwitch = downloads.get()
            frames.clear()

            // Every profile read that starts once the target account is published is
            // blocked, so only what the switch preloaded can reach this first frame.
            var switched = false
            scope.launch {
                switched =
                    app.setActiveAccount(
                        label = B_REF,
                        preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS,
                        onActivated = { reads.seal() },
                    )
            }
            composeRule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                switched && app.activeAccountRef == B_REF
            }
            val targetFrame = drawTopBar()

            val recorded = frames.filter { it.activeAccountRef == B_REF }
            assertTrue("the target account must have drawn a frame", recorded.isNotEmpty())
            recorded.forEach { frame ->
                assertEquals("account A's avatar must be decoded for this frame", Color.GREEN, frame.avatarColors[A_ID])
                assertEquals("account C's avatar must be decoded for this frame", Color.BLUE, frame.avatarColors[C_ID])
            }
            assertTrue("the first target frame must contain account A's bitmap", targetFrame.contains(Color.GREEN))
            assertTrue("the first target frame must contain account C's bitmap", targetFrame.contains(Color.BLUE))
            assertEquals(
                "a persisted selector avatar must not be downloaded again by the switch",
                downloadsBeforeSwitch,
                downloads.get(),
            )
        } finally {
            reads.release()
            scope.cancel()
            fixture.close()
            AvatarImageLoader.clear()
            AvatarImageLoader.resetProfileImageFetcherForTests()
        }
    }

    /** An account with no persisted profile keeps the stable fallback instead of blocking the switch. */
    @Test
    fun missingLocalProfileStillActivatesTheTargetAccount() {
        val reads = RecordedProfileReads(profiles = mapOf(A_ID to A_AVATAR, B_ID to B_AVATAR))
        val downloads = AtomicInteger()
        val fixture = fixture(reads, downloads)
        try {
            runBlocking { fixture.bootstrap() }
            val app = fixture.appState
            app.updateQuickAccountSwitching(true)
            val switched =
                runBlocking {
                    app.setActiveAccount(
                        label = B_REF,
                        preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS,
                    )
                }

            assertTrue("a missing local profile must not fail the switch", switched)
            assertEquals(B_REF, app.activeAccountRef)
            assertEquals(A_AVATAR, app.avatarUrl(A_ID))
            assertNull("an unreadable profile keeps the stable fallback", app.avatarUrl(C_ID))
        } finally {
            reads.release()
            fixture.close()
            AvatarImageLoader.clear()
            AvatarImageLoader.resetProfileImageFetcherForTests()
        }
    }

    /** A switch back to the source account discards the superseded target's preloaded handoff. */
    @Test
    fun switchingBackDiscardsTheSupersededTargetSnapshot() {
        val reads = RecordedProfileReads()
        val downloads = AtomicInteger()
        val holdTargetRows = CountDownLatch(1)
        val targetRowsRequested = CountDownLatch(1)
        val fixture =
            fixture(reads, downloads) { accountRef ->
                if (accountRef == B_REF) {
                    targetRowsRequested.countDown()
                    check(holdTargetRows.await(10, TimeUnit.SECONDS))
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            runBlocking { fixture.bootstrap() }
            val app = fixture.appState
            app.updateQuickAccountSwitching(true)
            var switchedToTarget: Boolean? = null
            scope.launch {
                switchedToTarget =
                    app.setActiveAccount(B_REF, preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS)
            }
            composeRule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                targetRowsRequested.count == 0L
            }
            var switchedBack: Boolean? = null
            scope.launch {
                switchedBack =
                    app.setActiveAccount(A_REF, preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS)
            }
            holdTargetRows.countDown()
            composeRule.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idle()
                switchedToTarget != null && switchedBack != null
            }

            assertEquals("the account that was switched back to must stay active", A_REF, app.activeAccountRef)
            assertNull(
                "a superseded switch must not leave its handoff behind",
                app.consumeAccountSwitchLocalSnapshot(B_REF),
            )
        } finally {
            holdTargetRows.countDown()
            reads.release()
            scope.cancel()
            fixture.close()
            AvatarImageLoader.clear()
            AvatarImageLoader.resetProfileImageFetcherForTests()
        }
    }

    /** An account removed before the switch is never preloaded, so its profile cannot leak into the new top bar. */
    @Test
    fun removedAccountIsNotPreloadedByTheSwitch() {
        val reads = RecordedProfileReads()
        val downloads = AtomicInteger()
        val fixture = fixture(reads, downloads)
        try {
            runBlocking { fixture.bootstrap() }
            val app = fixture.appState
            app.updateQuickAccountSwitching(true)
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setAccounts", List::class.java)
                .apply { isAccessible = true }
                .invoke(app, listOf(account(A_REF, A_ID), account(B_REF, B_ID)))
            reads.observed.clear()
            runBlocking {
                app.setActiveAccount(B_REF, preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS)
            }

            assertEquals(B_REF, app.activeAccountRef)
            assertTrue("the remaining account must still be seeded", A_ID in reads.observed)
            assertTrue("a removed account must not be preloaded", C_ID !in reads.observed)
        } finally {
            reads.release()
            fixture.close()
            AvatarImageLoader.clear()
            AvatarImageLoader.resetProfileImageFetcherForTests()
        }
    }

    /** Draws the production header and records what each frame could paint for the other accounts. */
    @androidx.compose.runtime.Composable
    private fun RecordingTopBar(
        app: WhiteNoiseAppState,
        frames: MutableList<Frame>,
    ) {
        val activeAccountRef = app.activeAccountRef
        val avatarColors = OTHER_ACCOUNT_IDS.associateWith { id -> paintedAvatarColor(app.avatarUrl(id)) }
        WhiteNoiseTheme(darkTheme = false) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .testTag(TOP_BAR_TAG)
                        .drawWithContent {
                            frames += Frame(activeAccountRef, avatarColors)
                            drawContent()
                        },
                ) {
                    ChatListTopBar(
                        appState = app,
                        searchOpen = false,
                        searchQuery = "",
                        searchFocusRequester = remember { FocusRequester() },
                        onSearchQueryChange = {},
                        onSearchOpen = {},
                        onSearchClose = {},
                        onMic = {},
                        onOpenSettings = {},
                        onSwitchAccount = {},
                        selfUpdateEnabled = false,
                    )
                }
            }
        }
    }

    /** Colour of the avatar the process image cache can hand this frame, or null when it has none. */
    private fun paintedAvatarColor(url: String?): Int? =
        AvatarImageLoader.peek(url)?.let { bitmap -> bitmap.toPixelMap()[bitmap.width / 2, bitmap.height / 2].toArgb() }

    /** Forces the top bar to draw and returns the pixels it produced. */
    private fun drawTopBar(): ImageBitmap = composeRule.onNodeWithTag(TOP_BAR_TAG).captureToImage()

    /** Builds the native fixture with three signed-in accounts and distinct persisted avatars. */
    private fun fixture(
        reads: RecordedProfileReads,
        downloads: AtomicInteger,
        onPresentedChatList: (String) -> Unit = {},
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = listOf(account(A_REF, A_ID), account(B_REF, B_ID), account(C_REF, C_ID)),
        emitStartupNotification = false,
        onUserProfile = { accountIdHex -> reads.profile(accountIdHex) },
        onDisplayName = { _, accountIdHex -> reads.displayName(accountIdHex) },
        onPresentedChatList = { accountRef ->
            onPresentedChatList(accountRef)
            emptyList()
        },
        profileImageDownload = { url, _ ->
            downloads.incrementAndGet()
            solidPngBytes(AVATAR_COLORS.getValue(url))
        },
    )

    /** Signed-in local-signing account summary. */
    private fun account(
        label: String,
        accountIdHex: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /**
     * Local profile store that records who was read and can be sealed: once sealed,
     * every further read blocks until the test releases it, so no hydration started
     * after account publication can rescue a frame the preload failed to seed.
     */
    private class RecordedProfileReads(
        private val profiles: Map<String, String> = mapOf(A_ID to A_AVATAR, B_ID to B_AVATAR, C_ID to C_AVATAR),
    ) {
        val observed = CopyOnWriteArrayList<String>()
        private val sealed = AtomicBoolean(false)
        private val released = CountDownLatch(1)

        /** Blocks every later read so only pre-activation seeding can satisfy a frame. */
        fun seal() {
            sealed.set(true)
        }

        /** Unblocks any sealed read; always called from test teardown. */
        fun release() {
            released.countDown()
        }

        /** Persisted metadata for [accountIdHex], or null when that account has none. */
        fun profile(accountIdHex: String): UserProfileMetadataFfi? {
            await()
            observed += accountIdHex
            val picture = profiles[accountIdHex] ?: return null
            return UserProfileMetadataFfi(
                name = null,
                displayName = NAMES[accountIdHex],
                about = null,
                picture = picture,
                nip05 = null,
                lud16 = null,
            )
        }

        /** Persisted display name for [accountIdHex], or null when that account has none. */
        fun displayName(accountIdHex: String): String? {
            await()
            return NAMES[accountIdHex]?.takeIf { accountIdHex in profiles }
        }

        private fun await() {
            if (sealed.get()) check(released.await(10, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val TOP_BAR_TAG = "quick-switch-first-frame-top-bar"
        const val A_REF = "personal"
        const val B_REF = "studio"
        const val C_REF = "work"
        const val A_AVATAR = "https://profiles.example/personal.png"
        const val B_AVATAR = "https://profiles.example/studio.png"
        const val C_AVATAR = "https://profiles.example/work.png"
        val A_ID = "11".repeat(32)
        val B_ID = "22".repeat(32)
        val C_ID = "33".repeat(32)
        val OTHER_ACCOUNT_IDS = listOf(A_ID, B_ID, C_ID)
        val NAMES = mapOf(A_ID to "Personal profile", B_ID to "Studio profile", C_ID to "Work profile")
        val AVATAR_COLORS = mapOf(A_AVATAR to Color.GREEN, B_AVATAR to Color.RED, C_AVATAR to Color.BLUE)

        /** Whether any drawn pixel carries exactly [color], which only a decoded avatar bitmap can supply. */
        fun ImageBitmap.contains(color: Int): Boolean {
            val pixels = toPixelMap()
            return (0 until width).any { x -> (0 until height).any { y -> pixels[x, y].toArgb() == color } }
        }

        /** PNG bytes of a solid [color] square, decoded by the app exactly like a downloaded avatar. */
        fun solidPngBytes(color: Int): ByteArray =
            ByteArrayOutputStream()
                .also { stream ->
                    Bitmap
                        .createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                        .apply { eraseColor(color) }
                        .compress(Bitmap.CompressFormat.PNG, 100, stream)
                }.toByteArray()
    }
}
