package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat row's preview is one line, and the row is 72 dp because of it. The two are one change: the
 * row used to take an 88 dp minimum whenever the preview was taller than the name, which a two-line
 * preview always is, so the tall variant was every populated row rather than an exception.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRowPreviewLinesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A preview far longer than the row is ellipsised on one line rather than wrapping onto a second. */
    @Test
    fun aLongPreviewStaysOnOneLine() {
        render(LONG_PREVIEW)
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(LONG_PREVIEW.take(24), substring = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        assertTrue("no text layout was reported for the preview", results.isNotEmpty())
        results.forEach { assertEquals("the preview must stay on one line", 1, it.lineCount) }
    }

    /**
     * The row is no taller than Material's list-item floor of 88 dp, and a one-line preview no longer
     * pushes it past that.
     *
     * The prototype's row is 68-72 dp, and the pre-port list reached it with a plain Row carrying
     * `heightIn(min = 72.dp)`. The port moved the row onto `ListItem`, whose own minimum is what sets the
     * height now — `ChatRowMinimumHeight` bounds the text column inside it, not the row. Reaching 72 dp
     * means taking the row off `ListItem`, which is more than #2599 scopes.
     */
    @Test
    fun oneLinePreviewDoesNotPushTheRowPastTheListItemFloor() {
        render(LONG_PREVIEW)
        val bounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val rowHeight = bounds.bottom.value - bounds.top.value
        assertTrue("the row grew to $rowHeight dp, past the list-item floor", rowHeight <= 88f)
    }

    private fun render(preview: String) {
        val state = ChatRowPortFixtures.state(context)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.width(360.dp)) {
                        ChatRow(
                            item = ChatRowPortFixtures.item(preview = preview),
                            appState = state,
                            onClick = {},
                            onOpenProfile = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val LONG_PREVIEW =
            "A local message preview long enough to span two lines at the narrow screen width used here"
    }
}
