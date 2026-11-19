@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.media.ImageSearchClient
import dev.ipf.whitenoise.android.media.ImageSearchResult
import dev.ipf.whitenoise.android.ui.account.AccountSelectorContent
import dev.ipf.whitenoise.android.ui.account.accountSelectorState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Source-render capture only: no APK, hardware, route-origin or bitmap-continuity proof. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class RemainingSheetMotionCaptureTest {
    @get:Rule val rule = createComposeRule()

    /** Actual ImageSearchSheet Cancel owns its terminal animation; no callback shortcut. */
    @Test fun imageSearchCancel() {
        chapter("image-search-cancel", { close ->
            ImageSearchSheet(
                initialUrl = "",
                header = "Edit group image",
                title = "Group image",
                seed = "synthetic-group",
                urlLabel = "Image URL",
                applyInFlight = false,
                onApply = { error("Cancel capture must not apply") },
                onDismiss = close,
                searchClient =
                    object : ImageSearchClient {
                        override suspend fun search(query: String): List<ImageSearchResult> = emptyList()
                    },
                resultImageLoader = { null },
                resultImageCacheLookup = { null },
            )
        }) { rule.onNodeWithText("Cancel").performClick() }
    }

    /** Actual chooser Close/Cancel requires no render, image upload or mutation fake. */
    @Test fun groupEmojiClose() {
        chapter("group-emoji-close", { close ->
            GroupEmojiImagePickerSheet(
                applyInFlight = false,
                recentEmojis = listOf("😀", "🚀"),
                onEmojiUsed = {},
                onApply = { error("Close capture must not apply") },
                onDismiss = close,
                renderer = { error("No selection in Close capture") },
            )
        }) { rule.onNodeWithText("Cancel").performClick() }
    }

    /** Production content in its real Material parent type, NOT AccountSelectorSheet route. */
    @Test fun accountSelectorContentMaterialDismiss() {
        chapter("account-selector-content-material-parent", { close ->
            WhiteNoiseModalBottomSheet(onDismissRequest = close) {
                AccountSelectorContent(
                    state = accountSelectorState(listOf(account("personal"), account("work")), "personal", false),
                    displayName = { if (it == "hex-personal") "Personal" else "Work" },
                    shortNpub = { "npub…synthetic" },
                    avatarUrl = { null },
                    unreadCountForAccount = { if (it == "work") 4uL else 0uL },
                    onSwitchAccount = { error("Dismiss capture must not switch") },
                    onAddAccount = { error("Dismiss capture must not add") },
                )
            }
        }) {
            rule
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        }
    }

    private fun account(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = "hex-$label",
            localSigning = true,
            signedOut = false,
            running = true,
            externalSigning = false,
            unlinked = false,
        )

    private fun chapter(
        name: String,
        content: @Composable (() -> Unit) -> Unit,
        exit: () -> Unit,
    ) {
        assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val shown = mutableStateOf(false)
        var dismissals = 0
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (shown.value) {
                        content {
                            dismissals++
                            shown.value = false
                        }
                    }
                }
            }
        }
        capture("$name/forward") { rule.runOnIdle { shown.value = true } }
        rule.runOnIdle {
            assertEquals(true, shown.value)
            assertEquals(0, dismissals)
        }
        capture("$name/reverse", exit)
        rule.runOnIdle {
            assertEquals(false, shown.value)
            assertEquals(1, dismissals)
        }
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
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        repeat(48) { frame ->
            rule.mainClock.advanceTimeBy(16L)
            rule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }
}
