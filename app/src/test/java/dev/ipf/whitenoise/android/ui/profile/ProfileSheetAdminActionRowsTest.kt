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
import androidx.compose.ui.Modifier
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

        composeRule.onNodeWithText(grantLabel).performScrollTo().performClick()

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
            assertTrue(progressBounds.left >= labelBounds.right)
            assertTrue(progressBounds.right <= rowBounds.right)
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

    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
