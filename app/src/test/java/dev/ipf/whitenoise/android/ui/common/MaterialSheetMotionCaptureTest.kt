package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.messages.ReportMessageSheet
import dev.ipf.whitenoise.android.ui.conversation.reactions.ConfigureReactionsSheet
import dev.ipf.whitenoise.android.ui.settings.AddRelaySheet
import dev.ipf.whitenoise.android.ui.settings.SmartFolderAddFilter
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual shared sheets, exported opt-in with synthetic data and no MDK mutations. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MaterialSheetMotionCaptureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun captureEmojiSelection() =
        chapter("emoji-sheet", { close ->
            EmojiPickerSheet(onDismissRequest = close, onEmojiPicked = { close() })
        }) {
            val emoji =
                SemanticsMatcher("emoji choice") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("emoji.picker.item.") ==
                        true
                }
            rule.onAllNodes(emoji)[0].performClick()
        }

    @Test fun captureReactionConfiguration() =
        chapter("reactions-sheet", { close ->
            ConfigureReactionsSheet(
                current =
                    listOf(
                        "👍",
                        "❤️",
                        "😂",
                    ),
                onDismiss = close,
                onApply = { close() },
                onPickSlot = {
                    _,
                    _,
                    ->
                    close()
                },
            )
        }) { rule.onNodeWithText("Done").performClick() }

    @Test fun captureEntityPicker() =
        chapter("entity-sheet", { close ->
            WhiteNoiseEntityPickerSheet(
                title = "Choose people",
                items = listOf(WhiteNoisePickerItem("synthetic", "Alice")),
                multiple = true,
                onSelect = {},
                onDismiss = close,
                onDone = close,
            )
        }) { rule.onNodeWithText("Done").performClick() }

    @Test fun captureFilterChoices() =
        chapter("folder-filter-sheet", { close ->
            SmartFolderAddFilter(people = emptyList(), resolveKey = { null }, onDismiss = close, onDone = { close() })
        }) { rule.onNodeWithContentDescription("Close").performClick() }

    @Test fun captureAddRelay() =
        chapter("relay-sheet", { close ->
            AddRelaySheet(existing = emptyList(), busy = false, rejectedUrl = null, onDismiss = close, onAdd = {
                _,
                _,
                finish,
                ->
                finish()
            })
        }) { rule.onNodeWithContentDescription("Close").performClick() }

    @Test fun captureReportOutcome() =
        chapter("report-sheet", { close ->
            ReportMessageSheet(onDismissRequest = close, onSubmit = { _, _, finish -> finish() })
        }) { rule.onNodeWithTag("message.report.send").performClick() }

    private fun chapter(
        name: String,
        content: @Composable (() -> Unit) -> Unit,
        exit: () -> Unit,
    ) {
        assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val shown = mutableStateOf(false)
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { if (shown.value) content { shown.value = false } }
            }
        }
        capture("$name/forward") { rule.runOnIdle { shown.value = true } }
        capture("$name/reverse", exit)
    }

    private fun capture(
        name: String,
        action: () -> Unit,
    ) {
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        rule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(48) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }
}
