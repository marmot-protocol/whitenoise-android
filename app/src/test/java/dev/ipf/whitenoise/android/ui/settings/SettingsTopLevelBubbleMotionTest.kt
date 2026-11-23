package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphCard
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphColors
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphGeometry
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphResume
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphSource
import dev.ipf.whitenoise.android.ui.common.drawClippedSurfaceMorph
import dev.ipf.whitenoise.android.ui.common.drawSurfaceArrival
import dev.ipf.whitenoise.android.ui.common.surfaceMorphCardAlpha
import dev.ipf.whitenoise.android.ui.common.surfaceMorphInk
import dev.ipf.whitenoise.android.ui.common.surfaceMorphShape
import dev.ipf.whitenoise.android.ui.common.surfaceMorphUnderlayCover
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

/** The production top-level Settings host gives the Home pen one landing and keeps native ink in place. */
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

    @Test fun thePenStartsAndEndsAtItsMeasuredNativeOutline() {
        val card = fixedCard(Rect(16f, 100f, 344f, 260f))
        val bubble = SurfaceMorphGeometry(PEN, card, PEN_SURFACE)
        val start = surfaceMorphShape(bubble, 0f)
        assertEquals(PEN, Rect(start.left, start.top, start.right, start.bottom))
        assertEquals(PEN_SURFACE.radius, start.topLeftCornerRadius.x, 0f)
        val end = surfaceMorphShape(bubble, 1f)
        assertEquals(card.bounds, Rect(end.left, end.top, end.right, end.bottom))
        assertEquals(card.radius, end.topLeftCornerRadius.x, 0f)
        assertEquals(0f, surfaceMorphUnderlayCover(0f), 0f)
        assertEquals(1f, surfaceMorphUnderlayCover(0.5f), 0f)
        assertEquals(0f, surfaceMorphCardAlpha(0f), 0f)
        assertEquals(1f, surfaceMorphCardAlpha(1f), 0f)
    }

    @Test fun thePenLandsOnTheBottomMostVisibleCard() {
        val handoff = SettingsTopLevelHandoff().apply { pen = SurfaceMorphSource(PEN, PEN_SURFACE) }
        val bubble = checkNotNull(handoff.bubble(HOST, CARDS))
        assertEquals(BOTTOM, bubble.card)
        assertEquals(PEN, bubble.origin)
        assertEquals(PEN_SURFACE, bubble.control)
    }

    @Test fun withoutAPenTheBottomCardFadesInPlace() {
        val handoff = SettingsTopLevelHandoff()
        assertNull(handoff.bubble(HOST, CARDS))
        val pixels =
            drawFrame {
                drawSurfaceArrival(null, 0.5f, BUBBLE_COLORS, 1f) {
                    drawRect(Color.Red, Offset(165f, 635f), Size(30f, 30f))
                }
            }
        assertTrue("the bottom card draws at its resting position", pixels[180, 650].red > 0.2f)
    }

    @Test fun aMissingOrOffscreenEndpointUsesOnlyTheFade() {
        val handoff = SettingsTopLevelHandoff()
        val offscreen = Rect(16f, 900f, 56f, 940f)
        handoff.pen = SurfaceMorphSource(offscreen, PEN_SURFACE)
        assertNull(handoff.bubble(HOST, CARDS))
        handoff.pen = SurfaceMorphSource(PEN, PEN_SURFACE)
        assertNull(handoff.bubble(HOST, listOf(fixedCard(offscreen))))
    }

    @Test fun theOutlineMovesWhileSiblingInkStaysInPlace() {
        val bubble = SurfaceMorphGeometry(PEN, BOTTOM, PEN_SURFACE)
        val pixels =
            drawFrame {
                drawSurfaceArrival(bubble, 0.5f, BUBBLE_COLORS, 1f) {
                    drawRect(Color.Red, Offset(165f, 590f), Size(30f, 20f))
                }
            }
        assertTrue("the outline reaches its interpolated top edge", pixels[252, 673].alpha > 0.4f)
        assertTrue("the sibling ink stays in place", pixels[180, 600].alpha > 0.4f)
        assertEquals("there is no translated sibling ink", 0f, pixels[252, 653].alpha, 0f)
    }

    @Test fun aSoleProfileCardKeepsItsPortraitAtItsNativePosition() {
        val profile = BOTTOM.copy(fill = Color.Yellow)
        val target = Rect(84f, 634f, 116f, 666f)
        val handoff = SettingsTopLevelHandoff().apply { pen = SurfaceMorphSource(PEN, PEN_SURFACE) }
        val bubble = checkNotNull(handoff.bubble(HOST, listOf(profile)))
        assertEquals(profile, bubble.card)
        val pixels =
            drawFrame {
                drawSurfaceArrival(bubble, 0.95f, BUBBLE_COLORS, 1f) {
                    drawRect(Color.Yellow, profile.bounds.topLeft, profile.bounds.size)
                    drawCircle(Color.Green, target.width / 2, target.center)
                }
            }
        assertEquals(Color.Green, pixels[100, 650])
    }

    @Test fun partlyClippedCardsKeepTheirViewportClipWhileTheBubblesAreOnThem() {
        // The viewport hides the profile card's top under the header and the pen card's bottom below the fold.
        val top =
            fixedCard(Rect(16f, 60f, 344f, 260f))
                .copy(visibleBounds = Rect(16f, 100f, 344f, 260f), fill = Color.Blue)
        val low =
            fixedCard(Rect(16f, 600f, 344f, 800f))
                .copy(visibleBounds = Rect(16f, 600f, 344f, 740f), fill = Color.Blue)
        val pen = PEN_SURFACE.copy(fill = Color.Blue)
        val bubbles = listOf(SurfaceMorphGeometry(AVATAR, top, pen), SurfaceMorphGeometry(PEN, low, pen))
        listOf(1f, CLOSING_PRESENCE, 0.9f, 0.6f).forEach { presence ->
            val pixels =
                drawFrame {
                    bubbles.forEach { bubble ->
                        drawClippedSurfaceMorph(bubble, presence, SurfaceMorphColors(Color.Black, Color.Red), 1f) {
                            drawRect(Color.Green)
                        }
                    }
                }
            val hidden = (HIDDEN_TOP until HIDDEN_BOTTOM) + (FOLD_TOP until FOLD_BOTTOM)
            for (y in hidden) {
                listOf(17, 180, 343).forEach { x ->
                    val pixel = pixels[x, y]
                    val chrome = pixel.red > 0.05f || pixel.blue > 0.05f
                    assertTrue("no card chrome where the viewport hides it, $x,$y ($presence): $pixel", !chrome)
                }
            }
        }
    }

    @Test fun aReversedLegKeepsTheInkItTurnedAroundWith() {
        // A reopen after a pictureless close ramps its ink from none; turning back keeps the drawn ink.
        val reopened = SurfaceMorphResume(presence = 0.4f, ink = 0f)
        val drawn = surfaceMorphInk(0.6f, opening = true, reopened)
        assertEquals(1f / 3f, drawn, 0.001f)
        assertEquals(1f, surfaceMorphInk(1f, opening = true, reopened), 0f)
        assertEquals(drawn, surfaceMorphInk(0.6f, opening = false, SurfaceMorphResume(0.6f, drawn)), 0f)
        assertEquals(1f, surfaceMorphInk(0.3f, opening = false, null), 0f)
    }

    @Test fun amoledPenMorphsIntoTheBottomCardAndBack() {
        host(amoled = true, pen = true, rows = false)
        val cards = measureCards()
        val middle = (cards.first().bottom + cards.last().top) / 2f
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        val opening = List(OPEN_FRAMES) { step().let { outlineBounds(frame(), cards) { y -> y > middle } } }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        assertNull("settled Settings shows no bubble", outlineBounds(frame(), cards) { it > middle })
        update { section.value = 0 }
        val closing = List(CLOSE_FRAMES) { step().let { outlineBounds(frame(), cards) { y -> y > middle } } }
        val span = PEN_STAND_IN.span(cards.last())
        (opening + closing).filterNotNull().forEach { bubble ->
            assertTrue(
                "the pen outline stays between the pen and the bottom card: $bubble",
                span.inflate(2f).containsRect(bubble),
            )
        }
        val first = checkNotNull(opening.firstOrNull { it != null }) { "the open morphs the pen" }
        // The pen shares rows with the bottom card, whose band is masked; its visible edges must be the pen's.
        assertTrue("the morph leaves the pen as the pen: $first", PEN_STAND_IN.inflate(2f).containsRect(first))
        val last = checkNotNull(closing.lastOrNull { it != null }) { "the close morphs back into the pen" }
        assertTrue(
            "the morph lands in the pen: $last",
            last.inflate(PEN_SLACK).containsRect(PEN_STAND_IN) && last.width < 2 * PEN_STAND_IN.width,
        )
    }

    @Test fun amoledWithoutThePenTheBottomCardJustFades() {
        host(amoled = true, pen = false, rows = false)
        val cards = measureCards()
        val middle = (cards.first().bottom + cards.last().top) / 2f
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        repeat(OPEN_FRAMES) { index ->
            step()
            val outline = outlineBounds(frame(), cards) { it > middle }
            assertNull("open frame $index morphs nothing into the bottom card", outline)
        }
    }

    @Test fun lightChatsFadeInPlaceAndBothPortraitsStayAtTheirNativePositions() {
        host(amoled = false, pen = true, rows = true)
        val chats = frame()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        val opening = List(OPEN_FRAMES) { step().let { frame() } }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        val settings = frame()
        opening.forEachIndexed { index, open ->
            assertBetween("open frame $index", open, chats, settings)
            assertStationaryPortraits(open, "open frame $index")
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
            assertStationaryPortraits(close, "close frame $index")
        }
        assertTrue("the avatar is back in place", near(settled.getPixel(32, 32), chats.getPixel(32, 32), 3))
    }

    @Test fun reversingMidOpenReturnsThePenToHome() {
        host(amoled = true, pen = true, rows = false)
        val cards = measureCards()
        val middle = (cards.first().bottom + cards.last().top) / 2f
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(AVATAR_TAG).performClick()
        repeat(REVERSE_AFTER_FRAMES) { step() }
        val departing = checkNotNull(outlineBounds(frame(), cards) { it > middle })
        update { section.value = 0 }
        val returning = List(CLOSE_FRAMES) { step().let { outlineBounds(frame(), cards) { it > middle } } }
        assertTrue("the pen moved before the interruption", departing.width > PEN_STAND_IN.width)
        assertTrue(
            "the pen returns to its measured Home position",
            returning.filterNotNull().any { bounds ->
                PEN_STAND_IN.inflate(PEN_SLACK).containsRect(bounds)
            },
        )
    }

    private fun host(
        amoled: Boolean,
        pen: Boolean,
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
                    if (route == 0) ChatsStandIn(pen, rows) { section.value = 1 } else SettingsStandIn()
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

    /** Bounds of the light outline pixels outside both cards in [rows] (the stand-ins are otherwise dark). */
    private fun outlineBounds(
        frame: Bitmap,
        cards: List<Rect>,
        rows: (Float) -> Boolean = { true },
    ): Rect? {
        val excluded = cards.map { it.inflate(CARD_MARGIN) }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until frame.height) {
            if (!rows(y + 0.5f)) continue
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

    /** Green pixels may belong to either native portrait, with no travelling portrait between them. */
    private fun assertStationaryPortraits(
        frame: Bitmap,
        label: String,
    ) {
        val home = AVATAR.inflate(2f)
        val settings = TARGET.inflate(2f)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val (r, g, b) = channels(frame.getPixel(x, y))
                if (g > r + GREEN_MARGIN && g > b + GREEN_MARGIN) {
                    val at = Offset(x + 0.5f, y + 0.5f)
                    assertTrue(
                        "$label has travelling portrait pixels at $at",
                        home.contains(at) || settings.contains(at),
                    )
                }
            }
        }
    }

    private fun Rect.span(other: Rect) =
        Rect(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom),
        )

    private fun Rect.containsRect(other: Rect): Boolean =
        left <= other.left &&
            top <= other.top &&
            right >= other.right &&
            bottom >= other.bottom

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
        const val HIDDEN_TOP = 62
        const val HIDDEN_BOTTOM = 96
        const val FOLD_TOP = 744
        const val FOLD_BOTTOM = 778
        const val SETTLE_MILLIS = 1_000L
        const val OPEN_FRAMES = 20
        const val CLOSE_FRAMES = 18
        const val REVERSE_AFTER_FRAMES = 5
        const val CHATS_GONE_FRAME = 9
        const val CARD_MARGIN = 3f
        const val LIGHT = 60
        const val GREY_SPREAD = 40
        const val GREEN_MARGIN = 40
        const val PEN_SLACK = 12f
        const val FAR_X = 18
        const val FAR_Y = 394
        val BUBBLE_COLORS = SurfaceMorphColors(Color.Black, Color.White)
        val HOST = Rect(0f, 0f, 360f, 780f)
        val AVATAR = Rect(12f, 12f, 52f, 52f)
        val TARGET = Rect(32f, 196f, 88f, 252f)
        val PEN = Rect(300f, 720f, 348f, 768f)
        val PEN_SURFACE = ControlMorphSurface(Color.Gray, Color.White, 16f, 1f, 1f)
        val PEN_STAND_IN = Rect(304f, 724f, 352f, 772f)
        val PROFILE = fixedCard(Rect(16f, 180f, 344f, 326f))
        val BOTTOM = fixedCard(Rect(16f, 626f, 344f, 740f))
        val CARDS = listOf(PROFILE, BOTTOM, fixedCard(Rect(16f, 900f, 344f, 990f)))
    }
}

