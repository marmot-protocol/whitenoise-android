package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live Chats route under the opening top-level Settings page is inert, and a reversal never
 * restores Settings ink the previous leg did not draw.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsTopLevelUnderlayTest {
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

    @Test fun chatsUnderTheOpeningContainerLosesFocusSemanticsAndBack() {
        val section = mutableStateOf(0)
        var fieldFocused = false
        var chatsComposed = false
        var chatsBacks = 0
        var hostBacks = 0
        host(section) { route ->
            BackHandler { hostBacks++ }
            if (route == 0) {
                DisposableEffect(Unit) {
                    chatsComposed = true
                    onDispose { chatsComposed = false }
                }
                BackHandler { chatsBacks++ }
                Chats(open = { section.value = 1 }) {
                    var text by remember { mutableStateOf("draft") }
                    BasicTextField(
                        text,
                        { text = it },
                        Modifier.offset(y = 200.dp).width(200.dp).testTag(FIELD).onFocusChanged {
                            fieldFocused = it.isFocused
                        },
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow))
            }
        }
        rule.onNodeWithTag(FIELD).performClick()
        rule.waitForIdle()
        assertTrue("the chat list field holds focus before Settings opens", fieldFocused)

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR).performClick()
        repeat(OPEN_FRAMES) { step() }
        assertTrue("Chats is still composed beneath the arriving Settings page", chatsComposed)
        assertTrue("focus held in the underlay is cleared", !fieldFocused)
        rule.onNodeWithTag(FIELD).assertDoesNotExist()
        rule.onNodeWithTag(AVATAR).assertDoesNotExist()
        rule.runOnUiThread { checkNotNull(backDispatcher).onBackPressed() }
        assertEquals("the underlay's Back handler is inert", 0, chatsBacks)
        assertEquals("Back reaches the live route host instead", 1, hostBacks)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("Chats leaves when the open settles", !chatsComposed)
    }

    @Test fun reopeningFromAPicturelessCloseBringsSettingsInkInFromNone() {
        val section = mutableStateOf(0)
        host(section) { route ->
            if (route == 0) {
                // Like a chat list with screenshots blocked: no Settings picture may be kept for the close.
                val morph = LocalSettingsCardMorph.current
                DisposableEffect(morph) {
                    val release = morph?.holdSecure()
                    onDispose { release?.invoke() }
                }
                Chats(open = { section.value = 1 }) {}
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Box(Modifier.padding(start = 16.dp, top = 60.dp).size(120.dp, 80.dp).background(Color.Black))
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR).performClick()
        repeat(MID_OPEN_FRAMES) { step() }
        println("INKDBG open ${ink(frame())}")
        assertTrue("Settings ink shows mid-open", ink(frame()) > MID_OPEN_INK)

        update { section.value = 0 }
        repeat(2) { step() }
        println("INKDBG close ${ink(frame())}")
        assertTrue("the pictureless close draws no Settings ink", ink(frame()) < NO_INK)

        rule.onNodeWithTag(AVATAR).performClick()
        var drawn = ink(frame())
        repeat(REOPEN_FRAMES) {
            step()
            val next = ink(frame())
            println("INKDBG reopen $next")
            assertTrue("reopened ink grows gradually: $drawn -> $next", next - drawn <= MAX_INK_STEP)
            drawn = next
        }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        assertTrue("settled Settings shows its ink", ink(frame()) > SETTLED_INK)
    }

    @Test fun turningBackDuringAReopenKeepsTheInkItWasDrawing() {
        val section = mutableStateOf(0)
        val secure = mutableStateOf(true)
        host(section) { route ->
            if (route == 0) {
                val morph = LocalSettingsCardMorph.current
                val held = secure.value
                DisposableEffect(morph, held) {
                    val release = if (held) morph?.holdSecure() else null
                    onDispose { release?.invoke() }
                }
                Chats(open = { section.value = 1 }) {}
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Box(Modifier.padding(start = 16.dp, top = 60.dp).size(120.dp, 80.dp).background(Color.Black))
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR).performClick()
        repeat(TURN_OPEN_FRAMES) { step() }
        update { section.value = 0 }
        repeat(2) { step() }
        // The reopen ramps its ink from none; screenshots are allowed again, so it records Settings.
        rule.onNodeWithTag(AVATAR).performClick()
        step()
        update { secure.value = false }
        repeat(2) { step() }
        val reopened = ink(frame())
        assertTrue("the reopen is still bringing its ink in: $reopened", reopened < SETTLED_INK)
        update { section.value = 0 }
        step()
        val turned = ink(frame())
        assertTrue("turning back keeps the drawn ink: $reopened -> $turned", turned <= reopened + MAX_TURN_STEP)
    }

    private var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

    private fun host(
        section: MutableState<Int>,
        content: @Composable (Int) -> Unit,
    ) {
        rule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            WhiteNoiseTheme(darkTheme = false) {
                SettingsRouteMotion(
                    target = section.value,
                    depth = section.value,
                    ownerKey = "account",
                    topLevelBubble = true,
                    cardMorph = true,
                    content = content,
                )
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.waitForIdle()
    }

    /** How dark the Settings ink block is against the page fill beside it, from 0 to 1. */
    private fun ink(frame: Bitmap): Float {
        val fill = luminance(frame.getPixel(FILL_X, INK_Y))
        val block = luminance(frame.getPixel(INK_X, INK_Y))
        return if (fill <= 0f) 0f else ((fill - block) / fill).coerceIn(0f, 1f)
    }

    private fun luminance(color: Int): Float {
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        return red * 0.299f + green * 0.587f + (color and 0xFF) * 0.114f
    }

    private fun update(block: () -> Unit) {
        rule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    private fun step() {
        rule.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        app.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private companion object {
        const val AVATAR = "underlay.avatar"
        const val FIELD = "underlay.field"
        const val SETTLE_MILLIS = 1_000L
        const val OPEN_FRAMES = 3
        const val MID_OPEN_FRAMES = 6
        const val TURN_OPEN_FRAMES = 3
        const val MAX_TURN_STEP = 0.1f
        const val REOPEN_FRAMES = 4
        const val INK_X = 76
        const val INK_Y = 100
        const val FILL_X = 150
        const val MID_OPEN_INK = 0.3f
        const val NO_INK = 0.05f
        const val MAX_INK_STEP = 0.45f
        const val SETTLED_INK = 0.9f
    }
}

/** A chat list stand-in: the avatar wired like the production top bar, plus [extra] content. */
@Composable
@Suppress("FunctionNaming")
private fun Chats(
    open: () -> Unit,
    extra: @Composable () -> Unit,
) {
    val inset = 4.dp
    val (origin, click) = rememberSettingsRouteClick(open, originInset = inset)
    Box(Modifier.fillMaxSize().background(Color(0xFFFFC107))) {
        extra()
        Box(
            Modifier
                .padding(start = 8.dp, top = 8.dp)
                .size(48.dp)
                .then(origin)
                .settingsCardMorph(SettingsHomeOriginAnchor, inset)
                .testTag("underlay.avatar")
                .clickable { click() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(Color(0xFF2E7D32), CircleShape))
        }
    }
}
