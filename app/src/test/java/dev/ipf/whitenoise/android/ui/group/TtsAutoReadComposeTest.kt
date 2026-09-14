package dev.ipf.whitenoise.android.ui.group

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Contract of the per-chat Read Aloud row and its override picker: semantics, provenance and selection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class TtsAutoReadComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Resolves a string resource in the test context. */
    private fun string(resId: Int): String = app.getString(resId)

    /** Every provenance label the row can show renders beside the title. */
    @Test
    fun groupActionRowRendersEachProvenanceLabel() {
        val title = string(R.string.tts_auto_read_title)
        val cases =
            listOf(
                string(R.string.tts_auto_read_use_default_off),
                string(R.string.tts_auto_read_use_default_on),
                string(R.string.tts_auto_read_override_on),
                string(R.string.tts_auto_read_override_off),
            )
        var provenance by mutableStateOf(cases.first())
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    row("auto_read") { rowContext ->
                        TtsAutoReadGroupActionRow(
                            context = rowContext,
                            title = title,
                            provenanceLabel = provenance,
                            onClick = {},
                        )
                    }
                }
            }
        }
        for (label in cases) {
            provenance = label
            composeRule.waitForIdle()
            composeRule.onNodeWithText(title).assertIsDisplayed()
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    /** TalkBack hears the title and the provenance as one announcement. */
    @Test
    fun groupActionRowMergesTitleAndProvenanceForTalkBack() {
        val title = string(R.string.tts_auto_read_title)
        val provenance = string(R.string.tts_auto_read_use_default_on)
        renderGroupRow(provenanceLabel = provenance)
        val description =
            composeRule
                .onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString()
        assertEquals("$title. $provenance", description)
    }

    /** The row is a button and a tap reaches the caller. */
    @Test
    fun groupActionRowIsButtonAndInvokesClick() {
        var clicked = false
        val provenance = string(R.string.tts_auto_read_use_default_on)
        renderGroupRow(provenanceLabel = provenance, onClick = { clicked = true })
        composeRule
            .onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG)
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    /** At 200 % type on a narrow screen the title sits above an unclipped provenance line inside the row. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun groupActionRowAtLargeFontKeepsFullTitleAndProvenanceVisible() {
        val title = string(R.string.tts_auto_read_title)
        val provenance = string(R.string.tts_auto_read_use_default_off)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                WhiteNoiseTheme {
                    Box(Modifier.width(320.dp)) {
                        SettingsGroup {
                            row("auto_read") { rowContext ->
                                TtsAutoReadGroupActionRow(
                                    context = rowContext,
                                    title = title,
                                    provenanceLabel = provenance,
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val rowBounds =
            composeRule
                .onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val titleBounds =
            composeRule
                .onNode(hasText(title), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val provenanceNode = composeRule.onNode(hasText(provenance), useUnmergedTree = true)
        val provenanceBounds = provenanceNode.fetchSemanticsNode().boundsInRoot
        val provenanceUnclipped = provenanceNode.getUnclippedBoundsInRoot()
        val clippedHeight = provenanceBounds.bottom - provenanceBounds.top
        val unclippedHeight = (provenanceUnclipped.bottom - provenanceUnclipped.top).value
        composeRule.onNodeWithText(provenance).assertIsDisplayed()
        assertTrue(titleBounds.bottom <= provenanceBounds.top)
        assertTrue(clippedHeight >= unclippedHeight - 0.5f)
        assertTrue(provenanceBounds.bottom <= rowBounds.bottom + 0.5f)
    }

    /** The row reads and taps the same way under RTL. */
    @Test
    fun groupActionRowSupportsRtlLayoutAndInteraction() {
        val title = string(R.string.tts_auto_read_title)
        val provenance = string(R.string.tts_auto_read_use_default_on)
        var clicked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme {
                    SettingsGroup {
                        row("auto_read") { rowContext ->
                            TtsAutoReadGroupActionRow(
                                context = rowContext,
                                title = title,
                                provenanceLabel = provenance,
                                onClick = { clicked = true },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(provenance).assertIsDisplayed()
        composeRule.onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG).performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    /** The default option is named by the global default's current value and is selected when no override is set. */
    @Test
    fun pickerContentShowsResolvedDefaultOnAndOffOptions() {
        var globalDefault by mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadPickerContent(
                    globalDefaultEnabled = globalDefault,
                    selectedOverride = null,
                    onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.tts_auto_read_use_default_off)).assertIsSelected()
        globalDefault = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.tts_auto_read_use_default_on)).assertIsSelected()
    }

    /** Explicit on and off overrides select their own row. */
    @Test
    fun pickerContentMarksExplicitOnAndOffSelections() {
        var selected by mutableStateOf<TtsAutoReadOverride?>(TtsAutoReadOverride.ON)
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadPickerContent(
                    globalDefaultEnabled = false,
                    selectedOverride = selected,
                    onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.tts_auto_read_override_on)).assertIsSelected()
        selected = TtsAutoReadOverride.OFF
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.tts_auto_read_override_off)).assertIsSelected()
    }

    /** Every option carries radio-style selectable semantics. */
    @Test
    fun pickerContentUsesRadioSelectableSemantics() {
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadPickerContent(
                    globalDefaultEnabled = false,
                    selectedOverride = null,
                    onSelect = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.tts_auto_read_use_default_off)).assert(isSelectable())
        composeRule.onNodeWithText(string(R.string.tts_auto_read_override_on)).assert(isSelectable())
        composeRule.onNodeWithText(string(R.string.tts_auto_read_override_off)).assert(isSelectable())
    }

    /** Exactly one option reports itself selected. */
    @Test
    fun pickerContentSelectsExactlyOneOption() {
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadPickerContent(
                    globalDefaultEnabled = false,
                    selectedOverride = TtsAutoReadOverride.ON,
                    onSelect = {},
                )
            }
        }
        composeRule.onAllNodes(isSelected()).assertCountEquals(1)
        composeRule.onNodeWithText(string(R.string.tts_auto_read_override_on)).assertIsSelected()
    }

    /** Tapping an option reports that override; tapping the default reports null. */
    @Test
    fun pickerContentInvokesSelectedOverrideCallback() {
        var globalDefault by mutableStateOf(false)
        var selected: TtsAutoReadOverride? = TtsAutoReadOverride.ON
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadPickerContent(
                    globalDefaultEnabled = globalDefault,
                    selectedOverride = selected,
                    onSelect = { selected = it },
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.tts_auto_read_override_off)).performClick()
        composeRule.runOnIdle { assertEquals(TtsAutoReadOverride.OFF, selected) }
        globalDefault = true
        selected = TtsAutoReadOverride.OFF
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.tts_auto_read_use_default_on)).performClick()
        composeRule.runOnIdle { assertEquals(null, selected) }
    }

    /** Renders group row. */
    private fun renderGroupRow(
        provenanceLabel: String,
        onClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    row("auto_read") { rowContext ->
                        TtsAutoReadGroupActionRow(
                            context = rowContext,
                            title = string(R.string.tts_auto_read_title),
                            provenanceLabel = provenanceLabel,
                            onClick = onClick,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
