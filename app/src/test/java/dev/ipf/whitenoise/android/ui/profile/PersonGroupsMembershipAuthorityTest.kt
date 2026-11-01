package dev.ipf.whitenoise.android.ui.profile

import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Mounted default administration UI, with authority failure/latency injected at the native binding only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class PersonGroupsMembershipAuthorityTest {
    /**
     * Queues effect continuations back onto the composition thread, as production's UI dispatcher does.
     * The deprecated v1 rule resumed each row on its native I/O worker, so rows that returned together
     * raced on the shared visit maps and one row's authority could be overwritten.
     */
    @get:Rule val rule = createComposeRule()

    /** A pending first row cannot hide a later row's already verified admin menu. */
    @Test fun successfulRowPublishesBeforeAnEarlierNativeReadFinishes() {
        val fixture = MembershipAuthorityFixture()
        val mounted = mutableStateOf(true)
        fixture.hold(fixture.slow.id)
        try {
            mount(fixture, mutableStateOf(listOf(fixture.slow, fixture.fast)), mounted)
            assertTrue(fixture.entered.getValue(fixture.slow.id).await(5, TimeUnit.SECONDS))
            awaitMenu(fixture, fixture.fast.id)
            rule.onNodeWithTag("groups_in_common.menu.${fixture.slow.id}").assertDoesNotExist()
            assertEquals(1L, fixture.releases.getValue(fixture.slow.id).count)
        } finally {
            fixture.close()
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
        }
    }

    /** Native reads that return together publish every row, and no visit state is written from a native worker. */
    @Test fun simultaneousNativeCompletionsPublishEveryRowFromTheCompositionThread() {
        val fixture = MembershipAuthorityFixture()
        val mounted = mutableStateOf(true)
        val ids = listOf(fixture.fast.id, fixture.slow.id)
        val workerWrites = CopyOnWriteArrayList<String>()
        ids.forEach(fixture::hold)
        // A global write on a native worker is a row effect resumed off the composition thread,
        // which production's UI dispatcher never allows.
        val observer =
            Snapshot.registerGlobalWriteObserver {
                if (Thread.currentThread() in fixture.nativeThreads) workerWrites += Thread.currentThread().name
            }
        try {
            mount(fixture, mutableStateOf(listOf(fixture.fast, fixture.slow)), mounted)
            ids.forEach { assertTrue(fixture.entered.getValue(it).await(5, TimeUnit.SECONDS)) }
            ids.forEach { fixture.releases.getValue(it).countDown() }
            awaitAuthority { workerWrites.isNotEmpty() || ids.all(::menuShown) }
            assertEquals(emptyList<String>(), workerWrites.toList())
            ids.forEach { awaitMenu(fixture, it) }
        } finally {
            observer.dispose()
            fixture.close()
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
        }
    }

    /** Unavailable authority preserves known groups, exposes Retry, and can recover without reopening the visit. */
    @Test fun failedAuthorityHasRetryAndDoesNotHideSuccessfulRows() {
        val fixture = MembershipAuthorityFixture()
        val mounted = mutableStateOf(true)
        fixture.failures += fixture.slow.id
        try {
            mount(fixture, mutableStateOf(listOf(fixture.slow, fixture.fast)), mounted)
            awaitMenu(fixture, fixture.fast.id)
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("groups_in_common.retry").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Slow group").assertIsDisplayed()
            rule.onNodeWithTag("groups_in_common.menu.${fixture.slow.id}").assertDoesNotExist()
            val fastReads = fixture.reads.count { it == fixture.fast.id }
            fixture.failures.clear()
            rule.onNodeWithTag("groups_in_common.retry").performClick()
            awaitMenu(fixture, fixture.slow.id)
            assertEquals(fastReads, fixture.reads.count { it == fixture.fast.id })
            rule.onNodeWithTag("groups_in_common.retry").assertDoesNotExist()
        } finally {
            fixture.close()
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
        }
    }

    /** A different group's changed projection cannot dismiss the current exact-group confirmation. */
    @Test fun siblingGroupRefreshPreservesConfirmationAndItsLoadedController() {
        val fixture = MembershipAuthorityFixture()
        val mounted = mutableStateOf(true)
        val groups = mutableStateOf(listOf(fixture.fast, fixture.slow))
        try {
            mount(fixture, groups, mounted)
            awaitMenu(fixture, fixture.fast.id)
            awaitMenu(fixture, fixture.slow.id)
            openConfirmation(fixture)
            val fastReads = fixture.reads.count { it == fixture.fast.id }
            rule.runOnIdle {
                val updatedSibling = fixture.slow.copy(group = fixture.slow.group.copy(name = "Updated"))
                groups.value = listOf(fixture.fast, updatedSibling)
            }
            rule.waitForIdle()
            rule.onNodeWithText("Remove River from Fast group").assertIsDisplayed()
            assertEquals(fastReads, fixture.reads.count { it == fixture.fast.id })
        } finally {
            fixture.close()
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
        }
    }

    /** An unavailable confirmation-time read reports missing evidence, never a fabricated roster change. */
    @Test fun confirmationRefreshFailureReportsUnavailableMembershipEvidence() {
        val fixture = MembershipAuthorityFixture()
        val mounted = mutableStateOf(true)
        var feedback: ToastMessage? = null
        try {
            mount(fixture, mutableStateOf(listOf(fixture.fast)), mounted, onFeedback = { feedback = it })
            awaitMenu(fixture, fixture.fast.id)
            openConfirmation(fixture)
            fixture.failures += fixture.fast.id
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            rule.onNodeWithText(context.getString(R.string.remove_from_group)).performClick()
            awaitAuthority { feedback != null }
            rule.runOnIdle {
                assertEquals(AppText.Resource(R.string.toast_couldnt_remove_member), feedback?.title)
                assertEquals(AppText.Resource(R.string.person_group_memberships_unavailable), feedback?.detail)
            }
        } finally {
            fixture.close()
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
        }
    }

    /** Retrying failed authority cannot dispose a different group's already-open confirmation. */
    @Test fun failedSiblingRetryPreservesTheConfirmedGroup() {
        val fixture = MembershipAuthorityFixture()
        val mounted = mutableStateOf(true)
        fixture.failures += fixture.slow.id
        try {
            mount(fixture, mutableStateOf(listOf(fixture.fast, fixture.slow)), mounted)
            awaitMenu(fixture, fixture.fast.id)
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("groups_in_common.retry").fetchSemanticsNodes().isNotEmpty()
            }
            val retry = rule.onNodeWithTag("groups_in_common.retry").captureClickCallbackForReplay()
            openConfirmation(fixture)
            val fastReads = fixture.reads.count { it == fixture.fast.id }
            fixture.failures.clear()
            rule.runOnIdle { retry() }
            awaitAuthority { fixture.reads.count { it == fixture.slow.id } >= 2 }
            rule.waitForIdle()
            rule.onNodeWithText("Remove River from Fast group").assertIsDisplayed()
            assertEquals(fastReads, fixture.reads.count { it == fixture.fast.id })
        } finally {
            fixture.close()
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
        }
    }

    /** Confirm reaches the real remove contract; an authoritative parent read removes only that shared row. */
    @Test fun confirmedNativeRemovalRefreshesTheSharedListAndPreservesOtherGroups() {
        val fixture = MembershipAuthorityFixture()
        val mounted = mutableStateOf(true)
        val groups = mutableStateOf(listOf(fixture.fast, fixture.slow))
        var refreshes = 0
        var feedback: ToastMessage? = null
        try {
            mount(fixture, groups, mounted, onFeedback = { feedback = it }, onMembershipChanged = {
                val fresh = checkNotNull(fixture.state.profileMembershipController(fixture.account, fixture.fast))
                try {
                    if (fresh.members.none { it.memberIdHex == fixture.person }) {
                        groups.value = groups.value.filterNot { it.id == fixture.fast.id }
                    }
                    refreshes++
                } finally {
                    fresh.onCleared()
                }
            })
            awaitMenu(fixture, fixture.fast.id)
            awaitMenu(fixture, fixture.slow.id)
            openConfirmation(fixture)
            assertTrue(fixture.commits.isEmpty())
            rule.onNodeWithTag("confirmation.confirm").performClick()
            awaitAuthority { refreshes == 1 && feedback != null }
            rule.waitForIdle()
            assertEquals(listOf(fixture.fast.id to listOf(fixture.person)), fixture.commits.toList())
            assertTrue(fixture.reads.count { it == fixture.fast.id } >= 3)
            rule.onNodeWithTag("groups_in_common.group.${fixture.fast.id}").assertDoesNotExist()
            rule.onNodeWithTag("groups_in_common.group.${fixture.slow.id}").assertIsDisplayed()
            rule.runOnIdle { assertEquals(AppText.Resource(R.string.toast_member_removed), feedback?.title) }
        } finally {
            fixture.close()
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
        }
    }

    /** Opens the real per-row overflow action and its production confirmation. */
    private fun openConfirmation(fixture: MembershipAuthorityFixture) {
        rule.onNodeWithTag("groups_in_common.menu.${fixture.fast.id}").performClick()
        rule.onNodeWithText("Remove River from Fast group").performClick()
        rule.onNodeWithText("Remove River from Fast group").assertIsDisplayed()
        rule.onNodeWithText(fixture.actorName, substring = true).assertIsDisplayed()
        rule.onNodeWithText(fixture.actorNpub, substring = true).assertIsDisplayed()
        rule.onNodeWithText(fixture.personNpub, substring = true).assertIsDisplayed()
        rule.onNodeWithText(fixture.fast.id).assertDoesNotExist()
    }

    /** Native IO resumes the process mutation scope on Android Main, outside the Compose clock. */
    private fun awaitAuthority(condition: () -> Boolean) {
        rule.waitUntil(5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            condition()
        }
    }

    /** Waits on actual row semantics rather than assuming the main clock drains native IO. */
    private fun awaitMenu(
        fixture: MembershipAuthorityFixture,
        id: String,
    ) {
        fixture.recordAuthorityEvent(id, "menu-wait-start")
        var lastMenuNodeCount: Int? = null
        var lastAuthorityStatus = "not completed"
        val menuTag = "groups_in_common.menu.$id"
        val rowTag = "groups_in_common.group.$id"
        val retryTag = "groups_in_common.retry"
        val observedTags = setOf(menuTag, rowTag, retryTag)
        try {
            awaitAuthority {
                val nodes =
                    rule
                        .onAllNodes(
                            SemanticsMatcher("membership authority status") {
                                it.config.getOrNull(SemanticsProperties.TestTag) in observedTags
                            },
                        ).fetchSemanticsNodes()
                val counts = nodes.groupingBy { it.config.getOrNull(SemanticsProperties.TestTag) }.eachCount()
                val menuCount = counts[menuTag] ?: 0
                lastMenuNodeCount = menuCount
                lastAuthorityStatus = "rowNodes=${counts[rowTag] ?: 0} retryNodes=${counts[retryTag] ?: 0}"
                menuCount > 0
            }
        } catch (failure: Throwable) {
            runCatching {
                fixture.recordAuthorityEvent(id, "menu-wait-failed:${failure.javaClass.simpleName}")
                val native =
                    runCatching { fixture.authorityDiagnostics().take(MAX_AUTHORITY_DIAGNOSTIC_CHARS) }
                        .getOrElse { "Native diagnostics unavailable: ${it.javaClass.simpleName}" }
                failure.addSuppressed(
                    AssertionError(
                        "Authority menu $id: $native\n" +
                            "Last completed poll menuNodes=$lastMenuNodeCount $lastAuthorityStatus",
                    ),
                )
            }
            throw failure
        }
    }

    /** True once the row's verified admin overflow action is in the semantics tree. */
    private fun menuShown(id: String): Boolean {
        val menus = rule.onAllNodesWithTag("groups_in_common.menu.$id")
        return menus.fetchSemanticsNodes().isNotEmpty()
    }

    /** Mounts the shipped entry point with the real active account, state owner and native controller reader. */
    private fun mount(
        fixture: MembershipAuthorityFixture,
        groups: MutableState<List<ChatListItem>>,
        mounted: MutableState<Boolean>,
        onFeedback: (ToastMessage?) -> Unit = {},
        onMembershipChanged: suspend () -> Unit = {},
    ) {
        rule.setContent {
            WhiteNoiseTheme {
                if (mounted.value) {
                    PersonGroupsMembershipContent(
                        appState = fixture.state,
                        account = fixture.account,
                        personHex = fixture.person,
                        personName = "River",
                        groups = groups.value,
                        ownerIsCurrent = { true },
                        onFeedback = onFeedback,
                        onMembershipChanged = onMembershipChanged,
                        rows = groups.value.map { PersonSharedGroupRow(it.id, it.group.name.orEmpty(), 3) },
                        unresolved = false,
                        onBack = {},
                        onOpen = {},
                        onAdd = {},
                        onRetry = {},
                        avatar = { null },
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_AUTHORITY_DIAGNOSTIC_CHARS = 4_000
    }
}
