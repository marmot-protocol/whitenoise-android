package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1100dp-mdpi")
class SmartFolderEditorTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun absenceAndIgnoreAreDifferentDraftEdits() {
        val root = render(SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Condition(FolderField.UNREAD))))
        composeRule.onNodeWithTag("folder.condition.0").performClick()
        composeRule.onNodeWithTag("folder.mode").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_no_unread)).performClick()
        composeRule.onNodeWithTag("folder.conditionDone").performClick()
        assertEquals(FolderMode.NONE, (root.value.children.single() as SmartFolderFilter.Condition).mode)
        composeRule.onNodeWithTag("folder.condition.0").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_ignore)).performClick()
        assertTrue(root.value.children.isEmpty())
    }

    @Test fun matchAnyAndNestedNotRemainIndependent() {
        val child = SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Condition(FolderField.MENTIONS)))
        val second = SmartFolderFilter.Condition(FolderField.UNREAD)
        val root = render(SmartFolderFilter.Group(children = listOf(child, second)))
        composeRule.onNodeWithTag("folder.match.").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_any)).performClick()
        assertFalse(root.value.all)
        composeRule
            .onNodeWithText(
                context.getString(R.string.smart_folder_all) + " · " +
                    context.resources.getQuantityString(R.plurals.smart_folder_group_items, 1, 1),
            ).performClick()
        composeRule.onNodeWithTag("folder.options.0").performClick()
        composeRule.onNodeWithTag("folder.not.0").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_not_hint)).performClick()
        assertTrue((root.value.children.first() as SmartFolderFilter.Group).not)
        assertFalse(root.value.not)
    }

    @Test fun participantConditionRequiresSelectionBeforeSaveAndPickerPreservesChoices() {
        val root = render(SmartFolderFilter.Group())
        composeRule.onNodeWithTag("folder.add.").performClick()
        composeRule.onNodeWithTag("folder.addField.PARTICIPANTS").performClick()
        composeRule.onNodeWithTag("folder.conditionDone").assertIsNotEnabled()
        composeRule.onNodeWithTag("folder.choosePeople").performClick()
        composeRule.onNodeWithText("Agent").performClick()
        composeRule.onNodeWithText(context.getString(R.string.done)).performClick()
        composeRule.onNodeWithTag("folder.conditionDone").assertIsEnabled().performClick()
        assertEquals(
            setOf("a".repeat(64)),
            (root.value.children.single() as SmartFolderFilter.Condition).values,
        )
    }

    @Test fun cancelDoesNotChangeTheRuleAndTreeEditsDoNotMutateSiblingGroups() {
        val initial = defaultSmartFolder()
        val root = render(initial)
        composeRule.onNodeWithTag("folder.condition.0").performClick()
        composeRule.onNodeWithTag("folder.mode").performClick()
        composeRule.onNodeWithText(context.getString(R.string.yes)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(initial, root.value)
        val child = SmartFolderFilter.Group(children = listOf(SmartFolderFilter.Condition(FolderField.UNREAD)))
        val tree = SmartFolderFilter.Group(children = listOf(child, child))
        val changed = tree.updateAt(listOf(0, 0), SmartFolderFilter.Condition(FolderField.MENTIONS))
        assertEquals(child, changed.children[1])
        assertNotEquals(child, changed.children[0])
    }

    @Test fun allReadPresetIncludesActiveAcceptedScopeAndManualUnreadPredicate() {
        val root = mutableStateOf(SmartFolderFilter.Group())
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    SmartFolderRulePanel(
                        SmartFolderPanelState(true, root.value),
                        emptyList(),
                        resolveKey = { null },
                        onChange = { root.value = it },
                        legacyControls = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("folder.presets").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_preset_read)).performClick()
        composeRule.onNodeWithTag("folder.confirmPreset").performClick()
        assertTrue(root.value.all)
        val conditions =
            root.value.children
                .filterIsInstance<SmartFolderFilter.Condition>()
                .associateBy { it.field }
        assertEquals(setOf(FolderField.ARCHIVED, FolderField.ACCEPTED, FolderField.UNREAD), conditions.keys)
        assertEquals(FolderMode.NONE, conditions.getValue(FolderField.UNREAD).mode)
        assertEquals(FolderMode.NONE, conditions.getValue(FolderField.ARCHIVED).mode)
        assertEquals(FolderMode.PRESENT, conditions.getValue(FolderField.ACCEPTED).mode)
        assertFalse(conditions.containsKey(FolderField.MUTED))
    }

    @Test fun extraLogicIsOptionalButAnExistingExclusionStaysVisible() {
        render(SmartFolderFilter.Group(not = true, children = listOf(SmartFolderFilter.Condition(FolderField.UNREAD))))
        composeRule.onNodeWithTag("folder.not.").assertExists()
        composeRule.onNodeWithTag("folder.options.").performClick()
        composeRule.onNodeWithTag("folder.not.").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_not_hint)).assertExists()
        composeRule.onNodeWithTag("folder.condition.0").performClick()
        composeRule.onNodeWithTag("folder.conditionNot").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.conditionOptions").performClick()
        composeRule.onNodeWithTag("folder.conditionNot").assertExists()
    }

    @Test fun presetStartsDirectlyFromSimpleModeAndReplacementCanBeCancelled() {
        val root = mutableStateOf<SmartFolderFilter.Group?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    SmartFolderRulePanel(
                        SmartFolderPanelState(root.value != null, root.value),
                        emptyList(),
                        resolveKey = { null },
                        onChange = { root.value = it },
                        legacyControls = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("folder.presetRead").performClick()
        val read = root.value
        assertEquals(FolderMode.NONE, (read!!.children.last() as SmartFolderFilter.Condition).mode)
        composeRule.onNodeWithTag("folder.presets").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_preset_mentions)).performClick()
        assertEquals(read, root.value)
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(read, root.value)
        composeRule.onNodeWithTag("folder.presets").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_preset_mentions)).performClick()
        composeRule.onNodeWithTag("folder.confirmPreset").performClick()
        assertEquals(FolderField.MENTIONS, (root.value!!.children.last() as SmartFolderFilter.Condition).field)
    }

    @Test fun replacingSavedFiltersRequiresConfirmationAndCancelKeepsTheOriginal() {
        val root = mutableStateOf<SmartFolderFilter.Group?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    SmartFolderRulePanel(
                        SmartFolderPanelState(root.value != null, root.value, confirmSimpleReplacement = true),
                        emptyList(),
                        resolveKey = { null },
                        onChange = { root.value = it },
                        legacyControls = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("folder.presets").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_preset_read)).performClick()
        assertEquals(null, root.value)
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithTag("folder.add.").performClick()
        composeRule.onNodeWithTag("folder.addField.UNREAD").performClick()
        composeRule.onNodeWithTag("folder.conditionDone").performClick()
        assertEquals(null, root.value)
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(null, root.value)
        composeRule.onNodeWithTag("folder.add.").performClick()
        composeRule.onNodeWithTag("folder.addField.MENTIONS").performClick()
        composeRule.onNodeWithTag("folder.conditionDone").performClick()
        composeRule.onNodeWithTag("folder.confirmPreset").performClick()
        assertEquals(FolderField.MENTIONS, (root.value!!.children.last() as SmartFolderFilter.Condition).field)
    }

    @Test fun addingFilterCancellationDoesNotCreateAnEmptyRuleOrOfferRemoval() {
        val root = render(SmartFolderFilter.Group())
        composeRule.onNodeWithTag("folder.add.").performClick()
        composeRule.onNodeWithTag("folder.moreFilters").assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(1000L)
        composeRule.onNodeWithTag("folder.addField.DRAFT").assertIsDisplayed()
        composeRule.onNodeWithTag("folder.addField.DRAFT").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_ignore)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertTrue(root.value.children.isEmpty())
    }

    @Test
    fun filterSheetShowsAllChoicesWithoutExpandingAndKeepsCompactTouchTargets() {
        render(SmartFolderFilter.Group())
        composeRule
            .onNodeWithTag("folder.add.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeRule.mainClock.advanceTimeBy(1000L)
        FolderField.entries.forEach {
            composeRule.onNodeWithTag("folder.addField.${it.name}").assertIsDisplayed()
        }
        val first = composeRule.onNodeWithTag("folder.addField.PARTICIPANTS").fetchSemanticsNode().boundsInRoot
        val next = composeRule.onNodeWithTag("folder.addField.UNREAD").fetchSemanticsNode().boundsInRoot
        assertTrue(first.height >= 48f)
        assertTrue(next.top - first.top <= 56f)
        composeRule.onNodeWithTag("folder.moreFilters").assertDoesNotExist()
    }

    @Test fun singleFilterHidesMatchChoiceWithoutChangingItsStoredMode() {
        val unread = SmartFolderFilter.Condition(FolderField.UNREAD)
        val root = render(SmartFolderFilter.Group(all = false, children = listOf(unread)))
        composeRule.onNodeWithTag("folder.match.").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.add.").performClick()
        composeRule.onNodeWithTag("folder.addField.MENTIONS").performClick()
        composeRule.onNodeWithTag("folder.conditionDone").performClick()
        composeRule.onNodeWithTag("folder.match.").assertExists()
        assertFalse(root.value.all)
    }

    @Test
    @Config(qualifiers = "en-w640dp-h360dp-mdpi")
    fun shortLandscapeAtDoubleTextSizeCanReachTheLastFilter() {
        render(SmartFolderFilter.Group(), fontScale = 2f)
        composeRule.onNodeWithTag("folder.add.").performClick()
        composeRule.mainClock.advanceTimeBy(1000L)
        composeRule
            .onNodeWithTag("folder.addField.PINNED")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("folder.conditionDone").assertExists()
    }

    @Test fun pairedControlsReplaceClearAndResetOnlyTheVisiblePredicates() {
        val initial = defaultSmartFolder()
        val root = renderPanel(initial)
        quickFolderFields.forEach { field ->
            val positive = if (field == FolderField.TYPE) FolderMode.DIRECT else FolderMode.PRESENT
            val negative = if (field == FolderField.TYPE) FolderMode.GROUP else FolderMode.NONE
            val positiveTag = "folder.quick.${field.name}.${positive.name}"
            val negativeTag = "folder.quick.${field.name}.${negative.name}"
            composeRule.onNodeWithTag(positiveTag).performScrollTo().performClick()
            assertEquals(positive, root.value!!.quickMode(field))
            composeRule
                .onNodeWithTag(positiveTag)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            composeRule.onNodeWithTag(negativeTag).performScrollTo().performClick()
            assertEquals(negative, root.value!!.quickMode(field))
            assertEquals(
                1,
                root.value!!
                    .children
                    .filterIsInstance<SmartFolderFilter.Condition>()
                    .count { it.field == field },
            )
            composeRule.onNodeWithTag(negativeTag).performClick()
            assertEquals(null, root.value!!.quickMode(field))
        }
        composeRule.onNodeWithTag("folder.quick.UNREAD.PRESENT").performScrollTo().performClick()
        composeRule.onNodeWithTag("folder.quick.reset").performScrollTo().performClick()
        assertEquals(listOf(SmartFolderFilter.Condition(FolderField.ACCEPTED)), root.value!!.children)
    }

    @Test fun pairedRowsAnnounceNeutralAndBothSelectionsAndAdvancedExpansion() {
        renderPanel(SmartFolderFilter.Group())
        val positive = composeRule.onNodeWithTag("folder.quick.UNREAD.PRESENT")
        val negative = composeRule.onNodeWithTag("folder.quick.UNREAD.NONE")

        fun assertRowState(choice: Int) {
            val expected =
                context.getString(
                    R.string.smart_folder_quick_state,
                    context.getString(R.string.smart_folder_unread),
                    context.getString(choice),
                )
            positive.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
            negative.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
        }
        assertRowState(R.string.smart_folder_quick_neutral)
        positive.performScrollTo().performClick()
        assertRowState(R.string.smart_folder_quick_unread)
        negative.performClick()
        assertRowState(R.string.smart_folder_no_unread)
        negative.performClick()
        assertRowState(R.string.smart_folder_quick_neutral)
        val advanced = composeRule.onNodeWithTag("folder.quick.advanced")
        advanced
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.smart_folder_quick_collapsed),
                ),
            ).performScrollTo()
            .performClick()
        advanced.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.smart_folder_quick_expanded),
            ),
        )
    }

    @Test fun switchingAdvancedPresentationAndCancellingAnEditPreservesTheExactDraft() {
        val initial = defaultSmartFolder().toggleQuickFilter(FolderField.DRAFT, FolderMode.NONE)
        val root = renderPanel(initial)
        assertEquals(initial, root.value)
        composeRule.onNodeWithTag("folder.quick.advanced").performScrollTo().performClick()
        composeRule.onNodeWithTag("folder.condition.2").performScrollTo().performClick()
        composeRule.onNodeWithTag("folder.mode").performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_has_draft)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(initial, root.value)
        composeRule.onNodeWithTag("folder.quick.advanced").performScrollTo().performClick()
        assertEquals(initial, root.value)
        composeRule
            .onNodeWithTag("folder.quick.DRAFT.NONE")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test fun legacyQuickReplacementCanBeCancelledBeforeAnyDraftChange() {
        val root = renderPanel(null, legacy = true)
        composeRule.onNodeWithTag("folder.quick.start").performClick()
        assertEquals(null, root.value)
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(null, root.value)
        composeRule.onNodeWithTag("folder.quick.start").performClick()
        composeRule.onNodeWithTag("folder.confirmPreset").performClick()
        assertEquals(defaultSmartFolder(), root.value)
    }

    @Test fun complexRulesHaveNoQuickControlsOrImplicitNormalization() {
        val nested = SmartFolderFilter.Group(children = listOf(defaultSmartFolder()), not = true)
        val root = renderPanel(nested)
        composeRule.onNodeWithTag("folder.quick.UNREAD.PRESENT").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.quick.advanced").assertDoesNotExist()
        assertEquals(nested, root.value)
    }

    @Test fun quickDraftAndAdvancedDisclosureSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        var current: SmartFolderFilter.Group? = null
        restoration.setContent {
            var payload by androidx.compose.runtime.saveable.rememberSaveable {
                mutableStateOf(
                    dev.ipf.whitenoise.android.state.SmartFolderCodec
                        .encode(defaultSmartFolder()),
                )
            }
            val tree =
                dev.ipf.whitenoise.android.state.SmartFolderCodec
                    .decode(payload)!!
            current = tree
            WhiteNoiseTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SmartFolderRulePanel(
                            SmartFolderPanelState(true, tree),
                            emptyList(),
                            { null },
                            onChange = {
                                payload =
                                    dev.ipf.whitenoise.android.state.SmartFolderCodec
                                        .encode(it)
                            },
                            legacyControls = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("folder.quick.UNREAD.NONE").performScrollTo().performClick()
        val edited = current
        composeRule.onNodeWithTag("folder.quick.advanced").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(edited, current)
        composeRule.onNodeWithTag("folder.group.").assertExists()
        composeRule.onNodeWithTag("folder.quick.UNREAD.NONE").assertDoesNotExist()
    }

    @Test fun becomingIncompatibleInAdvancedNeverOffersAFlatteningQuickToggle() {
        val root = renderPanel(defaultSmartFolder())
        composeRule.onNodeWithTag("folder.quick.advanced").performScrollTo().performClick()
        composeRule.onNodeWithTag("folder.match.").performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.smart_folder_any)).performClick()
        val any = root.value
        assertFalse(any!!.all)
        composeRule.onNodeWithTag("folder.quick.advanced").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.quick.UNREAD.PRESENT").assertDoesNotExist()
        assertEquals(any, root.value)
    }

    @Test fun pendingLegacyReplacementSurvivesRotationAndCanStillBeCancelled() {
        val restoration = StateRestorationTester(composeRule)
        var changes = 0
        restoration.setContent {
            WhiteNoiseTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SmartFolderRulePanel(
                            SmartFolderPanelState(false, null, true),
                            emptyList(),
                            { null },
                            onChange = { changes++ },
                            legacyControls = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("folder.quick.start").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("folder.confirmPreset").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(0, changes)
        composeRule.onNodeWithTag("folder.confirmPreset").assertDoesNotExist()
    }

    private fun renderPanel(
        initial: SmartFolderFilter.Group?,
        legacy: Boolean = false,
    ): MutableState<SmartFolderFilter.Group?> {
        val state = mutableStateOf(initial)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SmartFolderRulePanel(
                            SmartFolderPanelState(state.value != null, state.value, legacy),
                            emptyList(),
                            { null },
                            onChange = { state.value = it },
                            legacyControls = {},
                        )
                    }
                }
            }
        }
        return state
    }

    private fun render(
        initial: SmartFolderFilter.Group,
        fontScale: Float = 1f,
    ): MutableState<SmartFolderFilter.Group> {
        val state = mutableStateOf(initial)
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                Surface {
                    SmartFolderEditor(
                        state.value,
                        listOf(
                            WhiteNoisePickerItem(
                                "a".repeat(64),
                                "Agent",
                                "agent",
                            ),
                        ),
                        resolveKey = {
                            null
                        },
                        onChange = {
                            state.value =
                                it
                        },
                    )
                }
            }
        }
        return state
    }
}
