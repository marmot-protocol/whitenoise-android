package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

internal const val LOCAL_DELETE_ACCOUNT = "local-delete-account"
internal val LOCAL_DELETE_ACCOUNT_ID = "a1".repeat(32)
internal val LOCAL_DELETE_GROUP = "b1".repeat(16)

/** The real app state and chat list over a fake native runtime that records every call by name. */
internal data class LocalGroupDeleteFixture(
    val state: WhiteNoiseAppState,
    val controller: ChatsController,
    val calls: ConcurrentLinkedQueue<String>,
    val failureMethod: AtomicReference<String?>,
)

/**
 * Builds the fixture with one visible row for [LOCAL_DELETE_GROUP]. The native method named [failing] fails with a
 * closed transport until the test clears [LocalGroupDeleteFixture.failureMethod], and [onRowRead] runs on each
 * native row read. The controller joins [controllers] so the test can clear it afterwards.
 */
internal fun TestScope.localGroupDeleteFixture(
    controllers: MutableList<ChatsController>,
    failing: String? = null,
    onRowRead: () -> Unit = {},
    beforeDelete: suspend () -> Unit = {},
    localDeleteEligible: () -> Boolean = { true },
    configureState: (WhiteNoiseAppState, AtomicBoolean) -> Unit = { _, _ -> },
): LocalGroupDeleteFixture {
    val calls = ConcurrentLinkedQueue<String>()
    val failureMethod = AtomicReference(failing)
    val present = AtomicBoolean(true)
    val native = localDeleteNative(calls, present, failureMethod, onRowRead, beforeDelete, localDeleteEligible)
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fixtureAccount = AccountSummaryFfi(LOCAL_DELETE_ACCOUNT, LOCAL_DELETE_ACCOUNT_ID, true, false, false, true)
    val state =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { LOCAL_DELETE_ACCOUNT_ID },
            accounts = listOf(fixtureAccount),
            activeAccountRef = LOCAL_DELETE_ACCOUNT,
            initialMarmotRuntime = AppMarmotRuntime("local-delete-test", native),
            // Share the virtual readiness deadline's clock instead of racing real IO dispatch.
            marmotIoDispatcher = StandardTestDispatcher(testScheduler),
        )
    configureState(state, present)
    val controller = ChatsController(state, LOCAL_DELETE_ACCOUNT) { _, _ -> emptyList() }
    controllers += controller
    controller.setChatListVisible(false)
    controller.applyChatListRow(chatRow(LOCAL_DELETE_GROUP))
    controller.setChatListVisible(true)
    assertEquals(
        "fixture must begin with the real visible row",
        listOf(LOCAL_DELETE_GROUP),
        controller.items.map { it.id },
    )
    return LocalGroupDeleteFixture(state, controller, calls, failureMethod)
}

/** Exercise the production debounced projection before checking visible-row mutations. */
internal fun flushLocalDeleteChatRows() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
}

/** A native runtime that answers only the calls a local removal makes, and fails the one named by [failing]. */
private fun localDeleteNative(
    calls: ConcurrentLinkedQueue<String>,
    present: AtomicBoolean,
    failing: AtomicReference<String?>,
    onRowRead: () -> Unit,
    beforeDelete: suspend () -> Unit,
    localDeleteEligible: () -> Boolean,
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
                if (present.get()) chatRow(LOCAL_DELETE_GROUP) else null
            }
            "presentedChatListRow" -> {
                onRowRead()
                if (!present.get()) {
                    null
                } else {
                    val row = chatRow(LOCAL_DELETE_GROUP)
                    PresentedChatRowFfi(
                        null,
                        emptyChatRowPreview(),
                        noChatRowActions().copy(canDeleteLocal = localDeleteEligible()),
                        row,
                        ConversationPresentationFfi(
                            PresentationTextFfi.Literal("Local delete test"),
                            SelectedAvatarFfi.Placeholder(row.groupIdHex, PresentationSourceFfi.GROUP_FALLBACK),
                            PresentationSourceFfi.GROUP_FALLBACK,
                            PresentationSourceFfi.GROUP_FALLBACK,
                            null,
                            PresentationResolutionFfi.FALLBACK,
                        ),
                        null,
                    )
                }
            }
            "listMedia" -> emptyList<Any>()
            "catchUpAccounts" -> Unit
            "deleteGroupLocal", "forgetGroupLocal" -> {
                val operation: suspend () -> Boolean = {
                    beforeDelete()
                    present.set(false)
                    true
                }
                @Suppress("UNCHECKED_CAST")
                operation.startCoroutine(arguments!!.last() as Continuation<Boolean>)
                COROUTINE_SUSPENDED
            }
            "selectedMessageDraft", "clearMessageDraftIfRevision" ->
                SelectedMessageDraftFfi(draftRevisionStub(), null)
            "toString" -> "LocalDeleteTestNative"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> error("Unexpected local-delete native call: $name")
        }
    } as MarmotInterface

/** The revision is passed opaquely to the fake — never invoke or load its native handle. */
private fun draftRevisionStub(): MessageDraftRevisionFfi {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafeClass
        .getMethod("allocateInstance", Class::class.java)
        .invoke(unsafe, MessageDraftRevisionFfi::class.java) as MessageDraftRevisionFfi
}
