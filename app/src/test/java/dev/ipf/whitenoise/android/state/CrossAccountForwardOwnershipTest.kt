package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation

/**
 * Production-path account ownership for cross-account forwarding: destination
 * publish work runs only under the explicitly selected destination account,
 * the live active account is never consulted, and removal or sign-out of an
 * owner stops the operation without any fallback account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class CrossAccountForwardOwnershipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class RecordedCall(
        val method: String,
        val accountRef: String,
    )

    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private val messageIdCounter = AtomicInteger(0)
    private var timelineGate: CountDownLatch? = null
    private var onTimelineEntered: (() -> Unit)? = null

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "sendText" -> {
                    calls += RecordedCall("sendText", arguments!![0] as String)
                    SendSummaryFfi(
                        published = 1u,
                        messageIds = listOf("id-${messageIdCounter.incrementAndGet()}"),
                        acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                    )
                }
                "timelineMessages" -> {
                    calls += RecordedCall("timelineMessages", arguments!![0] as String)
                    onTimelineEntered?.invoke()
                    timelineGate?.await()
                    TimelinePageFfi(messages = emptyList(), hasMoreBefore = false, hasMoreAfter = false)
                }
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "CrossAccountForwardMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        error("Unexpected Marmot call: ${method.name}")
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    /** Builds one signed-in signing-account summary. */
    private fun account(
        label: String,
        hexSeed: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = hexSeed.repeat(32),
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** Builds an app state wired to the scripted engine proxy. */
    private fun appState(
        accounts: List<AccountSummaryFfi>,
        activeAccountRef: String,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = accounts,
            activeAccountRef = activeAccountRef,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** Builds one text payload rooted in the source group. */
    private fun textPayload(text: String = "forwarded body") =
        ForwardMessagePayload.Text(
            sourceGroupIdHex = SOURCE_GROUP,
            sourceMessageIdHex = "01".repeat(32),
            text = text,
        )

    /** Pumps the main looper until the operation leaves its active phases. */
    private fun awaitTerminal(
        appState: WhiteNoiseAppState,
        timeoutMillis: Long = 20_000,
    ): ForwardOperationSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val snapshot = appState.activeForwardOperation.value
            if (snapshot != null && !snapshot.isActive) return snapshot
            Thread.sleep(5)
        }
        error("forward operation did not reach a terminal state")
    }

    /** Pumps the main looper until the proxy records the named call. */
    private fun awaitCall(
        method: String,
        timeoutMillis: Long = 20_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (calls.any { it.method == method }) return
            Thread.sleep(5)
        }
        error("never observed a $method call")
    }

    /** All publish traffic runs under the destination while a third account is active. */
    @Test
    fun destinationPublishesRunOnlyUnderTheSelectedAccountRegardlessOfTheActiveAccount() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa"), account(ACCOUNT_B, "bb"), account(ACCOUNT_C, "cc")),
                activeAccountRef = ACCOUNT_C,
            )

        val started =
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE, TARGET_TWO),
                messages = listOf(textPayload(), textPayload("second body")),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            )

        assertTrue(started)
        val terminal = awaitTerminal(appState)
        assertEquals(ForwardOperationPhase.Completed, terminal.phase)
        assertEquals(2, terminal.completedTargets)
        assertEquals(ACCOUNT_B, appState.activeForwardDestinationAccountRef)
        val accountsUsed = calls.map(RecordedCall::accountRef).toSet()
        assertEquals(setOf(ACCOUNT_B), accountsUsed)
        assertEquals(4, calls.count { it.method == "sendText" })
    }

    /** Start returns false when either bound owner is signed out. */
    @Test
    fun startIsRejectedWhenEitherBoundOwnerIsNotASignedInSigningAccount() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa")),
                activeAccountRef = ACCOUNT_A,
            )

        assertFalse(
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(textPayload()),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            ),
        )
        assertFalse(
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(textPayload()),
                sourceAccountRef = ACCOUNT_B,
                destinationAccountRef = ACCOUNT_A,
            ),
        )
        assertTrue(calls.isEmpty())
    }

    /** Mid-flight destination sign-out fails closed with zero sends and no retry. */
    @Test
    fun destinationSignOutMidFlightStopsBeforePublishWithoutAFallbackAccount() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa"), account(ACCOUNT_B, "bb")),
                activeAccountRef = ACCOUNT_A,
            )
        val gate = CountDownLatch(1)
        timelineGate = gate
        onTimelineEntered = { setAccounts(appState, listOf(account(ACCOUNT_A, "aa"))) }

        val started =
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(textPayload()),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            )
        assertTrue(started)
        awaitCall("timelineMessages")
        gate.countDown()

        val terminal = awaitTerminal(appState)
        assertEquals(ForwardOperationPhase.Failed, terminal.phase)
        assertEquals(ForwardFailureStage.SessionChanged, terminal.targets.single().failureStage)
        assertFalse(terminal.canRetry)
        assertTrue(calls.none { it.method == "sendText" })
    }

    /** Replaces the live account list through the snapshot-state delegate. */
    @Suppress("UNCHECKED_CAST")
    private fun setAccounts(
        appState: WhiteNoiseAppState,
        accounts: List<AccountSummaryFfi>,
    ) {
        val delegate =
            WhiteNoiseAppState::class.java
                .getDeclaredField("accounts\u0024delegate")
                .apply { isAccessible = true }
                .get(appState) as androidx.compose.runtime.MutableState<List<AccountSummaryFfi>>
        delegate.value = accounts
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val ACCOUNT_C = "account-c"
        val SOURCE_GROUP = "aa" + "00".repeat(31)
        val TARGET_ONE = "11".repeat(32)
        val TARGET_TWO = "22".repeat(32)
    }
}