/** A chat list stand-in: the avatar and the pen wired exactly like the production top bar and FAB. */
@Composable
@Suppress("FunctionNaming")
private fun ChatsStandIn(
    pen: Boolean,
    rows: Boolean,
    open: () -> Unit,
) {
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
                .padding(8.dp)
                .size(48.dp)
                .testTag("fixture.avatar")
                .clickable(onClick = open),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(Color(0xFF2E7D32), CircleShape))
        }
        if (pen) {
            val fill = MaterialTheme.colorScheme.surfaceContainerHigh
            val border = MaterialTheme.colorScheme.outline
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(48.dp)
                    .settingsPenSource(true, 16.dp, fill, border)
                    .background(fill, RoundedCornerShape(16.dp))
                    .border(1.dp, border, RoundedCornerShape(16.dp)),
            )
        }
    }
}

/** A Settings stand-in: a profile card with its avatar near the top and a production group near the bottom. */
@Composable
@Suppress("FunctionNaming")
private fun SettingsStandIn() {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Spacer(Modifier.height(180.dp))
        SettingsGroup {
            row("profile") { context ->
                SettingsGroupPanel(context) {
                    Box(
                        Modifier
                            .padding(16.dp)
                            .size(56.dp)
                            .background(Color(0xFF2E7D32), CircleShape),
                    )
                }
            }
            row("account") { context -> SettingsGroupPanel(context) { Box(Modifier.height(56.dp)) } }
        }
        Spacer(Modifier.height(300.dp))
        SettingsGroup {
            listOf("first", "second").forEach { key ->
                row(key) { context -> SettingsGroupPanel(context) { Box(Modifier.height(56.dp)) } }
            }
        }
    }
}

private fun fixedCard(bounds: Rect) = SurfaceMorphCard(bounds, bounds, 16f, Color.Black, Color.White, 1f)
