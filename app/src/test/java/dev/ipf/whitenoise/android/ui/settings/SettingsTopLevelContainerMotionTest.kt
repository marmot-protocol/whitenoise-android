package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The production top-level host (topLevelBubble with cardMorph, one account, the avatar's measured
 * origin) opens Settings as ONE container growing from the avatar, never several outlined groups
 * fanning out of it, and Back contracts that container into the same avatar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsTopLevelContainerMotionTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var originalAnimatorScale = 1f

    @Before
    fun enableMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(1f)
    }

    @After
    fun restoreMotion() = setAnimatorScale(originalAnimatorScale)

    @Test fun amoledOpensOneContainerFromTheAvatarAndClosesIntoIt() = assertAvatarContainer(amoled = true)

    @Test fun lightOpensOneContainerFromTheAvatarAndClosesIntoIt() = assertAvatarContainer(amoled = false)

    @Test fun containerStartsAsTheAvatarCircleAndEndsAsTheSquareHost() {
        val avatar = androidx.compose.ui.geometry.Rect(12f, 12f, 52f, 52f)
        val size = androidx.compose.ui.geometry.Size(360f, 780f)
        val start = settingsContainerShape(avatar, size, rtl = false, presence = 0f, fallbackRadius = 28f)
        assertEquals(avatar, start.boundingRect)
        assertEquals(20f, start.topLeftCornerRadius.x, 0.01f)
        val end = settingsContainerShape(avatar, size, rtl = true, presence = 1f, fallbackRadius = 28f)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 360f, 780f), end.boundingRect)
        assertEquals(0f, end.topLeftCornerRadius.x, 0f)
        // Body ink and the shape are functions of presence alone, so reversal is continuous.
        assertEquals(0f, settingsContainerBodyAlpha(SETTINGS_CONTAINER_BODY_FADE_START), 0f)
        assertEquals(1f, settingsContainerBodyAlpha(1f), 0f)
        assertEquals(0f, settingsContainerShapeAlpha(0f), 0f)
    }

    @Suppress("LongMethod")
    private fun assertAvatarContainer(amoled: Boolean) {
        val section = mutableStateOf(0)
        var morph: SettingsCardMorph? = null
        rule.setContent {
            WhiteNoiseTheme(darkTheme = amoled, amoled = amoled) {
                SettingsRouteMotion(
                    target = section.value,
                    depth = section.value,
                    ownerKey = "account",
                    topLevelBubble = true,
                    cardMorph = true,
                ) { route ->
                    morph = LocalSettingsCardMorph.current
                    if (route == 0) {
                        ChatsWithAvatar { section.value = 1 }
                    } else {
                        SettingsScreen(
                            homeState(),
                            { section.value = 0 },
                            {},
                            {},
                            null,
                            {},
                            SettingsHomeViewport.Top,
                            {},
                            selfUpdateEnabled = true,
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.waitForIdle()
        val chats = frame()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        var narrowFrames = 0
        repeat(OPEN_FRAMES) {
            step()
            assertFalse("the top-level pair never runs the multi-group contour morph", morph!!.page!!.active)
            if (assertOneContainerFromAvatar(frame(), "open frame $it")) narrowFrames++
        }
        assertTrue("open must start as one container at the avatar", narrowFrames > 0)
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()

        rule.runOnUiThread {
            section.value = 0
            Snapshot.sendApplyNotifications()
        }
        val closing = List(CLOSE_FRAMES) { step().let { frame() } }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        val settled = frame()
        assertEquals("settled Chats shows the avatar again", chats.getPixel(32, 32), settled.getPixel(32, 32))
        // Late in the close the container still covers the avatar while the far chat rows are only dimmed.
        val landing =
            closing.indexOfFirst { frame ->
                covered(frame.getPixel(32, 32), settled.getPixel(32, 32)) &&
                    onlyDimmed(frame.getPixel(FAR_X, FAR_Y), settled.getPixel(FAR_X, FAR_Y)) &&
                    onlyDimmed(frame.getPixel(PEN_X, PEN_Y), settled.getPixel(PEN_X, PEN_Y))
            }
        assertTrue("close contracts into the avatar, not elsewhere", landing >= 0)
    }

    /** Returns whether [frame] showed one early container anchored at the avatar; fails on any other early shape. */
    private fun assertOneContainerFromAvatar(
        frame: Bitmap,
        label: String,
    ): Boolean {
        val reference = frame.getPixel(frame.width - 2, frame.height - 2)
        var left = frame.width
        var top = frame.height
        var right = -1
        var bottom = -1
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                if (differs(frame.getPixel(x, y), reference)) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        // Before the Settings body fades in, the frame is backplate plus one growing container.
        if (right < 0 || right - left > frame.width * NARROW_FRACTION) return false
        assertTrue("$label anchored at the avatar: $left,$top", left <= AVATAR_LEFT + 2 && top <= AVATAR_LEFT + 2)
        assertTrue("$label covers the avatar centre", left <= 32 && top <= 32 && right >= 32 && bottom >= 32)
        val row = (top + bottom) / 2
        val column = (left + right) / 2
        val across = (0 until frame.width).count { x -> x > 0 && rises(frame, x - 1, row, x, row, reference) }
        val down = (0 until frame.height).count { y -> y > 0 && rises(frame, column, y - 1, column, y, reference) }
        // One filled shape enters once; one outlined shape enters at each edge. Several groups enter more often.
        assertTrue("$label is one container across ($across edges)", across in 1..2)
        assertTrue("$label is one container down ($down edges)", down in 1..2)
        return true
    }

    private fun rises(
        frame: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        reference: Int,
    ) = !differs(frame.getPixel(x0, y0), reference) && differs(frame.getPixel(x1, y1), reference)

    private fun step() {
        rule.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    private fun homeState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            app,
            DraftStore(
                object : DraftPersistence {
                    override fun read(): Map<String, String> = emptyMap()

                    override fun write(
                        key: String,
                        value: String?,
                    ) = Unit
                },
            ),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    dev.ipf.marmotkit.AccountSummaryFfi(
                        label = "motion-fixture",
                        accountIdHex = "11".repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = "motion-fixture",
            profileReader = { null },
            profileDisplayNameReader = { "Motion fixture" },
        )

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        app.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private companion object {
        const val AVATAR_TAG = "fixture.avatar"
        const val AVATAR_LEFT = 12
        const val SETTLE_MILLIS = 1_000L
        const val OPEN_FRAMES = 6
        const val CLOSE_FRAMES = 18
        const val NARROW_FRACTION = 0.4f
        const val FAR_X = 300
        const val FAR_Y = 400
        const val PEN_X = 320
        const val PEN_Y = 740
        const val CHANNEL_TOLERANCE = 12
        const val SCRIM_LIMIT = 0.34f
    }

    private fun differs(
        a: Int,
        b: Int,
    ) = channels(a).zip(channels(b)).any { (x, y) -> abs(x - y) > CHANNEL_TOLERANCE }

    /** Darker than the container scrim alone could make it, or lighter at all: something is drawn over it. */
    private fun covered(
        frame: Int,
        settled: Int,
    ) = channels(frame).zip(channels(settled)).any { (f, s) ->
        f < s * (1f - SCRIM_LIMIT) - 2 || f > s + CHANNEL_TOLERANCE
    }

    /** At most the uniform scrim darkens this pixel; no container or picture covers it. */
    private fun onlyDimmed(
        frame: Int,
        settled: Int,
    ) = channels(frame).zip(channels(settled)).all { (f, s) -> f <= s + 2 && f >= s * (1f - SCRIM_LIMIT) - 2 }

    private fun channels(color: Int) = listOf(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)
}

/** A chat list stand-in: the account avatar wired exactly like the production top bar, over bright rows. */
@Composable
@Suppress("FunctionNaming")
private fun ChatsWithAvatar(open: () -> Unit) {
    val inset = 4.dp
    val (origin, click) = rememberSettingsRouteClick(open, originInset = inset)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(top = 72.dp)) {
            repeat(10) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(Color(0xFFFFC107)),
                )
            }
        }
        Box(
            Modifier
                .padding(start = 8.dp, top = 8.dp)
                .size(48.dp)
                .then(origin)
                .settingsCardMorph(SettingsHomeOriginAnchor, inset)
                .testTag("fixture.avatar")
                .clickable { click() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(Color(0xFF2E7D32), CircleShape))
        }
    }
}
