package dev.ipf.whitenoise.android.ui.group

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
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
@Config(sdk = [36], qualifiers = "en")
class TtsAutoReadComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int): String = app.getString(resId)

    @Test
    fun globalDefaultRowReflectsOffAndOnToggleState() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadGlobalDefaultRow(checked = checked, onCheckedChange = { checked = it })
            }
        }
        composeRule.onNode(isToggleable()).assertIsOff()
        checked = true
        composeRule.waitForIdle()
        composeRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun globalDefaultRowExposesTitleAndSubtitleToTalkBack() {
        val title = string(R.string.tts_auto_read_default_global_title)
        val subtitle = string(R.string.tts_auto_read_default_global_subtitle)
        renderGlobalDefault(checked = false)

        val semantics =
            composeRule
                .onNodeWithTag(TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG)
                .fetchSemanticsNode()
                .config
        val description = semantics.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertEquals("$title. $subtitle", description)
    }

    @Test
    fun globalDefaultRowToggleInvokesCallback() {
        var checked by mutableStateOf(false)
        var enabled: Boolean? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadGlobalDefaultRow(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        enabled = it
                    },
                )
            }
        }

        composeRule.onNode(isToggleable()).performClick()
        composeRule.runOnIdle { assertEquals(true, enabled) }

        composeRule.onNode(isToggleable()).performClick()
        composeRule.runOnIdle { assertEquals(false, enabled) }
    }

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
                TtsAutoReadGroupActionRow(
                    title = title,
                    provenanceLabel = provenance,
                    onClick = {},
                )
            }
        }
        for (label in cases) {
            provenance = label
            composeRule.waitForIdle()
            composeRule.onNodeWithText(title).assertIsDisplayed()
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

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

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun groupActionRowAtLargeFontKeepsFullTitleAndProvenanceVisible() {
        val title = string(R.string.tts_auto_read_title)
        val provenance = string(R.string.tts_auto_read_use_default_off)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                WhiteNoiseTheme {
                    Box(Modifier.width(320.dp)) {
                        TtsAutoReadGroupActionRow(
                            title = title,
                            provenanceLabel = provenance,
                            onClick = {},
                        )
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
                .onNodeWithTag(TTS_AUTO_READ_GROUP_TITLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val provenanceBounds =
            composeRule
                .onNodeWithTag(TTS_AUTO_READ_GROUP_PROVENANCE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val provenanceClipped =
            composeRule
                .onNodeWithTag(TTS_AUTO_READ_GROUP_PROVENANCE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val provenanceUnclipped =
            composeRule
                .onNodeWithTag(TTS_AUTO_READ_GROUP_PROVENANCE_TAG, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()

        val clippedHeight = provenanceClipped.bottom - provenanceClipped.top
        val unclippedHeight = (provenanceUnclipped.bottom - provenanceUnclipped.top).value
        composeRule.onNodeWithText(provenance).assertIsDisplayed()
        assertTrue(titleBounds.bottom <= provenanceBounds.top)
        assertTrue(clippedHeight >= unclippedHeight - 0.5f)
        assertTrue(provenanceBounds.bottom <= rowBounds.bottom + 0.5f)
    }

    @Test
    fun groupActionRowSupportsRtlLayoutAndInteraction() {
        val title = string(R.string.tts_auto_read_title)
        val provenance = string(R.string.tts_auto_read_use_default_on)
        var clicked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme {
                    TtsAutoReadGroupActionRow(
                        title = title,
                        provenanceLabel = provenance,
                        onClick = { clicked = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(provenance).assertIsDisplayed()
        composeRule.onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG).performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

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

    @Test
    fun pickerContentShowsVisibleCheckOnlyOnSelectedOption() {
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadPickerContent(
                    globalDefaultEnabled = false,
                    selectedOverride = TtsAutoReadOverride.ON,
                    onSelect = {},
                )
            }
        }

        val selectedLabel = string(R.string.selected)
        composeRule.onAllNodesWithContentDescription(selectedLabel).assertCountEquals(1)
        composeRule.onNodeWithText(string(R.string.tts_auto_read_override_on)).assertIsSelected()
    }

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

    private fun renderGlobalDefault(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadGlobalDefaultRow(checked = checked, onCheckedChange = onCheckedChange)
            }
        }
        composeRule.waitForIdle()
    }

    private fun renderGroupRow(
        provenanceLabel: String,
        onClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                TtsAutoReadGroupActionRow(
                    title = string(R.string.tts_auto_read_title),
                    provenanceLabel = provenanceLabel,
                    onClick = onClick,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
