package dev.ipf.whitenoise.android.ui.devices

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.CompromiseWarningReasonFfi
import dev.ipf.marmotkit.SignInResponseFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.LinkedDeviceChatCounts
import dev.ipf.whitenoise.android.state.LinkedDeviceRow
import dev.ipf.whitenoise.android.state.LinkedDeviceSettingsPatch
import dev.ipf.whitenoise.android.state.SignInListState
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Managing devices and reviewing sign-ins sends exactly what the user chose and keeps distinct actions distinct. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h2400dp-mdpi")
class DeviceManagementContentTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * A device's page is headed by its name and the facts MDK reports, link history included, read as one item.
     * Holding the header explains them, as the row it replaced did.
     */
    @Test
    fun theDevicePageIsHeadedByItsFactsAndExplainsThem() {
        val device = fixtureDevice("tablet", label = "Tablet", linkedByLabel = "Phone")
        compose.setContent {
            WhiteNoiseTheme { ExplanationHost { LinkedDeviceDetailContent(device, false, {}) } }
        }

        val linkedBy = context.getString(R.string.linked_devices_linked_by, "Phone")
        compose
            .onNodeWithTag(DEVICE_DETAIL_HERO_TAG)
            .assertIsDisplayed()
            .assertTextContains("Tablet")
            .assertTextContains("White Noise · Android", substring = true)
            .assertTextContains(linkedBy, substring = true)
            .performTouchInput { longClick() }
        val explanation = context.getString(R.string.explanation_linked_device_row)
        compose.onNodeWithTag("explanation.body").assertTextEquals(explanation)
    }

    /** A name of the longest length a device can have is given in full in the header, whatever the title bar fits. */
    @Test
    fun aLongNameIsGivenInFullInTheHeader() {
        val name = "Workshop bench computer by the north window ".padEnd(64, 'x')
        detail(fixtureDevice("tablet", label = name))

        compose.onNodeWithTag(DEVICE_DETAIL_HERO_TAG).assertIsDisplayed().assertTextContains(name)
    }

    /** Each switch sends a patch that names only its own setting. */
    @Test
    fun eachSwitchPatchesOnlyItsOwnSetting() {
        val patches = mutableListOf<LinkedDeviceSettingsPatch>()
        detail(fixtureDevice("tablet", label = "Tablet"), LinkedDeviceDetailActions(onSettings = { patches += it }))

        compose.onNodeWithTag(DEVICE_DETAIL_ADD_TO_CHATS_TAG).performClick()
        compose.onNodeWithTag(DEVICE_DETAIL_BRING_HISTORY_TAG).performClick()
        compose.onNodeWithTag(DEVICE_DETAIL_KEEP_TAG).performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    LinkedDeviceSettingsPatch(addToAllChats = false),
                    LinkedDeviceSettingsPatch(bringHistory = true),
                    LinkedDeviceSettingsPatch(keepWhileInactive = true),
                ),
                patches,
            )
        }
    }

    /** Renaming saves the trimmed name; an empty name is allowed because it clears the name. */
    @Test
    fun renamingSavesTheTrimmedName() {
        val names = mutableListOf<String>()
        detail(fixtureDevice("tablet", label = "Tablet"), LinkedDeviceDetailActions(onRename = { names += it }))

        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_TAG).performClick()
        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_FIELD_TAG).performTextReplacement("  Desk  ")
        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_SAVE_TAG).performClick()
        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_TAG).performClick()
        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_FIELD_TAG).performTextReplacement("")
        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_SAVE_TAG).assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("Desk", ""), names) }
    }

    /** A name over MDK's byte limit says so and cannot be saved. */
    @Test
    fun anOverlongNameCannotBeSaved() {
        var renames = 0
        detail(fixtureDevice("tablet"), LinkedDeviceDetailActions(onRename = { renames++ }))

        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_TAG).performClick()
        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_FIELD_TAG).performTextReplacement("é".repeat(33))

        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.link_request_label_too_long)).assertExists()
        compose.runOnIdle { assertEquals(0, renames) }
    }

    /** Removal needs a second, explicit confirmation; cancelling it removes nothing. */
    @Test
    fun removalNeedsConfirmation() {
        var removals = 0
        detail(fixtureDevice("tablet", label = "Tablet"), LinkedDeviceDetailActions(onRemove = { removals++ }))

        compose.onNodeWithTag(DEVICE_DETAIL_REMOVE_TAG).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithTag(DEVICE_DETAIL_REMOVE_TAG).performClick()
        compose.onNodeWithTag(DEVICE_DETAIL_REMOVE_CONFIRM_TAG).performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    /** This device cannot be removed from its own page; signing out is how it leaves. */
    @Test
    fun thisDeviceHasNoRemoveAction() {
        detail(fixtureDevice("self", label = "Pixel", isThisDevice = true))

        compose.onNodeWithTag(DEVICE_DETAIL_REMOVE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_TAG).assertIsEnabled()
    }

    /** A device whose removal is pending says so and cannot be edited or removed again. */
    @Test
    fun aDeviceBeingRemovedCannotBeEdited() {
        detail(fixtureDevice("old", label = "Old phone", removalPending = true))

        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DEVICE_DETAIL_KEEP_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DEVICE_DETAIL_REMOVE_TAG).assertDoesNotExist()
    }

    /** While an edit runs every control is disabled, so a second tap sends nothing. */
    @Test
    fun controlsAreDisabledWhileAnEditRuns() {
        detail(fixtureDevice("tablet"), busy = true)

        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DEVICE_DETAIL_ADD_TO_CHATS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DEVICE_DETAIL_REMOVE_TAG).performScrollTo().assertIsNotEnabled()
    }

    /** Tapping a roster row asks to open that device. */
    @Test
    fun aRosterRowOpensItsDevice() {
        val opened = mutableListOf<String>()
        roster(actions = LinkedDevicesActions(onOpenDevice = { opened += it }))

        compose.onNodeWithTag("linked_devices.device.tablet").performClick()

        compose.runOnIdle { assertEquals(listOf("tablet"), opened) }
    }

    /** A chat count is shown only when MDK reported one above zero; unknown and zero show nothing. */
    @Test
    fun chatCountsShowOnlyAboveZero() {
        val counts = LinkedDeviceChatCounts(closedToLinkedDevices = 3, holdingRemovedDevice = 0)
        roster(LinkedDevicesHealth(counts = counts))

        compose.onNodeWithText(context.getString(R.string.devices_closed_chats, 3)).assertExists()
        compose.onNodeWithTag(LINKED_DEVICES_LEFTOVER_CHATS_TAG).assertDoesNotExist()
    }

    /** With no counts read, no count row is shown. */
    @Test
    fun unknownChatCountsShowNothing() {
        roster(LinkedDevicesHealth())

        compose.onNodeWithTag(LINKED_DEVICES_CLOSED_CHATS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(LINKED_DEVICES_LEFTOVER_CHATS_TAG).assertDoesNotExist()
    }

    /** A stalled device is offered the repair, and an overdue refresh is a separate notice with no action. */
    @Test
    fun aStalledDeviceAndAnOverdueRefreshAreSeparateNotices() {
        var repairs = 0
        roster(
            LinkedDevicesHealth(stalled = true, keyPackageRefreshOverdue = true),
            LinkedDevicesActions(onRepair = { repairs++ }),
        )

        compose.onNodeWithTag(LINKED_DEVICES_STALLED_TAG).assertExists()
        compose.onNodeWithTag(LINKED_DEVICES_OVERDUE_TAG).assertExists()
        compose.onNodeWithTag(LINKED_DEVICES_REPAIR_TAG).performClick()
        compose.runOnIdle { assertEquals(1, repairs) }
    }

    /** A lost repair reply keeps its explicit repair action reachable even after MDK reports a healthy group. */
    @Test
    fun anUncertainDiscardKeepsRepairReachable() {
        var repairs = 0
        roster(LinkedDevicesHealth(repairNeedsConfirmation = true), LinkedDevicesActions(onRepair = { repairs++ }))
        compose.onNodeWithTag(LINKED_DEVICES_REPAIR_TAG).performClick()
        compose.runOnIdle { assertEquals(1, repairs) }
    }

    /** A healthy device shows neither notice. */
    @Test
    fun aHealthyDeviceShowsNoNotice() {
        roster(LinkedDevicesHealth())

        compose.onNodeWithTag(LINKED_DEVICES_STALLED_TAG).assertDoesNotExist()
        compose.onNodeWithTag(LINKED_DEVICES_OVERDUE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMPROMISE_WARNING_TAG).assertDoesNotExist()
    }

    /** The repair explains itself and happens only on its own confirmation. */
    @Test
    fun repairHappensOnlyAfterItsExplanation() {
        var repairs = 0
        var dismissals = 0
        val open = mutableStateOf(true)
        compose.setContent {
            WhiteNoiseTheme {
                if (open.value) RepairDeviceLinkDialog(onRepair = { repairs++ }, onDismiss = { dismissals++ })
            }
        }

        compose.onNodeWithText(context.getString(R.string.devices_repair_confirm)).assertExists()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0 to 1, repairs to dismissals) }
        // Cancel closes the dialog, so the question is asked again before confirming.
        compose.runOnIdle { open.value = false }
        compose.waitForIdle()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithTag(LINKED_DEVICES_REPAIR_CONFIRM_TAG).performClick()
        compose.runOnIdle { assertEquals(1, repairs) }
    }

    /** The warning is shown with the action that marks it seen, described as hiding it on this device only. */
    @Test
    fun theWarningCanOnlyBeMarkedSeen() {
        var dismissals = 0
        val warning = CompromiseWarningItemFfi(CompromiseWarningReasonFfi.REJECTED_SIGN_IN, "slot", null, 1u)
        roster(
            LinkedDevicesHealth(review = SignInReviewState(warning = listOf(warning))),
            LinkedDevicesActions(review = SignInReviewActions(onDismissWarning = { dismissals++ })),
        )

        compose.onNodeWithTag(COMPROMISE_WARNING_TAG).assertExists()
        compose.onNodeWithText(context.getString(R.string.compromise_warning_dismiss_detail)).assertExists()
        compose.onNodeWithTag(COMPROMISE_WARNING_DISMISS_TAG).performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    /** Each warning reason has its own sentence. */
    @Test
    fun eachWarningReasonHasItsOwnSentence() {
        val texts = CompromiseWarningReasonFfi.entries.map(::compromiseWarningText)

        assertEquals(CompromiseWarningReasonFfi.entries.size, texts.toSet().size)
    }

    /** A failed check is announced above the sign-ins already listed; it never empties the list. */
    @Test
    fun aFailedCheckKeepsTheListedSignIns() {
        val list = SignInListState(signIns = listOf(signIn("a")), loaded = true, checkFailed = true)
        roster(LinkedDevicesHealth(review = SignInReviewState(list = list)))

        compose.onNodeWithTag(SIGN_INS_CHECK_FAILED_TAG).performScrollTo().assertExists()
        compose.onNodeWithTag("sign_ins.row.a").assertExists()
    }

    /** Check and opening a sign-in are separate actions. */
    @Test
    fun checkingAndOpeningASignInAreSeparate() {
        var checks = 0
        val opened = mutableListOf<String>()
        val list = SignInListState(signIns = listOf(signIn("a")), loaded = true)
        roster(
            LinkedDevicesHealth(review = SignInReviewState(list = list)),
            LinkedDevicesActions(
                review = SignInReviewActions(onCheck = { checks++ }, onOpen = { opened += it.slotIdHex }),
            ),
        )

        compose.onNodeWithTag(SIGN_INS_CHECK_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("sign_ins.row.a").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(1 to listOf("a"), checks to opened) }
    }

    /** A fresh start is offered only for unanswered sign-ins from before this device, and not after one was taken. */
    @Test
    fun aFreshStartIsOfferedOnlyWhenItApplies() {
        val before = signIn("a", beforeThisDevice = true)

        fun state(
            signIns: List<UnrecognizedSignInFfi>,
            startedFresh: Boolean = false,
        ) = SignInReviewState(SignInListState(signIns, loaded = true), startedFresh = startedFresh)

        assertTrue(offersStartFresh(state(listOf(before))))
        assertFalse(offersStartFresh(state(listOf(signIn("b")))))
        assertFalse(offersStartFresh(state(listOf(before.copy(state = UnrecognizedSignInStateFfi.NOT_NOW)))))
        assertFalse(offersStartFresh(state(listOf(before), startedFresh = true)))
        assertFalse(offersStartFresh(state(emptyList())))
    }

    /** A new sign-in offers Not now and This wasn't me, and each sends its own answer. */
    @Test
    fun aNewSignInOffersNotNowAndReject() {
        assertEquals(SignInAnswerChoice.NotNowOrReject, signInAnswerChoice(signIn("a")))
        val answers = review(signIn("a"))

        compose.onNodeWithText(context.getString(R.string.sign_in_question_new)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sign_in_reject_confirm)).assertExists()
        compose.onNodeWithTag(SIGN_IN_NOT_NOW_TAG).performClick()
        compose.onNodeWithTag(SIGN_IN_REJECT_TAG).performClick()
        compose.onNodeWithTag(SIGN_IN_DISMISS_TAG).assertDoesNotExist()

        compose.runOnIdle { assertEquals(listOf(SignInResponseFfi.NOT_NOW, SignInResponseFfi.REJECT), answers) }
    }

    /** A sign-in from before this device asks whether the user still has it and never offers This wasn't me. */
    @Test
    fun aSignInFromBeforeThisDeviceCannotBeRejectedHere() {
        val before = signIn("a", beforeThisDevice = true)
        assertEquals(SignInAnswerChoice.NotNowOnly, signInAnswerChoice(before))
        val answers = review(before)

        compose.onNodeWithText(context.getString(R.string.sign_in_question_before)).assertExists()
        compose.onNodeWithTag(SIGN_IN_REJECT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SIGN_IN_NOT_NOW_TAG).performClick()

        compose.runOnIdle { assertEquals(listOf(SignInResponseFfi.NOT_NOW), answers) }
    }

    /** A sign-in another device answered can only be dismissed, which is not a rejection. */
    @Test
    fun aSignInAnsweredElsewhereCanOnlyBeDismissed() {
        listOf(UnrecognizedSignInStateFfi.LINKED_ELSEWHERE, UnrecognizedSignInStateFfi.REJECTED_ELSEWHERE).forEach {
            assertEquals(SignInAnswerChoice.DismissOnly, signInAnswerChoice(signIn("a").copy(state = it)))
        }
        val answers = review(signIn("a").copy(state = UnrecognizedSignInStateFfi.REJECTED_ELSEWHERE))

        compose.onNodeWithTag(SIGN_IN_REJECT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SIGN_IN_NOT_NOW_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SIGN_IN_DISMISS_TAG).performClick()

        compose.runOnIdle { assertEquals(listOf(SignInResponseFfi.DISMISS), answers) }
    }

    /** Closing the review sends no answer. */
    @Test
    fun closingTheReviewAnswersNothing() {
        val answers = mutableListOf<SignInResponseFfi>()
        var closes = 0
        val before = signIn("a", beforeThisDevice = true)
        compose.setContent {
            WhiteNoiseTheme { SignInReviewDialog(before, onAnswer = { answers += it }, onClose = { closes++ }) }
        }

        compose.onNodeWithText(context.getString(R.string.close)).performClick()

        compose.runOnIdle { assertEquals(emptyList<SignInResponseFfi>() to 1, answers to closes) }
    }

    /** The missing-link note appears only when MDK reports that context. */
    @Test
    fun thePreviouslyLinkedNoteFollowsMdk() {
        review(signIn("a").copy(previouslyLinkedHere = true))

        compose.onNodeWithText(context.getString(R.string.sign_in_previously_linked)).assertExists()
    }

    /** Renders one device's page. */
    private fun detail(
        device: LinkedDeviceRow,
        actions: LinkedDeviceDetailActions = LinkedDeviceDetailActions(),
        busy: Boolean = false,
    ) {
        compose.setContent { WhiteNoiseTheme { LinkedDeviceDetailContent(device, busy, {}, actions) } }
    }

    /** Renders the roster with [health] and [actions]. */
    private fun roster(
        health: LinkedDevicesHealth = LinkedDevicesHealth(),
        actions: LinkedDevicesActions = LinkedDevicesActions(),
    ) {
        compose.setContent {
            val state = fixtureRosterState()
            WhiteNoiseTheme { LinkedDevicesContent(state, onBack = {}, actions = actions, health = health) }
        }
    }

    /** Opens the review of [signIn] and returns the answers it sends. */
    private fun review(signIn: UnrecognizedSignInFfi): List<SignInResponseFfi> {
        val answers = mutableListOf<SignInResponseFfi>()
        compose.setContent { WhiteNoiseTheme { SignInReviewDialog(signIn, { answers += it }, onClose = {}) } }
        return answers
    }
}
