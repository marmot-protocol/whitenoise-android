package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class EntityPickerSheetTerminalTest {
    @get:Rule val rule = createComposeRule()

    @Test fun doneRejectsRetainedRowActionWhileTheSheetHides() = verifyTerminal("Done")

    @Test fun closeRejectsRetainedRowActionWhileTheSheetHides() = verifyTerminal("Close")

    // Keep the complete retained-window sequence together.
    @Suppress("LongMethod")
    private fun verifyTerminal(action: String) {
        val shown = mutableStateOf(true)
        val selected = mutableStateOf(emptySet<String>())
        var done = 0
        var dismissed = 0
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                if (shown.value) {
                    WhiteNoiseEntityPickerSheet(
                        title = "Choose people",
                        items = listOf(WhiteNoisePickerItem("person", "Sample person")),
                        selectedIds = selected.value,
                        multiple = true,
                        onSelect = { id -> selected.value = selected.value + id },
                        onDone = {
                            done++
                            shown.value = false
                        },
                        onDismiss = {
                            dismissed++
                            shown.value = false
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        val retainedRowAction =
            rule
                .onNode(hasText("Sample person") and hasClickAction())
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        rule.mainClock.autoAdvance = false
        if (action == "Done") {
            rule.onNodeWithText("Done").performClick()
        } else {
            rule.onNodeWithTag("sheet.header").assertExists()
            rule
                .onNode(
                    androidx.compose.ui.test
                        .hasContentDescription("Close"),
                ).performClick()
        }
        rule.mainClock.advanceTimeBy(80)
        // The actual Material window remains present while its accepted hide is in flight.
        rule.onNodeWithTag("sheet.surface").assertExists()
        rule.onNode(hasText("Sample person") and hasClickAction()).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(0, done)
            assertEquals(0, dismissed)
            assertTrue(retainedRowAction())
            assertEquals(emptySet<String>(), selected.value)
        }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle {
            assertEquals(if (action == "Done") 1 else 0, done)
            assertEquals(if (action == "Close") 1 else 0, dismissed)
        }
        rule.onNodeWithTag("sheet.surface").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(1, done + dismissed) }
    }
}
