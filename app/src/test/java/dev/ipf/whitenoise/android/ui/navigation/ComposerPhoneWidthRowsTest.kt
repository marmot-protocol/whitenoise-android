package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_EDITOR_VIEWPORT_TAG
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins where the production composer puts its editor with four configured shortcuts (More, Emoji, Dictation and
 * Voice note). Each keeps a full 48 dp target, so the row no longer fits beside the editor minimum on a 360 dp phone.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class ComposerPhoneWidthRowsTest : DictationNavigationRenderFixture() {
    /** Releases the preview voice controller the production shell holds between tests. */
    @After
    fun releaseVoiceFixture() = releasePreviewVoiceController()

    /** A 360 dp phone gives the editor its own line above the shortcut row. */
    @Test
    fun fourShortcutsPutTheEditorAboveThemOnA360DpPhone() {
        render(configured = true, startImmediately = false)
        val (editor, shortcuts) = editorAndShortcuts()
        shortcuts.forEach { group ->
            assertTrue("editor $editor must sit above shortcuts $group", editor.bottom <= group.top + 1f)
        }
    }

    /** A 412 dp phone keeps the editor in the same row as the shortcuts. */
    @Test
    @Config(qualifiers = "en-w412dp-h780dp-mdpi")
    fun fourShortcutsShareOneRowWithTheEditorAt412Dp() {
        render(configured = true, startImmediately = false)
        val (editor, shortcuts) = editorAndShortcuts()
        shortcuts.forEach { group ->
            assertTrue(
                "editor $editor must share a row with shortcuts $group",
                group.top < editor.bottom && editor.top < group.bottom,
            )
        }
    }

    /** Reads the editor viewport and every physical shortcut group from the settled layout. */
    private fun editorAndShortcuts(): Pair<Rect, List<Rect>> {
        val editor =
            composeRule
                .onNodeWithTag(COMPOSER_EDITOR_VIEWPORT_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val shortcuts =
            composeRule
                .onAllNodesWithTag("composer.shortcuts", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
        assertTrue("shortcut groups are present", shortcuts.isNotEmpty())
        listOf("more", "emoji", "dictation", "voice_note").forEach { id ->
            val shortcut =
                composeRule
                    .onNodeWithTag("composer.shortcut.$id", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertEquals("$id keeps a whole 48 dp target", 48f, shortcut.width, 0.5f)
            assertEquals("$id keeps a whole 48 dp target", 48f, shortcut.height, 0.5f)
        }
        return editor to shortcuts
    }
}
