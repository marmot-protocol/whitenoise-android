@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.StickerAssetFfi
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.marmotkit.StickerRefFfi
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.StickerKey
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.TimeZone

/**
 * Pins the real [MessageBubble] sticker rows: a bare sticker replaces the bubble and draws no text, and a captioned
 * one sits above its caption bubble. Stickers come from an installed pack served by a fake engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1100dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBubbleStickerScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appState = fileFooterAppState(context, marmot = stickerEngine())
    private val controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )
    private val originalTimeZone = TimeZone.getDefault()
    private var originalTimeFormat: String? = null

    /** Uses the same UTC and 12-hour inputs as the shared native-message fixtures. */
    @Before
    fun pinClock() {
        originalTimeFormat = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Releases the controller and restores all process-level clock inputs. */
    @After
    fun releaseFixture() {
        try {
            controller.onCleared()
        } finally {
            try {
                Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, originalTimeFormat)
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }
    }

    /** Light rows: bare stickers sit on the background, captioned ones above a normal bubble. */
    @Test fun stickerRowsLight() = capture("light", AppThemeMode.Light)

    /** AMOLED keeps the bare stickers on pure black and the caption bubbles outlined. */
    @Test fun stickerRowsAmoled() = capture("amoled", AppThemeMode.Amoled)

    /** Mirrored rows at 200% text keep the sticker size and wrap only the caption and footer. */
    @Test fun stickerRowsRtlLarge() = capture("rtl_large", AppThemeMode.Light, rtlLarge = true)

    /** The long-press overlay keeps a bare sticker's artwork instead of an empty bubble. */
    @Test
    fun focusedBareStickerKeepsItsArtwork() {
        appState.updateThemeMode(AppThemeMode.Amoled)
        val item = fileTimelineMessage(230, "", mine = true, attachments = emptyList(), sticker = STICKERS[1])
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                    StickerMessage(item, isActionMenuOpen = true)
                }
            }
        }
        val sent = StickerKey.from(STICKERS[1])
        awaitLoaded(sent)
        composeRule.onNodeWithTag("message-actions-preview", useUnmergedTree = true).assertExists()
        // One sticker in the timeline row, one in the focused preview.
        composeRule.onAllNodesWithTag("message.sticker", useUnmergedTree = true).assertCountEquals(2)
        captureScreenRoboImage("src/test/snapshots/message_bubble_sticker_focused_amoled.png")
    }

    /** A bare sticker whose message was invalidated shows the warning below the artwork. */
    @Test
    fun invalidatedBareStickerShowsItsWarning() {
        val item =
            fileTimelineMessage(
                220,
                "",
                attachments = emptyList(),
                invalidationStatus = "BeyondAppRetention",
                sticker = STICKERS[0],
            )
        composeRule.setContent { WhiteNoiseTheme { StickerMessage(item) } }
        composeRule.onNodeWithTag("sticker-message-warning", useUnmergedTree = true).assertExists()
    }

    private fun capture(
        name: String,
        mode: AppThemeMode,
        rtlLarge: Boolean = false,
    ) {
        appState.updateThemeMode(mode)
        val rows = stickerRows()
        composeRule.setContent {
            val direction = if (rtlLarge) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(
                    darkTheme = mode != AppThemeMode.Light,
                    amoled = mode == AppThemeMode.Amoled,
                    fontScale = if (rtlLarge) 2f else 1f,
                ) {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rows.forEach { StickerMessage(it) }
                    }
                }
            }
        }
        // Robolectric reports no network, so auto-download is off: sent stickers load, received ones wait for a tap.
        val sent = StickerKey.from(STICKERS[1])
        awaitLoaded(sent)
        assertFalse("The sent sticker failed to load", controller.stickerAssets.hasFailed(sent))
        composeRule.onAllNodesWithTag("message.sticker", useUnmergedTree = true).assertCountEquals(rows.size)
        // The bare rows' "Sticker" preview label must never reach the timeline.
        composeRule.onAllNodesWithTag("sticker-message-footer", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNodeWithText("Sent caption", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Received caption", useUnmergedTree = true).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/message_bubble_sticker_rows_$name.png")
    }

    /**
     * Waits for [key]'s load to settle. The decode finishes on an IO thread and resumes on the main looper, so each
     * check drains the looper: once Compose is idle nothing else would, and a slow decode would never be delivered.
     */
    private fun awaitLoaded(key: StickerKey) {
        composeRule.waitUntil(LOAD_TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            controller.stickerAssets.cached(key) != null || controller.stickerAssets.hasFailed(key)
        }
    }

    /** Received then sent, each bare and then captioned. */
    private fun stickerRows(): List<TimelineMessage> =
        listOf(false, true).flatMap { mine ->
            val (index, caption) = if (mine) 230 to "Sent caption" else 220 to "Received caption"
            val ref = if (mine) STICKERS[1] else STICKERS[0]
            listOf(
                fileTimelineMessage(index, "", mine = mine, attachments = emptyList(), sticker = ref),
                fileTimelineMessage(index + 1, "", mine, caption, attachments = emptyList(), sticker = ref),
            )
        }

    @androidx.compose.runtime.Composable
    private fun StickerMessage(
        item: TimelineMessage,
        isActionMenuOpen: Boolean = false,
    ) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = true,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = isActionMenuOpen,
            onActionMenuOpenChange = {},
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            showSenderAvatar = false,
            parseMarkdown = ::markdown,
        )
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 15_000L
        val PACK = "30031:${"cd".repeat(32)}:cats"
        val STICKERS = listOf(StickerRefFfi(PACK, "wave", "aa".repeat(32)), StickerRefFfi(PACK, "nod", "bb".repeat(32)))
        val COLORS = mapOf("wave" to 0xFFF2B33D.toInt(), "nod" to 0xFF5FB3A1.toInt())

        fun sticker(ref: StickerRefFfi) =
            StickerFfi(
                ref.packCoordinate,
                ref.shortcode,
                "https://example.invalid/${ref.shortcode}.png",
                ref.plaintextSha256,
                "image/png",
                null,
                null,
                null,
                null,
            )

        /** A filled disc in the sticker's color, encoded the way a pack serves it. */
        fun discPng(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawCircle(48f, 48f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }

        /** An engine that only lists the one installed pack and serves its sticker images. */
        fun stickerEngine(): MarmotInterface =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                // Kotlin mangles names of methods taking inline-class (UInt) parameters.
                when (method.name.substringBefore('-')) {
                    "stickerPacks" ->
                        listOf(
                            StickerPackFfi(
                                PACK,
                                "cd".repeat(32),
                                "cats",
                                "ef".repeat(32),
                                1uL,
                                "Cats",
                                null,
                                null,
                                STICKERS.map(::sticker),
                                null,
                                true,
                            ),
                        )
                    "fetchStickerAsset" -> {
                        val ref = args[1] as StickerRefFfi
                        StickerAssetFfi(sticker(ref), discPng(COLORS.getValue(ref.shortcode)))
                    }
                    "toString" -> "StickerEngineFixture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException("Unused native test call: ${method.name}")
                }
            } as MarmotInterface
    }
}
