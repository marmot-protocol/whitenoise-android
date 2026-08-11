package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileSheetAdminActionRowsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val progressMatcher = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    @Test
    fun tappedGrantRowOwnsTheOnlyVisibleProgressAtLargeFontScaleInLongContent() {
        setActionRows(
            actions = listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember),
            fontScale = 2f,
            longContent = true,
        )
        val grantLabel = string(R.string.make_admin)
        val grantTag = adminActionRowTag(GroupMemberMenuAction.GrantAdmin)

        composeRule.onNodeWithText(grantLabel).performScrollTo()
        val idleBounds =
            composeRule.runOnIdle {
                val labelBounds =
                    composeRule.onNodeWithText(grantLabel, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val rowBounds =
                    composeRule.onNode(hasTestTag(grantTag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                labelBounds to rowBounds
            }

        composeRule.onNodeWithText(grantLabel).performClick()

        val progress =
            composeRule.onNode(
                progressMatcher and hasAnyAncestor(hasTestTag(grantTag)),
                useUnmergedTree = true,
            )
        progress.assertIsDisplayed()
        composeRule.onAllNodes(progressMatcher, useUnmergedTree = true).assertCountEquals(1)
        composeRule.runOnIdle {
            val rowBounds =
                composeRule.onNode(hasTestTag(grantTag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val labelBounds =
                composeRule.onNodeWithText(grantLabel, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val progressBounds = progress.fetchSemanticsNode().boundsInRoot
            assertTrue(progressBounds.right <= labelBounds.left)
            assertTrue(progressBounds.left >= rowBounds.left)
            assertEquals(idleBounds.first, labelBounds)
            assertEquals(idleBounds.second, rowBounds)
        }
    }

    @Test
    fun revokeProgressBelongsOnlyToRevokeRow() {
        val pending =
            setActionRows(
                actions = listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember),
                initialPendingAction = GroupMemberMenuAction.RevokeAdmin,
            )

        assertProgressOwnedBy(GroupMemberMenuAction.RevokeAdmin)
        composeRule.onAllNodes(progressMatcher, useUnmergedTree = true).assertCountEquals(1)

        pending.value = null
        composeRule.waitForIdle()
        composeRule.onAllNodes(progressMatcher, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun confirmedRemoveProgressBelongsOnlyToRemoveRow() {
        setActionRows(
            actions = listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember),
            initialPendingAction = GroupMemberMenuAction.RemoveMember,
        )

        assertProgressOwnedBy(GroupMemberMenuAction.RemoveMember)
        composeRule.onAllNodes(progressMatcher, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun externalMutationDisablesRowsWithoutClaimingLocalProgress() {
        var grantClicks = 0
        var removeClicks = 0
        setActionRows(
            actions = listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember),
            externallyBusy = true,
            onGrantAdmin = { grantClicks++ },
            onRemoveMember = { removeClicks++ },
        )

        composeRule.onAllNodes(progressMatcher, useUnmergedTree = true).assertCountEquals(0)
        assertFalse(
            composeRule
                .onNodeWithText(string(R.string.make_admin))
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.OnClick),
        )
        assertFalse(
            composeRule
                .onNodeWithText(string(R.string.remove_member))
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.OnClick),
        )
        assertEquals(0, grantClicks)
        assertEquals(0, removeClicks)
    }

    @Test
    fun moderationBlockKeepsBottomAnchoredBoundsStableAcrossRoleChangesAtAllFontScales() {
        val actions =
            mutableStateOf(
                listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember),
            )
        val pendingAction = mutableStateOf<GroupMemberMenuAction?>(null)
        val fontScale = mutableStateOf(1f)
        composeRule.setContent {
            WhiteNoiseTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                    Box(Modifier.width(320.dp).height(360.dp)) {
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            ProfileSheetAdminActionRows(
                                actions = actions.value,
                                pendingAction = pendingAction.value,
                                busy = pendingAction.value != null,
                                onGrantAdmin = {},
                                onRevokeAdmin = {},
                                onRemoveMember = {},
                            )
                        }
                    }
                }
            }
        }

        assertStableRoleTransitions(
            baseline = moderationBlockBounds(),
            actions = actions,
            pendingAction = pendingAction,
        )

        composeRule.runOnIdle {
            actions.value = listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember)
            pendingAction.value = null
            fontScale.value = 2f
        }
        composeRule.waitForIdle()

        assertStableRoleTransitions(
            baseline = moderationBlockBounds(),
            actions = actions,
            pendingAction = pendingAction,
        )
    }

    @Test
    fun failedMutationClearsPendingActionAndAllowsRetry() =
        runTest {
            var pendingAction: GroupMemberMenuAction? = null
            var launchedBlock: (suspend () -> Unit)? = null
            var launches = 0

            val firstStarted =
                runProfileSheetAdminMutation(
                    action = GroupMemberMenuAction.GrantAdmin,
                    isBusy = { pendingAction != null },
                    onPendingActionChange = { pendingAction = it },
                    clearLastMutationError = {},
                    launchMutation = {
                        launches++
                        launchedBlock = it
                    },
                    mutation = { error("grant failed") },
                )
            val duplicateStarted =
                runProfileSheetAdminMutation(
                    action = GroupMemberMenuAction.GrantAdmin,
                    isBusy = { pendingAction != null },
                    onPendingActionChange = { pendingAction = it },
                    clearLastMutationError = {},
                    launchMutation = { launches++ },
                    mutation = {},
                )

            assertTrue(firstStarted)
            assertFalse(duplicateStarted)
            assertEquals(GroupMemberMenuAction.GrantAdmin, pendingAction)
            assertEquals(1, launches)
            val failure = runCatching { requireNotNull(launchedBlock).invoke() }
            assertTrue(failure.exceptionOrNull() is IllegalStateException)
            assertNull(pendingAction)

            val retryStarted =
                runProfileSheetAdminMutation(
                    action = GroupMemberMenuAction.GrantAdmin,
                    isBusy = { pendingAction != null },
                    onPendingActionChange = { pendingAction = it },
                    clearLastMutationError = {},
                    launchMutation = { launches++ },
                    mutation = {},
                )
            assertTrue(retryStarted)
            assertEquals(2, launches)
        }

    private fun setActionRows(
        actions: List<GroupMemberMenuAction>,
        initialPendingAction: GroupMemberMenuAction? = null,
        externallyBusy: Boolean = false,
        fontScale: Float = 1f,
        longContent: Boolean = false,
        onGrantAdmin: () -> Unit = {},
        onRevokeAdmin: () -> Unit = {},
        onRemoveMember: () -> Unit = {},
    ): MutableState<GroupMemberMenuAction?> {
        val pendingAction = mutableStateOf(initialPendingAction)
        composeRule.setContent {
            WhiteNoiseTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(
                        Modifier
                            .width(320.dp)
                            .height(if (longContent) 220.dp else 240.dp)
                            .testTag("profile-actions-viewport"),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (longContent) Spacer(Modifier.height(600.dp))
                            ProfileSheetAdminActionRows(
                                actions = actions,
                                pendingAction = pendingAction.value,
                                busy = pendingAction.value != null || externallyBusy,
                                onGrantAdmin = {
                                    pendingAction.value = GroupMemberMenuAction.GrantAdmin
                                    onGrantAdmin()
                                },
                                onRevokeAdmin = {
                                    pendingAction.value = GroupMemberMenuAction.RevokeAdmin
                                    onRevokeAdmin()
                                },
                                onRemoveMember = onRemoveMember,
                            )
                        }
                    }
                }
            }
        }
        return pendingAction
    }

    private fun assertProgressOwnedBy(action: GroupMemberMenuAction) {
        composeRule
            .onNode(
                progressMatcher and hasAnyAncestor(hasTestTag(adminActionRowTag(action))),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    private fun assertStableRoleTransitions(
        baseline: Rect,
        actions: MutableState<List<GroupMemberMenuAction>>,
        pendingAction: MutableState<GroupMemberMenuAction?>,
    ) {
        composeRule.runOnIdle {
            pendingAction.value = GroupMemberMenuAction.RevokeAdmin
        }
        composeRule.waitForIdle()
        assertModerationBoundsEqual(baseline, moderationBlockBounds())

        composeRule.runOnIdle {
            actions.value = listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember)
            pendingAction.value = null
        }
        composeRule.waitForIdle()
        assertModerationBoundsEqual(baseline, moderationBlockBounds())

        composeRule.runOnIdle {
            pendingAction.value = GroupMemberMenuAction.GrantAdmin
        }
        composeRule.waitForIdle()
        assertModerationBoundsEqual(baseline, moderationBlockBounds())
    }

    private fun moderationBlockBounds(): Rect =
        composeRule
            .onNodeWithTag(PROFILE_SHEET_ADMIN_ACTIONS_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun assertModerationBoundsEqual(
        expected: Rect,
        actual: Rect,
    ) {
        assertEquals(expected.top, actual.top, 0.5f)
        assertEquals(expected.height, actual.height, 0.5f)
    }

    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
