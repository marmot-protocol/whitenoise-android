@file:Suppress("MaxLineLength")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneOffset

/** The account-wide Files & media library's layouts and states with a fixed clock and no network (#2000). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class GlobalAttachmentLibraryScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Mixed media tiles and file and audio rows under Today, Yesterday and a month heading. */
    @Test
    fun mixedLibraryLight() {
        render(MIXED, setOf(GlobalSearchContentKind.ANY_ATTACHMENT))
        capture("mixed_light")
    }

    /** The files-only view lists rows naming the sender and chat, in dark. */
    @Test
    fun filesDark() {
        render(MIXED.filter { it.mediaType == "application/pdf" }, setOf(GlobalSearchContentKind.FILES_DOCUMENTS), dark = true)
        capture("files_dark")
    }

    /** Large text, RTL, AMOLED and a narrow screen keep headings, rows and tiles legible. */
    @Test
    fun mixedRtlLargeTextAmoled() {
        render(MIXED, setOf(GlobalSearchContentKind.ANY_ATTACHMENT), dark = true, amoled = true, rtl = true, large = true)
        capture("mixed_rtl_large_amoled")
    }

    /** A failed later batch keeps loaded rows and offers a retry in the footer. */
    @Test
    fun failedBatchKeepsRowsAndOffersRetry() {
        var retries = 0
        render(
            MIXED.takeLast(2),
            setOf(GlobalSearchContentKind.ANY_ATTACHMENT),
            paging = GlobalAttachmentPaging(hasMore = true, failed = true, onRetry = { retries++ }),
        )
        capture("failed_batch")
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    /** Filters MDK refused explain themselves instead of claiming the account has no attachments. */
    @Test
    fun unsupportedFiltersEmptyState() {
        render(emptyList(), setOf(GlobalSearchContentKind.ANY_ATTACHMENT), paging = GlobalAttachmentPaging(filtersUnsupported = true))
        capture("filters_unsupported")
    }

    /** TalkBack hears one sentence per item and can reach Go to Message as a custom action. */
    @Test
    fun itemsAnnounceTheirOriginAndOfferGoToMessage() {
        val opened = mutableListOf<String>()
        val viewed = mutableListOf<String>()
        render(
            MIXED,
            setOf(GlobalSearchContentKind.ANY_ATTACHMENT),
            onOpen = { opened += it.messageIdHex },
            onViewInChat = { viewed += it.messageIdHex },
        )
        val report = composeRule.onNodeWithTag(globalLibraryItemTag(MIXED[2]))

        report.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("File, Quarterly report.pdf, from Bea in Design review, Aug 11"),
            ),
        )
        report.performClick()
        composeRule.runOnIdle {
            report
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
                .action()
        }

        assertEquals(listOf("m3"), opened)
        assertEquals(listOf("m3"), viewed)
    }

    /** Mounts the browser at a fixed day in UTC with deterministic sender names. */
    private fun render(
        items: List<GlobalAttachmentItem>,
        kinds: Set<GlobalSearchContentKind>,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        large: Boolean = false,
        paging: GlobalAttachmentPaging = GlobalAttachmentPaging(),
        onOpen: (GlobalAttachmentItem) -> Unit = {},
        onViewInChat: (GlobalAttachmentItem) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (large) 2f else 1f) {
                    Surface(Modifier.width(if (large) 320.dp else 360.dp).height(720.dp)) {
                        Box {
                            GlobalAttachmentBrowser(
                                items = items,
                                kinds = kinds,
                                loading = false,
                                bottomPadding = 0.dp,
                                actions =
                                    GlobalAttachmentCardActions(
                                        senderLabel = { if (it.sender == "me") "You" else "Bea" },
                                        onOpen = onOpen,
                                        onViewInChat = onViewInChat,
                                        timeLabel = { LABELS.getValue(it.messageIdHex) },
                                    ),
                                paging = paging,
                                zoneId = ZoneOffset.UTC,
                                today = TODAY,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Captures the whole root as the named baseline. */
    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/global_attachment_library_$name.png")
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 12)
        val LABELS = mapOf("m1" to "12:00", "m2" to "12:00", "m3" to "Aug 11", "m4" to "Aug 11", "m5" to "Jul 3")

        /** Seconds at 12:00 UTC on [day] of August 2026, or of July when [july]. */
        fun noon(
            day: Int,
            july: Boolean = false,
        ): ULong =
            LocalDate
                .of(2026, if (july) 7 else 8, day)
                .atTime(12, 0)
                .toEpochSecond(ZoneOffset.UTC)
                .toULong()

        val MIXED =
            listOf(
                item("m1", "image/jpeg", "beach.jpg", noon(12), "Family", sender = "me"),
                item("m2", "video/mp4", "clip.mp4", noon(12), "Family"),
                item("m3", "application/pdf", "Quarterly report.pdf", noon(11), "Design review"),
                item("m4", "audio/ogg", "voice-note.ogg", noon(11), "Design review", sender = "me"),
                item("m5", "image/png", "diagram.png", noon(3, july = true), "Design review"),
            )

        /** One fixture item with stable identity and no reference, so tiles draw their deterministic glyph. */
        fun item(
            id: String,
            mediaType: String,
            label: String,
            at: ULong,
            chat: String,
            sender: String = "bea",
        ) = GlobalAttachmentItem(
            groupIdHex = chat.lowercase().replace(' ', '-'),
            chatTitle = chat,
            messageIdHex = id,
            attachmentIndex = 0,
            mediaType = mediaType,
            label = label,
            timelineAt = at,
            sender = sender,
        )
    }
}
