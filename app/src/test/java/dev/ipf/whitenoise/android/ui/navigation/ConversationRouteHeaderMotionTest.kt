package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The route header fades through with its body on the shared clock instead of cutting on frame one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationRouteHeaderMotionTest {
    @get:Rule val rule = createComposeRule()
    private val origin = Rect(210f, 340f, 330f, 392f)

    @Test
    fun headersNeverOverlapAndReachRestWithTheClock() {
        val steps = (0..100).map { it / 100f }
        steps.forEach { v ->
            val list = presence(v, conversationRoute = false, current = false)
            val chat = presence(v, conversationRoute = true, current = true)
            assertTrue("Only one header is visible at v=$v", list == 0f || chat == 0f)
            val travel = conversationRouteHeaderTravel(chat, conversationRoute = true)
            assertTrue("Travel stays bounded at v=$v", travel in 0f..1f)
            assertEquals(0f, conversationRouteHeaderTravel(list, conversationRoute = false))
        }
        assertEquals(1f, presence(0f, conversationRoute = false, current = true))
        assertEquals(0f, presence(0f, conversationRoute = true, current = true))
        assertEquals(1f, presence(1f, conversationRoute = true, current = true))
        assertEquals(0f, conversationRouteHeaderTravel(1f, conversationRoute = true))
        // Reversal continues from the rendered value: one frame of clock moves presence by a bounded step.
        steps.zipWithNext().forEach { (a, b) ->
            val step = presence(b, conversationRoute = true, current = true) - presence(a, true, current = true)
            assertTrue("Presence step stays continuous", kotlin.math.abs(step) <= 0.021f)
        }
    }

    @Test
    fun directSwitchShowsOnlyTheCurrentHeaderAndOnlyTheOwnerChatDrawsOne() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { v ->
            assertEquals(1f, presence(v, conversationRoute = true, current = true, direct = true))
            assertEquals(0f, presence(v, conversationRoute = true, current = false, direct = true))
            assertEquals(0f, presence(v, conversationRoute = false, current = false, direct = true))
            assertEquals(0f, presence(v, conversationRoute = true, current = false, owner = false))
        }
    }

    @Test
    fun headerBandReachesTheBodyAndSettledFramesStayFixed() {
        val header = Rect(0f, 0f, 360f, 64f)
        val body = Rect(0f, 112f, 360f, 700f)
        val band = motion(header, body, visibility = 1f, conversationRoute = true)
        assertEquals(Rect(0f, 0f, 360f, 112f), band?.bounds)
        assertEquals(1f, band?.presence)
        assertEquals(0f, band?.offsetY)
        assertEquals(header, motion(header, null, 1f, conversationRoute = true)?.bounds)
        assertEquals(null, motion(null, body, 1f, conversationRoute = true))
        val rising = motion(header, body, visibility = 0.75f, conversationRoute = true)
        assertEquals(0.5f, rising?.presence)
        assertEquals(6f, rising?.offsetY)
    }

    @Test
    fun replacingAnOpeningChatAndGoingBackNeverDrawsTwoChatHeaders() {
        val route = mutableStateOf<String?>(null)
        rule.mainClock.autoAdvance = false
        setRouteContent(route) {
            HeaderPage(
                conversation = it != null,
                color = if (it == "Bravo") Color.Green else if (it == null) Color.Blue else Color.Red,
            )
        }
        rule.mainClock.advanceTimeBy(400)
        commit(route, "Alpha")
        rule.mainClock.advanceTimeBy(48)
        commit(route, "Bravo")
        val frames = mutableListOf(pixels())
        repeat(6) {
            rule.mainClock.advanceTimeBy(16)
            frames += pixels()
        }
        commit(route, null)
        repeat(18) {
            rule.mainClock.advanceTimeBy(16)
            frames += pixels()
        }
        assertTrue("Fixture draws the latest chat header", frames.any { ink(it, ::anyBravoHeader).size > 50 })
        frames.forEach { frame ->
            assertTrue("The replaced chat's header never draws again", ink(frame, ::anyHeader).isEmpty())
        }
    }

    @Test
    fun conversationHeaderFadesAndRisesInsteadOfCuttingOnOpenAndBack() {
        val route = mutableStateOf<String?>(null)
        rule.mainClock.autoAdvance = false
        setRouteContent(route) { HeaderPage(conversation = it != null) }
        rule.mainClock.advanceTimeBy(400)
        val listRest = pixels()
        assertTrue("Fixture needs list header glyphs", ink(listRest, ::opaqueListHeader).size > 100)

        commit(route, "chat")
        val first = pixels()
        assertTrue("Conversation header is not drawn opaque on the first frame", ink(first, ::opaqueHeader).isEmpty())

        val frames = mutableListOf(first)
        repeat(24) {
            rule.mainClock.advanceTimeBy(16)
            frames += pixels()
        }
        frames.forEach { frame ->
            assertTrue(
                "List and conversation headers never show together",
                ink(frame, ::listHeader).isEmpty() || ink(frame, ::anyHeader).isEmpty(),
            )
        }
        rule.mainClock.advanceTimeBy(400)
        val chatRest = pixels()
        val rest = ink(chatRest, ::opaqueHeader)
        assertTrue("Fixture needs conversation header glyphs", rest.size > 100)
        val fading = frames.first { f -> ink(f, ::anyHeader).size > 50 && ink(f, ::opaqueHeader).isEmpty() }
        val restTop = rest.minOf { it.second }
        val fadingTop = ink(fading, ::anyHeader).minOf { it.second }
        assertTrue(
            "A fading header sits below rest, within its travel ($fadingTop vs $restTop)",
            fadingTop - restTop in 1..13,
        )
        val fadingLeft = ink(fading, ::anyHeader).minOf { it.first }
        val restLeft = rest.minOf { it.first }
        assertTrue("Horizontal header geometry stays native", kotlin.math.abs(restLeft - fadingLeft) <= 1)

        commit(route, null)
        // The close curve starts slowly; by 96 ms the header is clearly below full opacity.
        rule.mainClock.advanceTimeBy(96)
        assertTrue("Conversation header fades on Back", ink(pixels(), ::opaqueHeader).isEmpty())
        rule.mainClock.advanceTimeBy(400)
        assertEquals(
            "The list settles exactly as it started",
            ink(listRest, ::opaqueListHeader),
            ink(pixels(), ::opaqueListHeader),
        )
    }

    private fun setRouteContent(
        route: androidx.compose.runtime.State<String?>,
        page: @Composable (String?) -> Unit,
    ) {
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ConversationRouteAnimatedContent(
                    rememberConversationRouteTransition(route.value, false),
                    routeForwardDirection = 1,
                    suppressMotion = false,
                    contentKey = { it ?: "list" },
                    activatingBounds = { if (it == null) null else origin },
                ) { page(it) }
            }
        }
    }

    private fun commit(
        route: androidx.compose.runtime.MutableState<String?>,
        value: String?,
    ) {
        rule.runOnIdle {
            route.value = value
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun presence(
        v: Float,
        conversationRoute: Boolean,
        current: Boolean,
        owner: Boolean = true,
        direct: Boolean = false,
    ) = conversationRouteHeaderPresence(v, conversationRoute, current, owner, direct)

    private fun motion(
        header: Rect?,
        body: Rect?,
        visibility: Float,
        conversationRoute: Boolean,
    ) = conversationRouteHeaderMotion(header, body, visibility, conversationRoute, true, true, false, travelPx = 12f)

    @Composable
    private fun HeaderPage(
        conversation: Boolean,
        color: Color = if (conversation) Color.Red else Color.Blue,
    ) {
        WhiteNoiseScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Black,
            topNoticeSlot = false,
            topBar = {
                Box(Modifier.height(80.dp).conversationRouteHeaderMotion()) {
                    Text(
                        if (conversation) "Chat header" else "List header",
                        color = color,
                        fontSize = 30.sp,
                        modifier = Modifier.padding(start = 18.dp, top = 16.dp),
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).conversationRouteBodyMotion()) {
                Text("Body", color = Color.White, fontSize = 28.sp, modifier = Modifier.padding(24.dp))
            }
        }
    }

    private fun pixels(): PixelMap = rule.onRoot().captureToImage().toPixelMap()

    private fun opaqueHeader(c: Color) = c.red > 0.95f && c.green < 0.05f && c.blue < 0.05f

    private fun anyHeader(c: Color) = c.red > 0.15f && c.green < 0.05f && c.blue < 0.05f

    private fun anyBravoHeader(c: Color) = c.green > 0.15f && c.red < 0.05f && c.blue < 0.05f

    private fun opaqueListHeader(c: Color) = c.blue > 0.95f && c.red < 0.05f && c.green < 0.05f

    private fun listHeader(c: Color) = c.blue > 0.15f && c.red < 0.05f && c.green < 0.05f

    private fun ink(
        pixels: PixelMap,
        matches: (Color) -> Boolean,
    ): Set<Pair<Int, Int>> =
        buildSet {
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    if (matches(pixels[x, y])) add(x to y)
                }
            }
        }
}
