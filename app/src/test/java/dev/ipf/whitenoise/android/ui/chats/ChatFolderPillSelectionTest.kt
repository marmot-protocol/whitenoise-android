package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The folder pill reports selection through the shared chip the moment the destination changes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ChatFolderPillSelectionTest {
    @get:Rule val rule = createComposeRule()

    /** Selection toggles both ways on the tagged node, with and without a long-press action. */
    @Test
    fun selectionFollowsStateWithAndWithoutLongPress() {
        val selected = mutableStateOf(false)
        rule.setContent {
            WhiteNoiseTheme {
                ChatFolderPill("Folder", selected.value, "pill", onClick = {})
                ChatFolderPill("Editable", selected.value, "editable", onClick = {}, onLongClick = {})
            }
        }
        rule.onNodeWithTag("pill").assertIsNotSelected()
        rule.onNodeWithTag("editable").assertIsNotSelected()
        rule.runOnUiThread {
            selected.value = true
            Snapshot.sendApplyNotifications()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("pill").assertIsSelected()
        rule.onNodeWithTag("editable").assertIsSelected()
    }
}
