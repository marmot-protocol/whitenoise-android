package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import kotlinx.coroutines.sync.Mutex

/** Posts and withdraws one account's sign-in notifications. */
internal interface SignInAlertNotifications {
    /** Reads the number on the active review notification, or zero if it is gone. */
    fun shownReviewCount(accountRef: String): Int

    /** Shows that [count] sign-ins wait for an answer; [alert] false updates a shown notification quietly. */
    fun postReview(
        accountRef: String,
        count: Int,
        alert: Boolean,
    ): Boolean

    /** Takes the review notification down. */
    fun withdrawReview(accountRef: String)

    /** Shows that the account's key may be compromised. */
    fun postCompromise(accountRef: String): Boolean

    /** Takes the compromise notification down. */
    fun withdrawCompromise(accountRef: String)
}

/** One account's sign-ins and compromise warning as MDK last reported them, with what this device adds. */
internal data class SignInAlertSnapshot(
    val accountRef: String,
    val signIns: List<UnrecognizedSignInFfi> = emptyList(),
    val warning: List<CompromiseWarningItemFfi> = emptyList(),
    val mine: Set<String> = emptySet(),
    val alerted: Set<String> = emptySet(),
) {
    /** The sign-ins that still ask for an answer. */
    val toReview: List<UnrecognizedSignInFfi>
        get() = signInsToReview(signIns, mine)

    /** How loudly the account is announced. */
    val tier: SignInAlertTier
        get() = signInAlertTier(toReview, warning)

    /** The warning items the full-screen alert has not shown on this device. */
    val alertDue: List<CompromiseWarningItemFfi>
        get() = warning.filter { compromiseWarningKey(it) !in alerted }
}

/** A request to open one account's Devices screen. Each request is new, so the same account can be asked twice. */
internal data class SignInDevicesRequest(
    val accountRef: String,
    val id: Long,
    val accountOpened: Boolean = false,
    val linkReportGeneration: Int? = null,
) {
    /** A report may wait for unlock, but a profile or runtime change cancels its handoff. */
    fun isStaleLinkReport(
        activeAccountRef: String?,
        runtimeGeneration: Int,
    ): Boolean =
        linkReportGeneration != null &&
            (linkReportGeneration != runtimeGeneration || accountRef != activeAccountRef)
}

/**
 * The app-wide view of every signed-in account's sign-in alerts, outside the Devices screen: the chat-list banner,
 * the Devices row badge, the full-screen alert and the notifications all read it. MDK's state is published here as
 * read, and only this device's presentation choices are kept: which sign-ins the user called theirs, and what has
 * already been announced. It never decides that a key is compromised; MDK's warning does.
 */
