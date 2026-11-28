package dev.ipf.whitenoise.android.state

import android.app.NotificationManager
import androidx.compose.material3.SnackbarResult
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.FailingDeleteNotificationManager
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.setPinnedConversationTestKey
import dev.ipf.whitenoise.android.share.buildShareShortcutIntent
import dev.ipf.whitenoise.android.ui.chats.PendingLocalChatDelete
import dev.ipf.whitenoise.android.ui.common.finishLocalDeleteSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

/** Exercises the actual controller, platform journal and privacy-safe notice boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class LocalGroupDeleteControllerTest {
    private val controllers = mutableListOf<ChatsController>()

    @After
    fun closeControllers() {
        controllers.forEach(ChatsController::onCleared)
    }

    @Test
    fun capturedNativeEligibilityIsRecheckedBeforeAnyLocalCleanupOrDelete() =
        runTest {
            val eligible = AtomicBoolean(true)
            val fixture = localGroupDeleteFixture(controllers, localDeleteEligible = eligible::get)
            val request =
                PendingLocalChatDelete.capture(
                    listOf(
                        chatListItemFromProjection(chatRow(GROUP)).copy(
                            actions = noChatRowActions().copy(canDeleteLocal = true),
                        ),
                    ),
                    fixture.controller,
                    fixture.state,
                    requireNativeEligibility = true,
                )
            eligible.set(false)
            assertFalse(
                fixture.controller.deleteGroupLocalFromChatList(
                    request.groupIds.single(),
                    requireNativeEligibility = request.requireNativeEligibility,
                ),
            )
            assertEquals(1, fixture.calls.count { it == "presentedChatListRow" })
            assertEquals(0, fixture.calls.count { it == "listMedia" })
            assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" })
            assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            assertTrue(fixture.controller.items.any { it.id == GROUP })
        }

    @Test
    fun capturedBatchRetryRechecksEligibilityAndCannotExpandTargets() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = localGroupDeleteFixture(controllers, localDeleteEligible = { false })
                val notice =
                    fixture.state.localDeleteBatchRetryNotice(
                        fixture.controller,
                        listOf(GROUP),
                        isCurrent = { true },
                        requireNativeEligibility = true,
                    )
                requireNotNull(notice.retry).invoke(setOf(GROUP, "unconfirmed-group"))
                fixture.state.mutationsScope.coroutineContext[Job]
                    ?.children
                    ?.toList()
                    ?.forEach { it.join() }
                assertEquals(1, fixture.calls.count { it == "presentedChatListRow" })
                assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" })
                assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun nativeEligibleDeletionUsesTheExistingLocalCommitAndCleanup() =
        runTest {
            val fixture = localGroupDeleteFixture(controllers)
            assertTrue(fixture.controller.deleteGroupLocalFromChatList(GROUP, requireNativeEligibility = true))
            assertEquals(1, fixture.calls.count { it == "presentedChatListRow" })
            assertEquals(1, fixture.calls.count { it == "deleteGroupLocal" })
        }

    @Test
    fun healthyDeletionReportsSuccessWithoutRecoveryOrDeferredCleanup() =
        runTest {
            val fixture = fixture()
            val observer =
                LocalChatDeleteObserver(
                    onFailure = { error("healthy deletion must not fail") },
                    onCleanupDeferred = { error("healthy cleanup must not be deferred") },
                )
            assertTrue(fixture.controller.deleteGroupLocalFromChatList(GROUP, observer = observer))
            flushChatRows()
            assertEquals(1, fixture.calls.count { it == "deleteGroupLocal" })
            assertEquals(0, fixture.calls.count { it == "catchUpAccounts" })
            assertTrue(fixture.controller.items.none { it.id == GROUP })
            assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            assertEquals(null, fixture.state.toast)
            assertEquals(AppText.Resource(R.string.toast_chat_deleted_local), fixture.state.transientNotice?.title)
        }

    @Test
    fun exhaustedMediaPreflightRestoresRowAndReportsItsPhaseWithoutDeleting() =
        runTest {
            val fixture = fixture("listMedia")
            var capturedFailure: Throwable? = null
            assertFalse(
                fixture.controller.deleteGroupLocalFromChatList(
                    GROUP,
                    observer = LocalChatDeleteObserver(onFailure = { capturedFailure = it }),
                ),
            )
            flushChatRows()
            assertEquals(3, fixture.calls.count { it == "listMedia" })
            assertEquals(1, fixture.calls.count { it == "catchUpAccounts" })
            assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" })
            assertTrue(fixture.controller.items.any { it.id == GROUP })
            assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            assertTrue(requireNotNull(fixture.state.toast?.diagnosticReport).contains("phase=media_preflight"))
            assertEquals(AppText.Resource(R.string.local_delete_retry_detail), fixture.state.toast?.detail)
            assertTrue(capturedFailure is LocalGroupDeleteFailure)
        }

    @Test
    fun exhaustedNativeDeleteRestoresRowAndReportsItsPhase() =
        runLocalGroupDeleteTest {
            val fixture = fixture("deleteGroupLocal")
            assertFalse(fixture.controller.deleteGroupLocalFromChatList(GROUP))
            // The restored row is published after the Main dispatcher's debounce.
            testScheduler.advanceTimeBy(20)
            testScheduler.runCurrent()
            assertEquals(3, fixture.calls.count { it == "deleteGroupLocal" })
            assertEquals(1, fixture.calls.count { it == "catchUpAccounts" })
            assertTrue(fixture.controller.items.any { it.id == GROUP })
            assertTrue(requireNotNull(fixture.state.toast?.diagnosticReport).contains("phase=native_delete"))
            // A still-present group retains its intent; reconciliation never repeats the native delete.
            fixture.state.reconcilePendingLocalGroupDeleteCleanups()
            assertNotNull(fixture.state.localGroupDeleteCleanupJournal.find(ACCOUNT, GROUP))
            assertEquals(3, fixture.calls.count { it == "deleteGroupLocal" })
        }

    @Test
    fun uncertainPresenceReadNeverStagesOrDeletesAndReportsUnknown() =
        runTest {
            val fixture = fixture("chatListRow")
            assertFalse(fixture.controller.deleteGroupLocalFromChatList(GROUP))
            assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" })
            assertEquals(0, fixture.calls.count { it == "listMedia" })
            assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            val report = requireNotNull(fixture.state.toast?.diagnosticReport)
            assertTrue(report.contains("phase=presence_reconciliation;attempt=3;exhausted=1;presence=unknown"))
        }

    @Test
    fun committedDeletionWithFailedClientCleanupKeepsRowAbsentAndRetriesQuietly() =
        runTest {
            val fixture = fixture("selectedMessageDraft")
            var cleanupDeferred = 0
            assertTrue(
                fixture.controller.deleteGroupLocalFromChatList(
                    GROUP,
                    observer = LocalChatDeleteObserver(onCleanupDeferred = { cleanupDeferred++ }),
                ),
            )
            assertEquals(1, cleanupDeferred)
            flushChatRows()
            assertEquals(1, fixture.calls.count { it == "deleteGroupLocal" })
            assertTrue(fixture.controller.items.none { it.id == GROUP })
            assertTrue(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            assertEquals(null, fixture.state.toast)
            assertEquals(null, fixture.state.transientNotice)
            assertFalse(fixture.calls.any { it.contains("leave", ignoreCase = true) })
        }

    @Test
    fun successfulRetryRetiresItsWarningAndKeepsTheSafePresenceCheck() =
        runLocalGroupDeleteTest {
            val fixture = fixture("deleteGroupLocal")
            assertFalse(fixture.controller.deleteGroupLocalFromChatList(GROUP))
            assertNotNull(
                fixture.state.toast
                    ?.localDeleteNotice
                    ?.retry,
            )
            fixture.failureMethod.set(null)
            val readsBefore = fixture.calls.count { it == "chatListRow" }
            assertTrue(fixture.controller.deleteGroupLocalFromChatList(GROUP))
            assertTrue(fixture.calls.count { it == "chatListRow" } > readsBefore)
            assertEquals(null, fixture.state.toast)
            assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
        }

    @Test
    fun backgroundRecoveryRetiresTheWarningWithoutAnotherNativeDelete() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture("selectedMessageDraft")
                assertTrue(fixture.controller.deleteGroupLocalFromChatList(GROUP))
                fixture.state.presentLocalDeleteFailure(
                    R.string.toast_couldnt_delete_chat,
                    MarmotKitException.TransportClosed(),
                    notice = LocalDeleteNotice(ACCOUNT, setOf(GROUP)),
                )
                fixture.failureMethod.set(null)
                fixture.state.reconcilePendingLocalGroupDeleteCleanups()
                assertEquals(null, fixture.state.toast)
                assertEquals(null, fixture.state.transientNotice)
                assertEquals(1, fixture.calls.count { it == "deleteGroupLocal" })
                assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun deferredCleanupRetainsAnUnrelatedWarningAndRecoversSilently() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture("selectedMessageDraft")
                fixture.state.present(R.string.toast_couldnt_delete_chat)
                val other = fixture.state.toast
                assertTrue(fixture.controller.deleteGroupLocalFromChatList(GROUP))
                assertEquals(other, fixture.state.toast)
                assertEquals(null, fixture.state.transientNotice)
                fixture.failureMethod.set(null)
                fixture.state.reconcilePendingLocalGroupDeleteCleanups()
                assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                assertEquals(other, fixture.state.toast)
                assertEquals(null, fixture.state.transientNotice)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun retainedAccountReactivationRejectsGestureBeforeOptimisticRowRemoval() =
        runTest {
            val fixture = fixture()
            fixture.state.setLocalDeleteTestReactivation(ACCOUNT)
            assertFalse(
                fixture.controller.deleteGroupLocalFromChatList(
                    GROUP,
                    observer = LocalChatDeleteObserver(onFailure = { error("stale gesture must not notify") }),
                ),
            )
            flushChatRows()
            assertTrue(fixture.controller.items.any { it.id == GROUP })
            val mutations = setOf("chatListRow", "listMedia", "deleteGroupLocal", "catchUpAccounts")
            assertEquals(0, fixture.calls.count { it in mutations })
            assertEquals(null, fixture.state.toast)
        }

    @Test
    fun runtimeReplacementDuringNativeReadCancelsWithoutAnotherMutationOrNotice() =
        runTest {
            lateinit var state: WhiteNoiseAppState
            val fixture = fixture(onRowRead = { state.advanceLocalDeleteTestRuntime() })
            state = fixture.state
            val failure = runCatching { fixture.controller.deleteGroupLocalFromChatList(GROUP) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" })
            assertEquals(null, fixture.state.toast)
        }

    @Test
    fun batchRetrySurvivesItsScreenAndCannotExpandTheConfirmedTargets() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                val request =
                    PendingLocalChatDelete.capture(
                        listOf(chatListItemFromProjection(chatRow(GROUP))),
                        fixture.controller,
                        fixture.state,
                    )
                // MainShell retains the controller and hides its projection when Chats leaves composition.
                fixture.controller.setChatListVisible(false)
                val notice =
                    fixture.state.localDeleteBatchRetryNotice(
                        fixture.controller,
                        request.groupIds,
                        isCurrent = { request.isCurrent(fixture.state, fixture.controller) },
                    )
                fixture.state.presentLocalDeleteFailure(R.string.toast_couldnt_delete_chat, null, notice = notice)
                requireNotNull(notice.retry).invoke(setOf(GROUP.uppercase(), "unconfirmed-group"))
                fixture.state.mutationsScope.coroutineContext[Job]
                    ?.children
                    ?.toList()
                    ?.forEach { it.join() }
                assertEquals(1, fixture.calls.count { it == "deleteGroupLocal" })
                assertEquals(null, fixture.state.toast)
                assertEquals(null, fixture.state.transientNotice)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aFailedOneChatRemainderReplacesTheClearedBatchWarning() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture("deleteGroupLocal")
                val captured =
                    PendingLocalChatDelete.capture(
                        listOf("already-deleted", GROUP).map { chatListItemFromProjection(chatRow(it)) },
                        fixture.controller,
                        fixture.state,
                    )
                val request = captured.remaining(1)
                val notice =
                    fixture.state.localDeleteBatchRetryNotice(
                        fixture.controller,
                        request.groupIds,
                        isCurrent = { request.isCurrent(fixture.state, fixture.controller) },
                    )
                fixture.state.presentLocalDeleteFailure(R.string.chat_list_delete_stopped, null, notice = notice)
                val oldWarning = requireNotNull(fixture.state.toast)
                finishLocalDeleteSnackbar(fixture.state, oldWarning, SnackbarResult.ActionPerformed)
                fixture.state.mutationsScope.coroutineContext[Job]
                    ?.children
                    ?.toList()
                    ?.forEach { it.join() }
                val failure = requireNotNull(fixture.state.toast)
                assertEquals(AppText.Resource(R.string.toast_couldnt_delete_chat), failure.title)
                assertEquals(setOf(GROUP), failure.localDeleteNotice?.groupIds)
                assertNotNull(failure.localDeleteNotice?.retry)
                assertTrue(requireNotNull(failure.diagnosticReport).contains("phase=native_delete"))
                assertEquals(3, fixture.calls.count { it == "deleteGroupLocal" })
                assertEquals(null, fixture.state.transientNotice)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun singleAndBatchRetryCallbacksRejectAReplacedRuntimeBeforeNativeReads() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                val runtime = fixture.state.runtimeGeneration
                val isCurrent = { fixture.state.runtimeGeneration == runtime }
                val single =
                    fixture.state.localDeleteRetryNotice(ACCOUNT, GROUP, isCurrent) {
                        fixture.controller.deleteGroupLocalFromChatList(GROUP)
                    }
                val batch = fixture.state.localDeleteBatchRetryNotice(fixture.controller, listOf(GROUP), isCurrent)
                fixture.state.advanceLocalDeleteTestRuntime()
                requireNotNull(single.retry).invoke(single.groupIds)
                requireNotNull(batch.retry).invoke(batch.groupIds)
                fixture.state.mutationsScope.coroutineContext[Job]
                    ?.children
                    ?.toList()
                    ?.forEach { it.join() }
                assertEquals(0, fixture.calls.count { it == "chatListRow" || it == "deleteGroupLocal" })
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun lastMemberForgetUsesTheSameDurableCleanupJournalAndRetryDoesNotForgetAgain() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture("selectedMessageDraft")
                var commits = 0
                var deferred = 0
                assertTrue(
                    fixture.controller.deleteGroupLocalFromChatList(
                        GROUP,
                        notify = false,
                        forgetProtocol = true,
                        observer =
                            LocalChatDeleteObserver(
                                onNativeCommitted = { commits++ },
                                onCleanupDeferred = { deferred++ },
                            ),
                    ),
                )
                assertEquals(1, commits)
                assertEquals(1, deferred)
                assertEquals(1, fixture.calls.count { it == "forgetGroupLocal" })
                assertEquals(0, fixture.calls.count { it == "deleteGroupLocal" || it == "leaveGroup" })
                assertTrue(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                fixture.failureMethod.set(null)
                assertTrue(
                    fixture.controller.deleteGroupLocalFromChatList(
                        GROUP,
                        notify = false,
                        forgetProtocol = true,
                    ),
                )
                assertEquals(1, fixture.calls.count { it == "forgetGroupLocal" })
                assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                flushChatRows()
                assertTrue(fixture.controller.items.none { it.id == GROUP })
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** Failing before channel redaction leaves durable local-delete cleanup pending until retry deletes the channel. */
    @Test
    @Config(sdk = [36], shadows = [FailingDeleteNotificationManager::class])
    fun channelRedactionFailureKeepsLocalDeleteCleanupPendingUntilRetrySucceeds() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            FailingDeleteNotificationManager.reset()
            try {
                val fixture = fixture()
                val context = fixture.state.appContext
                NotificationChannels.ensureChannels(context)
                val shortcutId = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
                val channelId =
                    checkNotNull(
                        ConversationNotificationChannels.ensureConversationChannel(
                            context,
                            NotificationChannelSpec.GROUP_MESSAGES.id,
                            shortcutId,
                            "Secret Deleted Group",
                            accountRef = ACCOUNT,
                        ),
                    )
                val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
                FailingDeleteNotificationManager.failingCreateChannelIds = setOf(channelId)
                var deferred = 0
                assertTrue(
                    fixture.controller.deleteGroupLocalFromChatList(
                        GROUP,
                        observer = LocalChatDeleteObserver(onCleanupDeferred = { deferred++ }),
                    ),
                )
                assertEquals(1, deferred)
                assertTrue(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                assertNotNull(manager.getNotificationChannel(channelId))
                FailingDeleteNotificationManager.reset()
                fixture.state.reconcilePendingLocalGroupDeleteCleanups()
                assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                assertNull(manager.getNotificationChannel(channelId))
            } finally {
                FailingDeleteNotificationManager.reset()
                Dispatchers.resetMain()
            }
        }

    /**
     * A live local delete stages its journal before native commit. Failed channel redaction keeps the journal
     * and a generic shortcut until replay removes the channel.
     */
    @Test
    @Config(sdk = [36], shadows = [FailingDeleteNotificationManager::class])
    fun liveLocalDeleteStagesBeforeNativeCommitAndRetriesChannelFailure() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            FailingDeleteNotificationManager.reset()
            try {
                lateinit var state: WhiteNoiseAppState
                val fixture =
                    localGroupDeleteFixture(
                        controllers,
                        beforeDelete = { assertNotNull(state.localGroupDeleteCleanupJournal.find(ACCOUNT, GROUP)) },
                    ).also { state = it.state }
                val context = fixture.state.appContext
                NotificationChannels.ensureChannels(context)
                val shortcutId = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
                val channelId =
                    checkNotNull(
                        ConversationNotificationChannels.ensureConversationChannel(
                            context,
                            NotificationChannelSpec.GROUP_MESSAGES.id,
                            shortcutId,
                            "Secret Left Group",
                            accountRef = ACCOUNT,
                        ),
                    )
                ShortcutManagerCompat.pushDynamicShortcut(
                    context,
                    ShortcutInfoCompat
                        .Builder(context, shortcutId)
                        .setShortLabel("Secret Left Group")
                        .setIntent(buildShareShortcutIntent(context))
                        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
                        .setLongLived(true)
                        .build(),
                )
                val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
                FailingDeleteNotificationManager.failingCreateChannelIds = setOf(channelId)

                fixture.state.deleteGroupLocalWithClientCleanup(ACCOUNT, GROUP)

                assertFalse(fixture.present.get())
                assertNotNull(fixture.state.localGroupDeleteCleanupJournal.find(ACCOUNT, GROUP))
                assertNotNull(manager.getNotificationChannel(channelId))
                val kept = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == shortcutId }
                assertEquals(context.getString(R.string.app_name), kept.shortLabel.toString())

                FailingDeleteNotificationManager.reset()
                fixture.state.reconcilePendingLocalGroupDeleteCleanups()
                assertFalse(fixture.state.localGroupDeleteCleanupJournal.hasPending())
                assertNull(manager.getNotificationChannel(channelId))
            } finally {
                FailingDeleteNotificationManager.reset()
                Dispatchers.resetMain()
            }
        }

    /** A queued vibration-pattern write cannot recreate a `:conv:` channel after local conversation deletion wins. */
    @Test
    fun staleVibrationPatternWriteAfterLocalDeleteDoesNotRecreateConversationChannel() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val fixture = fixture()
                val context = fixture.state.appContext
                NotificationChannels.ensureChannels(context)
                val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
                val beforeJobs =
                    fixture.state.mutationsScope.coroutineContext[Job]
                        ?.children
                        ?.toSet()
                        .orEmpty()
                fixture.state.conversationVibrationChannelMutex.lock()
                val vibrationJobs: List<Job>
                try {
                    fixture.state.setConversationVibrationPattern(
                        GROUP,
                        isDm = false,
                        conversationTitle = "Deleted Chat",
                        pattern = ConversationVibrationPattern.DOUBLE,
                    )
                    vibrationJobs =
                        fixture.state.mutationsScope.coroutineContext[Job]
                            ?.children
                            ?.filterNot { it in beforeJobs }
                            ?.toList()
                            .orEmpty()
                    assertTrue(fixture.controller.deleteGroupLocalFromChatList(GROUP))
                } finally {
                    fixture.state.conversationVibrationChannelMutex.unlock()
                }
                assertTrue(vibrationJobs.isNotEmpty())
                vibrationJobs.forEach { it.join() }
                assertEquals(
                    ConversationVibrationPattern.SYSTEM_DEFAULT,
                    fixture.state.conversationVibrationPreferences.pattern(ACCOUNT, GROUP),
                )
                assertTrue(manager.notificationChannels.none { it.id.contains(":conv:") })
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * If channel cleanup fails during `removePinnedConversationShortcuts`, launcher tokens for the
     * removed group are still durably revoked and visible shortcuts are relabeled while the call throws
     * so the delete journal retains its retry.
     */
    @Test
    @Config(sdk = [36], shadows = [FailingDeleteNotificationManager::class])
    fun removePinnedConversationShortcutsRevokesTokensWhenChannelCleanupFails() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            FailingDeleteNotificationManager.reset()
            setPinnedConversationTestKey(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
            try {
                val fixture = fixture()
                val context = fixture.state.appContext
                NotificationChannels.ensureChannels(context)
                val tokens = PinnedConversationTokens.create(context)
                val capability = requireNotNull(tokens.issue(ACCOUNT, GROUP))
                assertTrue(tokens.isValid(capability))
                val shortcutId = checkNotNull(conversationShortcutId(ACCOUNT, GROUP))
                val channelId =
                    checkNotNull(
                        ConversationNotificationChannels.ensureConversationChannel(
                            context,
                            NotificationChannelSpec.GROUP_MESSAGES.id,
                            shortcutId,
                            "Secret Journal Retry Chat",
                            accountRef = ACCOUNT,
                        ),
                    )
                ShortcutManagerCompat.pushDynamicShortcut(
                    context,
                    ShortcutInfoCompat
                        .Builder(context, shortcutId)
                        .setShortLabel("Secret Journal Retry Chat")
                        .setIntent(buildShareShortcutIntent(context))
                        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
                        .setLongLived(true)
                        .build(),
                )
                val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
                FailingDeleteNotificationManager.failingCreateChannelIds = setOf(channelId)

                val result = runCatching { fixture.state.removePinnedConversationShortcuts(ACCOUNT, GROUP) }

                assertTrue(result.isFailure)
                assertFalse(
                    "Launcher token must be revoked even when channel cleanup fails",
                    tokens.isValid(capability),
                )
                assertNotNull(manager.getNotificationChannel(channelId))
                val kept = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == shortcutId }
                assertEquals(context.getString(R.string.app_name), kept.shortLabel.toString())
            } finally {
                setPinnedConversationTestKey(null)
                FailingDeleteNotificationManager.reset()
                Dispatchers.resetMain()
            }
        }

    /** The shared fake-native fixture, cleared with this test's controllers. */
    private fun TestScope.fixture(
        failing: String? = null,
        onRowRead: () -> Unit = {},
    ): LocalGroupDeleteFixture = localGroupDeleteFixture(controllers, failing, onRowRead)

    /** Exercise the production debounced projection before checking visible-row mutations. */
    private fun flushChatRows() = flushLocalDeleteChatRows()

    private companion object {
        const val ACCOUNT = LOCAL_DELETE_ACCOUNT
        val GROUP = LOCAL_DELETE_GROUP
    }
}
