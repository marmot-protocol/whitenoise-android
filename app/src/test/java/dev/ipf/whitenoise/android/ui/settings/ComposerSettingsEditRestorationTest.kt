package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarLayout
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerToolbarEditState
import dev.ipf.whitenoise.android.ui.conversation.composer.add
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recreates the actual Settings draft holder through Compose's saved-state registry. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class ComposerSettingsEditRestorationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun uncommittedChoicesSurviveRecreationButThePointerTransactionDoesNot() {
        val restoration = StateRestorationTester(composeRule)
        val committed = ComposerToolbarSettings(listOf(ComposerShortcut.More))
        lateinit var edit: ComposerToolbarEditState
        restoration.setContent {
            edit = rememberComposerSettingsEditState(committed)
            WhiteNoiseTheme { Text(edit.draft.encode()) }
        }
        composeRule.runOnIdle {
            edit.add(ComposerShortcut.Camera)
            edit.change(edit.draft.copy(layout = ComposerToolbarLayout.AlwaysExpanded))
            edit.start(ComposerShortcut.Camera, Offset(10f, 10f))
        }
        val before = edit
        val workingCopy = edit.draft

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertNotSame(before, edit)
            assertEquals(workingCopy, edit.draft)
            assertNull(edit.dragging)
            assertEquals(listOf(ComposerShortcut.More), committed.shortcuts)
        }
    }
}
