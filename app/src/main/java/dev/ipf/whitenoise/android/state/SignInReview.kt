package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.SignInResponseFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** MDK's sign-in review reads and answers for one runtime, so tests can stand in for the native library. */
internal interface SignInReviewNative {
    /** Sign-ins of this account that this device does not recognize, the earliest first. */
    suspend fun unrecognizedSignIns(accountRef: String): List<UnrecognizedSignInFfi>

    /** Checks the relays for new sign-ins now and returns the list after the check; fails when no relay answered. */
    suspend fun checkSignIns(accountRef: String): List<UnrecognizedSignInFfi>

    /** Answers the sign-in in [slotIdHex]. */
    suspend fun answerSignIn(
        accountRef: String,
        slotIdHex: String,
        response: SignInResponseFfi,
    )

    /** The account's open compromise warning on this device; empty when none is open. */
    suspend fun compromiseWarning(accountRef: String): List<CompromiseWarningItemFfi>

    /** Marks everything the warning gathers as seen, on this device only. */
    suspend fun dismissCompromiseWarning(accountRef: String)

    /** Whether this device started fresh and still has no device group. */
    suspend fun startedFresh(accountRef: String): Boolean

    /** Starts this device without the devices its first check found. */
    suspend fun startFresh(accountRef: String)
}

/**
 * The sign-ins as last read. [checkFailed] marks a relay check that reached no relay: the list is then whatever was
 * read before and may be stale, which is different from a check that found nothing.
 */
internal data class SignInListState(
    val signIns: List<UnrecognizedSignInFfi> = emptyList(),
    val loaded: Boolean = false,
    val checking: Boolean = false,
    val checkFailed: Boolean = false,
)

/** A review action the user started. */
internal enum class SignInReviewAction {
    Check,
    Answer,
    DismissWarning,
    StartFresh,
}

/** A review action that failed, kept until the screen has shown it once. */
internal data class SignInReviewFailure(
    val action: SignInReviewAction,
    val cause: Throwable,
)

/**
 * Mirrors one account's unrecognized sign-ins and compromise warning from MDK and sends the user's answers. The
 * three answers stay separate calls with separate meanings: Not now keeps the sign-in listed, Reject records it as
 * not the user's on every device, and Dismiss only ends a prompt another device already answered. Dismissing the
 * warning marks it seen here and changes nothing else.
 */
@Stable
internal class SignInReviewController(
    val accountRef: String,
    private val native: SignInReviewNative,
    private val owns: () -> Boolean = { true },
) {
    var list by mutableStateOf(SignInListState())
        private set

    var warning by mutableStateOf<List<CompromiseWarningItemFfi>>(emptyList())
        private set

    var startedFresh by mutableStateOf(false)
        private set

    var running by mutableStateOf<SignInReviewAction?>(null)
        private set

    var failure by mutableStateOf<SignInReviewFailure?>(null)
        private set

    private val actions = Mutex()
    private val reads = Mutex()

    @Volatile
    private var retired = false

    private val current: Boolean
        get() = !retired && owns()

    /** Stops publishing for good. */
    fun retire() {
        retired = true
    }

    /** Forgets the failure once the screen has shown it. */
    fun failureShown() {
        failure = null
    }

    /** Re-reads the list, the warning and the started-fresh flag locally; a failed read keeps what is shown. */
    suspend fun refresh() {
        reads.withLock {
            val signIns = read { native.unrecognizedSignIns(accountRef) }
            val items = read { native.compromiseWarning(accountRef) }
            val fresh = read { native.startedFresh(accountRef) }
            if (!current) return
            if (signIns != null) list = list.copy(signIns = signIns, loaded = true)
            if (items != null) warning = items
            if (fresh != null) startedFresh = fresh
        }
    }

    /** Re-reads when MDK reports a change that concerns this account's sign-ins, warning or roster. */
    suspend fun onEvent(event: MarmotEventFfi) {
        val mine =
            when (event) {
                is MarmotEventFfi.UnrecognizedSignInsChanged -> event.accountLabel == accountRef
                is MarmotEventFfi.CompromiseWarningChanged -> event.accountLabel == accountRef
                is MarmotEventFfi.LinkedDevicesChanged -> event.accountLabel == accountRef
                else -> false
            }
        if (mine) refresh()
    }

    /**
     * Checks the relays now. A check that reached no relay keeps the list and sets [SignInListState.checkFailed]; it
     * is never shown as "no sign-ins".
     */
    @Suppress("TooGenericExceptionCaught") // A failed relay check is a state of the list, not a crash.
    suspend fun check() {
        if (!current || !actions.tryLock()) return
        try {
            running = SignInReviewAction.Check
            list = list.copy(checking = true)
            val checked =
                try {
                    native.checkSignIns(accountRef)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    null
                }
            if (!current) return
            list =
                if (checked == null) {
                    list.copy(checking = false, checkFailed = true)
                } else {
                    SignInListState(signIns = checked, loaded = true)
                }
        } finally {
            running = null
            actions.unlock()
        }
    }

    /** Sends the user's [response] for the sign-in in [slotIdHex]. */
    suspend fun answer(
        slotIdHex: String,
        response: SignInResponseFfi,
    ) = act(SignInReviewAction.Answer) { native.answerSignIn(accountRef, slotIdHex, response) }

    /** Marks the warning seen on this device. It revokes nothing and secures nothing. */
    suspend fun dismissWarning() {
        act(SignInReviewAction.DismissWarning) { native.dismissCompromiseWarning(accountRef) }
    }

    /** Starts this device without the devices signed in before it. MDK rejects none of them. */
    suspend fun startFresh() = act(SignInReviewAction.StartFresh) { native.startFresh(accountRef) }

    /** Runs one action, then re-reads the state it may have changed. */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure is shown to the user once.
    private suspend fun act(
        action: SignInReviewAction,
        run: suspend () -> Unit,
    ) {
        if (!current || !actions.tryLock()) return
        try {
            running = action
            run()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (current) failure = SignInReviewFailure(action, error)
        } finally {
            running = null
            actions.unlock()
        }
        refresh()
    }

    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure keeps the prior value.
    private suspend fun <T> read(call: suspend () -> T): T? =
        try {
            call()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }
}
