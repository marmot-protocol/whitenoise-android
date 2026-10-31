package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ManualUnreadDot
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.chatRowBadgeDiameter
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The production unread host occupies only the shared badge, returning unused width to the preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ChatRowBadgeSpacingCoverageTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousScale = 1f

    /** Retain the emulated setting so the fixture cannot affect another test's motion policy. */
    @Before
    fun saveMotionPolicy() {
        previousScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** Restore only this isolated Android fixture's setting. */
    @After
    fun restoreMotionPolicy() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
    }

    /** Counts, dot, mention and delivery share compact occupied geometry in light mode with motion. */
    @Test
    fun lightLtrSettledBadgesUseTheirActualWidth() = checkGeometry(dark = false, reduced = false)

    /** Reduced motion must not preserve the old widest-label reservation. */
    @Test
    fun darkReducedMotionBadgesUseTheirActualWidth() = checkGeometry(dark = true, reduced = true)

    /** At double font scale in a narrow RTL row, the trailing anchor mirrors without extra slack. */
    @Test
    fun amoledLargeRtlBadgesUseTheirActualWidth() = checkGeometry(dark = true, reduced = false, largeRtl = true)

    /** Compare every production host to an independently laid-out real badge, never to another motion host. */
    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
    ) // Keep the full production/reference matrix and its exit check together.
    private fun checkGeometry(
        dark: Boolean,
        reduced: Boolean,
        largeRtl: Boolean = false,
    ) {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            if (reduced) 0f else 1f,
        )
        val count = mutableStateOf(1uL)
        val mention = mutableStateOf(false)
        val delivery = mutableStateOf(false)
        val unread = mutableStateOf(true)
        val appState = ChatRowPortFixtures.state(context)
        var deliverySlotWidth = 0f
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = largeRtl) {
                    deliverySlotWidth = with(LocalDensity.current) { chatRowBadgeDiameter().toPx() }
                    Column {
                        val original =
                            ChatRowPortFixtures.item(
                                unread = unread.value,
                                delivery =
                                    if (delivery.value) {
                                        ChatListMessageDeliveryStateFfi.DELIVERED
                                    } else {
                                        ChatListMessageDeliveryStateFfi.NOT_APPLICABLE
                                    },
                            )
                        val item =
                            original.copy(
                                projection =
                                    requireNotNull(original.projection).copy(
                                        unreadCount = count.value,
                                        unreadMention = mention.value,
                                        manuallyMarkedUnread = unread.value && count.value == 0uL,
                                    ),
                            )
                        ChatRow(
                            item,
                            appState,
                            onClick = {},
                            onOpenProfile = {},
                            modifier = Modifier.width(280.dp).testTag(ROW),
                        )
                        Box(Modifier.testTag(REFERENCE)) {
                            if (count.value > 0uL) UnreadCountBadge(count.value) else ManualUnreadDot()
                        }
                    }
                }
            }
        }
        val title = bounds(hasText(ChatRowPortFixtures.TITLE))
        val timestamp = bounds(hasText(context.getString(R.string.relative_time_now)))
        val gap = with(rule.density) { 4.dp.toPx() }
        val previewGap = with(rule.density) { 8.dp.toPx() }
        for (hasDelivery in listOf(false, true)) {
            for (hasMention in listOf(false, true)) {
                var cappedPreview: Rect? = null
                var trailing: Float? = null
                // Descending widths make the preview gain observable after the widest real label.
                for (value in listOf(1_001uL, 999uL, 10uL, 9uL, 1uL, 0uL)) {
                    rule.runOnIdle {
                        count.value = value
                        mention.value = hasMention
                        delivery.value = hasDelivery
                    }
                    rule.waitForIdle()
                    val descriptionMatcher = hasContentDescription(description(value))
                    val badge = bounds(descriptionMatcher and hasAnyAncestor(descriptionMatcher).not())
                    val reference = rule.onNodeWithTag(REFERENCE).fetchSemanticsNode().boundsInRoot
                    assertEquals("No invisible prefix before count $value", reference.width, badge.width, 0.5f)
                    val edge = if (largeRtl) badge.left else badge.right
                    trailing?.let { assertEquals("Count changes retain trailing alignment", it, edge, 0.5f) }
                    trailing = edge
                    assertEquals(title, bounds(hasText(ChatRowPortFixtures.TITLE)))
                    assertEquals(timestamp, bounds(hasText(context.getString(R.string.relative_time_now))))
                    val preview = bounds(hasText(ChatRowPortFixtures.PREVIEW, substring = true))
                    if (value == 1_001uL) cappedPreview = preview
                    if (value == 1uL || value == 0uL) {
                        assertTrue(
                            "A compact badge returns width to the production preview",
                            preview.width > cappedPreview!!.width,
                        )
                    }
                    var first = badge
                    if (hasMention) {
                        val mentioned =
                            bounds(hasContentDescription(context.getString(R.string.chat_list_mention_badge)))
                        assertGap(mentioned, badge, gap, largeRtl)
                        first = mentioned
                    } else {
                        rule
                            .onNode(
                                inRow(hasContentDescription(context.getString(R.string.chat_list_mention_badge))),
                                useUnmergedTree = true,
                            ).assertDoesNotExist()
                    }
                    if (hasDelivery) {
                        val glyph = bounds(hasContentDescription(context.getString(R.string.sent)))
                        // The existing 14 dp glyph is centered in its font-scaled shared diameter.
                        val inset = (deliverySlotWidth - glyph.width) / 2
                        val delivered = Rect(glyph.left - inset, glyph.top, glyph.right + inset, glyph.bottom)
                        assertGap(delivered, first, gap, largeRtl)
                        first = delivered
                    } else {
                        rule
                            .onNode(
                                inRow(hasContentDescription(context.getString(R.string.sent))),
                                useUnmergedTree = true,
                            ).assertDoesNotExist()
                    }
                    assertGap(preview, first, previewGap, largeRtl)
                }
            }
        }
        rule.runOnIdle {
            unread.value = false
            mention.value = false
            delivery.value = false
        }
        rule.waitForIdle()
        rule.onNode(inRow(hasContentDescription(description(0uL))), useUnmergedTree = true).assertDoesNotExist()
        val readPreview = bounds(hasText(ChatRowPortFixtures.PREVIEW, substring = true))
        assertEquals(
            "Exit releases every trailing badge pixel",
            timestampTrailing(timestamp, largeRtl),
            timestampTrailing(readPreview, largeRtl),
            0.5f,
        )
    }

    /** Resolve only the real row; the independent reference carries its own ordinary badge semantics. */
    private fun bounds(matcher: androidx.compose.ui.test.SemanticsMatcher): Rect =
        rule.onNode(inRow(matcher), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun inRow(matcher: androidx.compose.ui.test.SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag(ROW))

    /** Compare logical neighboring bounds, so both LTR and RTL exercise the same intended gaps. */
    private fun assertGap(
        first: Rect,
        second: Rect,
        expected: Float,
        rtl: Boolean,
    ) {
        assertEquals(expected, if (rtl) first.left - second.right else second.left - first.right, 0.5f)
    }

    private fun timestampTrailing(
        bounds: Rect,
        rtl: Boolean,
    ) = if (rtl) bounds.left else bounds.right

    private fun description(count: ULong): String =
        if (count == 0uL) {
            context.getString(R.string.chat_row_marked_unread)
        } else {
            context.resources.getQuantityString(R.plurals.unread_messages_count, count.toInt(), count.toInt())
        }

    private companion object {
        const val ROW = "spacing.production.row"
        const val REFERENCE = "spacing.reference.badge"
    }
}