@Stable
internal class SignInAlerts(
    private val store: SignInAlertStore,
    private val notifications: SignInAlertNotifications,
) {
    var snapshots by mutableStateOf<Map<String, SignInAlertSnapshot>>(emptyMap())
        private set

    var devicesRequest by mutableStateOf<SignInDevicesRequest?>(null)
        private set

    // Bumped on every write so composables reading what the store keeps are redrawn.
    private var revision by mutableIntStateOf(0)

    private val lock = Any()
    val refreshMutex = Mutex()
    private val accountIds = mutableMapOf<String, String>()
    private var requests = 0L

    /** The snapshot for [accountRef], or null before its first read. */
    fun snapshot(accountRef: String?): SignInAlertSnapshot? = accountRef?.let { snapshots[it] }

    /** Publishes what MDK reported for one account and posts or withdraws its notifications to match. */
    fun publish(
        accountRef: String,
        accountIdHex: String,
        signIns: List<UnrecognizedSignInFfi>,
        warning: List<CompromiseWarningItemFfi>,
    ) = synchronized(lock) {
        accountIds[accountRef] = accountIdHex
        val record = store.read(accountIdHex)
        val snapshot = SignInAlertSnapshot(accountRef, signIns, warning, record.mine, record.alertedWarning)
        snapshots = snapshots + (accountRef to snapshot)
        announce(snapshot, accountIdHex, record)
    }

    /** Drops an account alerts no longer apply to, and takes its notifications down. What the store keeps stays. */
    fun forget(accountRef: String) =
        synchronized(lock) {
            accountIds.remove(accountRef)
            if (snapshots.containsKey(accountRef)) snapshots = snapshots - accountRef
            notifications.withdrawReview(accountRef)
            notifications.withdrawCompromise(accountRef)
        }

    fun wipe(
        accountRef: String,
        accountIdHex: String?,
    ) = synchronized(lock) {
        (accountIdHex ?: accountIds[accountRef])?.let(store::clear)
        forget(accountRef)
    }

    /** The sign-ins the user answered "Mine, don't link" on this device for the account [accountIdHex]. */
    fun mine(accountIdHex: String): Set<String> {
        revision
        return store.read(accountIdHex).mine
    }

    /** Records "Mine, don't link" for [slotIdHex] on this device. The sign-in stops being announced at once. */
    fun markMine(
        accountRef: String,
        accountIdHex: String,
        slotIdHex: String,
    ) = synchronized(lock) {
        val record = store.read(accountIdHex)
        val updated = record.copy(mine = record.mine + slotIdHex)
        write(accountIdHex, updated)
        snapshots[accountRef]?.let { current ->
            val snapshot = current.copy(mine = updated.mine)
            snapshots = snapshots + (accountRef to snapshot)
            announce(snapshot, accountIdHex, updated)
        }
    }

    /** Records that the full-screen alert showed every warning item [accountRef] has open now. */
    fun alertShown(
        accountRef: String,
        shown: List<CompromiseWarningItemFfi>,
    ) = synchronized(lock) {
        val snapshot = snapshots[accountRef] ?: return@synchronized
        val accountIdHex = accountIds[accountRef] ?: return@synchronized
        val record = store.read(accountIdHex)
        val alerted = record.alertedWarning + shown.map(::compromiseWarningKey)
        write(accountIdHex, record.copy(alertedWarning = alerted))
        snapshots = snapshots + (accountRef to snapshot.copy(alerted = alerted))
    }

    /** Asks the app to open [accountRef]'s Devices screen, switching to that account first when needed. */
    fun requestDevices(accountRef: String) =
        synchronized(lock) {
            devicesRequest = SignInDevicesRequest(accountRef, ++requests)
        }

    /** Keeps a successful decline's review handoff without displacing an explicit Devices request. */
    fun requestDevicesAfterLinkDecline(
        accountRef: String,
        runtimeGeneration: Int,
    ) {
        synchronized(lock) {
            if (devicesRequest == null) {
                devicesRequest = SignInDevicesRequest(accountRef, ++requests, linkReportGeneration = runtimeGeneration)
            }
        }
    }

    /** Advances or clears only the matching Devices request. */
    fun updateDevicesRequest(
        expected: SignInDevicesRequest,
        next: SignInDevicesRequest? = null,
    ) {
        if (devicesRequest == expected) devicesRequest = next
    }

    private fun announce(
        snapshot: SignInAlertSnapshot,
        accountIdHex: String,
        record: SignInAlertRecord,
    ) {
        val accountRef = snapshot.accountRef
        val plan =
            planSignInNotifications(
                toReview = snapshot.toReview,
                warning = snapshot.warning,
                announcedSignIns = record.announcedSignIns,
                announcedWarning = record.announcedWarning,
                shownReviewCount = notifications.shownReviewCount(accountRef),
            )
        val reviewPosted =
            plan.reviewCount?.let { count ->
                notifications.postReview(accountRef, count, plan.reviewAlerts)
            } ?: false
        // Withdrawn even when this process posted nothing: the notification may be from before a restart.
        if (plan.withdrawReview) notifications.withdrawReview(accountRef)
        val warningPosted = plan.postCompromise && notifications.postCompromise(accountRef)
        if (plan.withdrawCompromise) notifications.withdrawCompromise(accountRef)
        val updated =
            record.copy(
                announcedSignIns = if (reviewPosted) plan.announcedSignIns else record.announcedSignIns,
                announcedWarning = if (warningPosted) plan.announcedWarning else record.announcedWarning,
            )
        if (updated != record) write(accountIdHex, updated)
    }

    private fun write(
        accountIdHex: String,
        record: SignInAlertRecord,
    ) {
        store.write(accountIdHex, record)
        revision++
    }
}
