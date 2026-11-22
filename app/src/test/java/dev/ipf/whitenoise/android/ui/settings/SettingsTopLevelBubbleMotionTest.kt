package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * The production top-level host (topLevelBubble with cardMorph and the avatar's measured origin)
 * opens Settings as ONE thin outline bubble that leaves the avatar and lands on the Settings card
 * nearest it, while the avatar and the chat list fade in place under no scrim; Back runs the same
 * bubble back into the avatar, and a reversal continues from the drawn frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsTopLevelBubbleMotionTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var originalAnimatorScale = 1f
    private val section = mutableStateOf(0)

    @Before
    fun enableMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(1f)
    }

    @After
    fun restoreMotion() = setAnimatorScale(originalAnimatorScale)

    @Test fun theBubbleIsTheOriginCircleAtFirstAndTheCardsRestingOutlineAtRest() {
        val avatar = Rect(12f, 12f, 52f, 52f)
        val card = card(Rect(16f, 100f, 344f, 260f))
        val geometry = SettingsBubbleGeometry(avatar, card)
        val start = settingsBubbleShape(geometry, 0f)
        assertEquals(avatar, Rect(start.left, start.top, start.right, start.bottom))
        assertEquals(20f, start.topLeftCornerRadius.x, 0.01f)
        val end = settingsBubbleShape(geometry, 1f)
        assertEquals(card.bounds, Rect(end.left, end.top, end.right, end.bottom))
        assertEquals(16f, end.topLeftCornerRadius.x, 0.01f)
        // Every alpha is a function of presence alone, so a reversal is continuous.
        assertEquals(0f, settingsBubbleUnderlayCover(0f), 0f)
        assertEquals(1f, settingsBubbleUnderlayCover(0.5f), 0f)
        assertEquals(0f, settingsBubbleCardAlpha(0f), 0f)
        assertEquals(1f, settingsBubbleCardAlpha(1f), 0f)
        assertEquals(0f, settingsBubblePageAlpha(0.3f), 0f)
        assertEquals(1f, settingsBubblePageAlpha(1f), 0f)
    }

    @Test fun theBubbleLandsOnTheCardNearestItsOrigin() {
        val host = Rect(0f, 0f, 360f, 780f)
        val top = card(Rect(16f, 100f, 344f, 260f))
        val middle = card(Rect(16f, 300f, 344f, 500f))
        val bottom = card(Rect(16f, 700f, 344f, 900f)).copy(visibleBounds = Rect(16f, 700f, 344f, 780f))
        val offscreen = card(Rect(16f, 900f, 344f, 1000f))
        val cards = listOf(offscreen, bottom, middle, top)
        assertEquals(top, nearestSettingsBubbleCard(cards, Rect(12f, 12f, 52f, 52f), host))
        assertEquals(top, nearestSettingsBubbleCard(cards, Rect(300f, 12f, 340f, 52f), host))
        assertEquals(bottom, nearestSettingsBubbleCard(cards, Rect(12f, 730f, 52f, 770f), host))
        assertNull(nearestSettingsBubbleCard(listOf(offscreen), Rect(12f, 730f, 52f, 770f), host))
    }

    @Test fun aPartlyClippedCardKeepsItsViewportClipWhileTheBubbleIsOnIt() {
        // The viewport hides the card's top 40 px under the header; the bubble lands on its visible part only.
        val visible = Rect(16f, 100f, 344f, 260f)
        val card = card(Rect(16f, 60f, 344f, 260f)).copy(visibleBounds = visible, fill = Color.Blue)
        val geometry = SettingsBubbleGeometry(Rect(12f, 12f, 52f, 52f), card)
        listOf(1f, CLOSING_PRESENCE, 0.9f, 0.6f).forEach { presence ->
            val pixels =
                drawFrame { drawSettingsBubbleFrame(geometry, presence, Color.Red, 1f) { drawRect(Color.Green) } }
            for (y in HIDDEN_TOP until HIDDEN_BOTTOM) {
                listOf(17, 180, 343).forEach { x ->
                    val pixel = pixels[x, y]
                    val chrome = pixel.red > 0.05f || pixel.blue > 0.05f
                    assertTrue("no card chrome over the header at $x,$y ($presence): $pixel", !chrome)
                }
            }
        }
    }

    @Test fun aReversedLegKeepsTheInkItTurnedAroundWith() {
        // A reopen after a pictureless close ramps its ink from none; turning back keeps the drawn ink.
        val reopened = SettingsBubbleResume(presence = 0.4f, ink = 0f)
        val drawn = settingsBubbleInk(0.6f, opening = true, reopened)
        assertEquals(1f / 3f, drawn, 0.001f)
        assertEquals(1f, settingsBubbleInk(1f, opening = true, reopened), 0f)
        assertEquals(drawn, settingsBubbleInk(0.6f, opening = false, SettingsBubbleResume(0.6f, drawn)), 0f)
        assertEquals(1f, settingsBubbleInk(0.3f, opening = false, null), 0f)
    }

    @Test fun amoledOutlineLeavesTheTopAvatarForTheTopCardAndReturns() = assertSingleOutline(bottomOrigin = false)

    @Test fun amoledOutlineLeavesABottomControlForTheBottomCardAndReturns() = assertSingleOutline(bottomOrigin = true)

    @Test fun lightAvatarAndChatsFadeInPlaceWithoutAScrim() {
        host(amoled = false, bottomOrigin = false, rows = true)
        val chats = frame()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        val opening = List(OPEN_FRAMES) { step().let { frame() } }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        val settings = frame()
        assertTrue("the avatar stays put at first", near(opening.first().getPixel(32, 32), chats.getPixel(32, 32), 40))
        opening.forEachIndexed { index, open ->
            assertBetween("open frame $index", open, chats, settings)
            assertTrue("open frame $index carries no avatar colour away", greenOutsideAvatar(open) == 0)
        }
        assertTrue(
            "the chat list is gone well before the open settles",
            near(opening[CHATS_GONE_FRAME].getPixel(FAR_X, FAR_Y), settings.getPixel(FAR_X, FAR_Y), 3),
        )

        update { section.value = 0 }
        val closing = List(CLOSE_FRAMES) { step().let { frame() } }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        val settled = frame()
        closing.forEachIndexed { index, close ->
            assertBetween("close frame $index", close, settled, settings)
            assertTrue("close frame $index carries no avatar colour", greenOutsideAvatar(close) == 0)
        }
        assertTrue("the avatar is back in place", near(settled.getPixel(32, 32), chats.getPixel(32, 32), 3))
    }

    @Test fun reversingMidOpenContinuesFromTheDrawnBubble() {
        host(amoled = true, bottomOrigin = false, rows = false)
        val cards = measureCards()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        val opened = List(REVERSE_AFTER_FRAMES) { step().let { outlineBounds(frame(), cards) } }.filterNotNull()
        update { section.value = 0 }
        val closed = List(CLOSE_FRAMES) { step().let { outlineBounds(frame(), cards) } }.filterNotNull()
        val tops = opened.map { it.top.toInt() } to closed.map { it.top.toInt() }
        assertTrue("the bubble was moving when reversed: $tops", opened.last().top > AVATAR_TOP + MAX_STEP)
        // The reversal may follow the open's clock by the one frame it already ticked, never further.
        val openStep = opened.zipWithNext().maxOf { (a, b) -> b.top - a.top }
        assertTrue(
            "the reversal starts from the drawn bubble: $tops",
            abs(closed.first().top - opened.last().top) <= openStep + MAX_STEP / 4,
        )
        closed.zipWithNext().forEach { (a, b) -> assertTrue("then heads straight back: $tops", b.top <= a.top + 1) }
        assertTrue("and lands in the avatar: $tops", closed.last().top <= AVATAR_TOP + LANDING_SLACK)
    }

    private fun assertSingleOutline(bottomOrigin: Boolean) {
        host(amoled = true, bottomOrigin = bottomOrigin, rows = false)
        val cards = measureCards()
        val near = if (bottomOrigin) cards.last() else cards.first()
        val far = if (bottomOrigin) cards.first() else cards.last()
        val avatar = avatarBounds(bottomOrigin)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        val opening = List(OPEN_FRAMES) { step().let { outlineBounds(frame(), cards) } }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        assertNull("settled Settings shows no bubble", outlineBounds(frame(), cards))
        update { section.value = 0 }
        val closing = List(CLOSE_FRAMES) { step().let { outlineBounds(frame(), cards) } }
        (opening + closing).filterNotNull().forEach { bubble ->
            assertTrue("the outline stays between the origin and its card: $bubble", bubble.overlapsSpan(avatar, near))
            assertTrue("the outline never heads for another card: $bubble", !bubble.reachesInto(far))
        }
        val first = checkNotNull(opening.firstOrNull { it != null }) { "the open draws an outline" }
        assertTrue("the outline leaves from the avatar: $first", first.isAround(avatar))
        val last = checkNotNull(closing.lastOrNull { it != null }) { "the close draws an outline" }
        assertTrue("the outline lands in the avatar: $last", last.isAround(avatar))
    }

    private fun host(
        amoled: Boolean,
        bottomOrigin: Boolean,
        rows: Boolean,
    ) {
        rule.setContent {
            WhiteNoiseTheme(darkTheme = amoled, amoled = amoled) {
                SettingsRouteMotion(
                    target = section.value,
                    depth = section.value,
                    ownerKey = "account",
                    topLevelBubble = true,
                    cardMorph = true,
                ) { route ->
                    if (route == 0) ChatsStandIn(bottomOrigin, rows) { section.value = 1 } else SettingsStandIn()
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.waitForIdle()
    }

    /** Opens and closes once, settled, to measure the two Settings cards in root pixels. */
    private fun measureCards(): List<Rect> {
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.waitForIdle()
        val cards =
            rule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SettingsGroupKey))
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
                .sortedBy { it.top }
        update { section.value = 0 }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.waitForIdle()
        assertEquals(2, cards.size)
        return cards
    }

    /** Bounds of the light outline pixels outside both cards (the Chats and Settings stand-ins are otherwise dark). */
    private fun outlineBounds(
        frame: Bitmap,
        cards: List<Rect>,
    ): Rect? {
        val excluded = cards.map { it.inflate(CARD_MARGIN) }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val center = Offset(x + 0.5f, y + 0.5f)
                if (lightGrey(frame.getPixel(x, y)) && excluded.none { it.contains(center) }) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    /** Within the span from [origin] to [card], allowing antialiasing. */
    private fun Rect.overlapsSpan(
        origin: Rect,
        card: Rect,
    ): Boolean {
        val span =
            Rect(
                minOf(origin.left, card.left),
                minOf(origin.top, card.top),
                maxOf(origin.right, card.right),
                maxOf(origin.bottom, card.bottom),
            ).inflate(2f)
        return span.left <= left && span.top <= top && span.right >= right && span.bottom >= bottom
    }

    private fun Rect.reachesInto(card: Rect) = bottom > card.top - CARD_MARGIN && top < card.bottom + CARD_MARGIN

    /** A bubble the size of the origin control, around it. */
    private fun Rect.isAround(origin: Rect): Boolean = inflate(1f).contains(origin.center) && width < origin.width * 2

    private fun card(bounds: Rect) = SettingsBubbleCard(bounds, bounds, 16f, Color.Black, Color.White, 1f)

    private fun avatarBounds(bottom: Boolean) = if (bottom) Rect(12f, 728f, 52f, 768f) else Rect(12f, 12f, 52f, 52f)

    private fun assertBetween(
        label: String,
        frame: Bitmap,
        from: Bitmap,
        to: Bitmap,
    ) {
        val at = frame.getPixel(FAR_X, FAR_Y)
        val ok =
            channels(at).indices.all { channel ->
                val value = channels(at)[channel]
                val a = channels(from.getPixel(FAR_X, FAR_Y))[channel]
                val b = channels(to.getPixel(FAR_X, FAR_Y))[channel]
                value >= minOf(a, b) - 3 && value <= maxOf(a, b) + 3
            }
        assertTrue("$label only fades between the chat list and Settings, no scrim", ok)
    }

    private fun greenOutsideAvatar(frame: Bitmap): Int {
        val avatar = avatarBounds(false).inflate(2f)
        var count = 0
        for (y in 0 until frame.height step 2) {
            for (x in 0 until frame.width step 2) {
                val (r, g, b) = channels(frame.getPixel(x, y))
                val green = g > r + GREEN_MARGIN && g > b + GREEN_MARGIN
                if (green && !avatar.contains(Offset(x + 0.5f, y + 0.5f))) count++
            }
        }
        return count
    }

    private fun lightGrey(color: Int): Boolean {
        val values = channels(color)
        return values.min() > LIGHT && values.max() - values.min() < GREY_SPREAD
    }

    private fun near(
        a: Int,
        b: Int,
        tolerance: Int,
    ) = channels(a).zip(channels(b)).all { (x, y) -> abs(x - y) <= tolerance }

    private fun channels(color: Int) = listOf(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)

    private fun drawFrame(block: DrawScope.() -> Unit): PixelMap {
        val image = ImageBitmap(360, 780)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(360f, 780f), block)
        return image.toPixelMap()
    }

    private fun step() {
        rule.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    private fun update(block: () -> Unit) {
        rule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        app.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private companion object {
        const val AVATAR_TAG = "fixture.avatar"
        const val CLOSING_PRESENCE = 0.98f
        const val HIDDEN_TOP = 60
        const val HIDDEN_BOTTOM = 100
        const val AVATAR_TOP = 12
        const val SETTLE_MILLIS = 1_000L
        const val OPEN_FRAMES = 20
        const val CLOSE_FRAMES = 18
        const val REVERSE_AFTER_FRAMES = 5
        const val CHATS_GONE_FRAME = 9
        const val MAX_STEP = 16
        const val LANDING_SLACK = 12
        const val CARD_MARGIN = 3f
        const val LIGHT = 60
        const val GREY_SPREAD = 40
        const val GREEN_MARGIN = 40
        const val FAR_X = 300
        const val FAR_Y = 400
    }
}

/** A chat list stand-in: the avatar wired exactly like the production top bar, at the top or the bottom. */
@Composable
@Suppress("FunctionNaming")
private fun ChatsStandIn(
    bottomOrigin: Boolean,
    rows: Boolean,
    open: () -> Unit,
) {
    val inset = 4.dp
    val (origin, click) = rememberSettingsRouteClick(open, originInset = inset)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (rows) {
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
        }
        Box(
            Modifier
                .align(if (bottomOrigin) Alignment.BottomStart else Alignment.TopStart)
                .padding(8.dp)
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

/** A Settings stand-in with two production groups far apart: one near the top, one near the bottom. */
@Composable
@Suppress("FunctionNaming")
private fun SettingsStandIn() {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Spacer(Modifier.height(180.dp))
        StandInGroup()
        Spacer(Modifier.height(300.dp))
        StandInGroup()
    }
}

@Composable
@Suppress("FunctionNaming")
private fun StandInGroup() {
    SettingsGroup {
        listOf("first", "second").forEach { key ->
            row(key) { context -> SettingsGroupPanel(context) { Box(Modifier.height(56.dp)) } }
        }
    }
}
