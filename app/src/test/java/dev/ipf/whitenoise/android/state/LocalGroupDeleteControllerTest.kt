package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

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
        runTest {
            val fixture = fixture("deleteGroupLocal")
            assertFalse(fixture.controller.deleteGroupLocalFromChatList(GROUP))
            flushChatRows()
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
        runTest {
            val fixture = fixture("deleteGroupLocal")
            assertFalse(fixture.controller.deleteGroupLocalFromChatList(GROUP))
            assertNotNull(fixture.state.toast?.localDeleteNotice?.retry)
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
                val notice = fixture.state.localDeleteBatchRetryNotice(fixture.controller, listOf(GROUP)) { true }
                fixture.state.presentLocalDeleteFailure(R.string.toast_couldnt_delete_chat, null, notice = notice)
                requireNotNull(notice.retry).invoke(setOf(GROUP.uppercase(), "unconfirmed-group"))
                fixture.state.mutationsScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
                assertEquals(1, fixture.calls.count { it == "deleteGroupLocal" })
                assertEquals(null, fixture.state.toast)
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
                val single = fixture.state.localDeleteRetryNotice(ACCOUNT, GROUP, isCurrent) {
                    fixture.controller.deleteGroupLocalFromChatList(GROUP)
                }
                val batch = fixture.state.localDeleteBatchRetryNotice(fixture.controller, listOf(GROUP), isCurrent)
                fixture.state.advanceLocalDeleteTestRuntime()
                requireNotNull(single.retry).invoke(single.groupIds)
                requireNotNull(batch.retry).invoke(batch.groupIds)
                fixture.state.mutationsScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
                assertEquals(0, fixture.calls.count { it == "chatListRow" || it == "deleteGroupLocal" })
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun TestScope.fixture(
        failing: String? = null,
        onRowRead: () -> Unit = {},
    ): Fixture {
        val calls = ConcurrentLinkedQueue<String>()
        val present = AtomicBoolean(true)
        val failureMethod = AtomicReference(failing)
        val native = native(calls, present, failureMethod, onRowRead)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { ACCOUNT_ID },
                accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_ID, true, false, false, true)),
                activeAccountRef = ACCOUNT,
                initialMarmotRuntime = AppMarmotRuntime("local-delete-test", native),
                // Share the virtual readiness deadline's clock instead of racing real IO dispatch.
                marmotIoDispatcher = StandardTestDispatcher(testScheduler),
            )
        val controller = ChatsController(state, ACCOUNT) { _, _ -> emptyList() }
        controllers += controller
        controller.setChatListVisible(false)
        controller.applyChatListRow(chatRow(GROUP))
        controller.setChatListVisible(true)
        assertEquals("fixture must begin with the real visible row", listOf(GROUP), controller.items.map { it.id })
        return Fixture(state, controller, calls, failureMethod)
    }

    /** Exercise the production debounced projection before checking visible-row mutations. */
    private fun flushChatRows() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    }

    private fun native(
        calls: ConcurrentLinkedQueue<String>,
        present: AtomicBoolean,
        failing: AtomicReference<String?>,
        onRowRead: () -> Unit,
    ): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            val name = method.name.substringBefore('-')
            calls += name
            if (name == failing.get()) {
                val failure = MarmotKitException.TransportClosed()
                val continuation = arguments?.lastOrNull() as? Continuation<*>
                if (continuation != null) {
                    continuation.resumeWith(Result.failure(failure))
                    return@newProxyInstance COROUTINE_SUSPENDED
                }
                throw failure
            }
            when (name) {
                "chatListRow" -> {
                    onRowRead()
                    if (present.get()) chatRow(GROUP) else null
                }
                "listMedia" -> emptyList<Any>()
                "catchUpAccounts" -> Unit
                "deleteGroupLocal" -> {
                    present.set(false)
                    true
                }
                "selectedMessageDraft", "clearMessageDraftIfRevision" ->
                    SelectedMessageDraftFfi(draftRevisionStub(), null)
                "toString" -> "LocalDeleteTestNative"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected local-delete native call: $name")
            }
        } as MarmotInterface

    /** The revision is passed opaquely to the fake; never invoke or load its native handle. */
    private fun draftRevisionStub(): MessageDraftRevisionFfi {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, MessageDraftRevisionFfi::class.java) as MessageDraftRevisionFfi
    }

    private data class Fixture(
        val state: WhiteNoiseAppState,
        val controller: ChatsController,
        val calls: ConcurrentLinkedQueue<String>,
        val failureMethod: AtomicReference<String?>,
    )

    private companion object {
        const val ACCOUNT = "local-delete-account"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP = "b1".repeat(16)
    }
}
