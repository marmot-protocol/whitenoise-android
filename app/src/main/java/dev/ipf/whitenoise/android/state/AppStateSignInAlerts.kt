package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import dev.ipf.whitenoise.android.notifications.SignInAlertNotifier
import kotlinx.coroutines.sync.withLock

private object SignInAlertsHolder {
    @Volatile
    private var shared: SignInAlerts? = null

    /** The process's one [SignInAlerts], so the notification runtime and the screens read the same state. */
    fun shared(context: Context): SignInAlerts =
        shared ?: synchronized(this) {
            shared ?: SignInAlerts(
                PreferencesSignInAlertStore(
                    context.applicationContext.getSharedPreferences(
                        PreferencesSignInAlertStore.PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    ),
                ),
                SignInAlertNotifier(context),
            ).also { shared = it }
        }
}

/** Every signed-in account's sign-in alerts. */
internal val WhiteNoiseAppState.signInAlerts: SignInAlerts
    get() = SignInAlertsHolder.shared(appContext)

/** The account whose sign-ins or compromise warning [event] says may have changed, or null for any other event. */
internal fun signInAlertAccount(event: MarmotEventFfi): String? =
    when (event) {
        is MarmotEventFfi.UnrecognizedSignInsChanged -> event.accountLabel
        is MarmotEventFfi.CompromiseWarningChanged -> event.accountLabel
        else -> null
    }

/**
 * Re-reads [accountRef]'s sign-ins and compromise warning from MDK and publishes them, which posts or withdraws its
 * notifications. An account alerts do not apply to is forgotten. A failed read keeps what was published before.
 */
internal suspend fun WhiteNoiseAppState.refreshSignInAlerts(accountRef: String) {
    val alerts = signInAlerts
    alerts.refreshMutex.withLock {
        val account = accounts.firstOrNull { it.label == accountRef }
        if (!linkedDevicesAvailable() || account == null || !signInAlertsApply(account)) {
            if (account != null || accountListLoaded) alerts.forget(accountRef)
            return@withLock
        }
        val generation = runtimeGeneration
        val (signIns, warning) =
            runCatchingCancellable {
                marmotIo { unrecognizedSignIns(accountRef) to compromiseWarning(accountRef) }
            }.getOrNull() ?: return@withLock
        val current = accounts.firstOrNull { it.label == accountRef }
        if (
            generation == runtimeGeneration &&
            current?.accountIdHex == account.accountIdHex &&
            signInAlertsApply(current)
        ) {
            alerts.publish(accountRef, account.accountIdHex, signIns, warning)
        }
    }
}

/** Re-reads every signed-in account, so a sign-in found while no event stream listened is still announced. */
internal suspend fun WhiteNoiseAppState.refreshAllSignInAlerts() {
    (accounts.map { it.label } + signInAlerts.snapshots.keys).distinct().forEach { refreshSignInAlerts(it) }
}

/**
 * Hides the open compromise warning on this device for [accountRef], then re-reads it. It revokes and secures
 * nothing; MDK reopens the warning when something new arrives.
 */
internal suspend fun WhiteNoiseAppState.hideCompromiseWarning(accountRef: String): Boolean {
    val hidden = runCatchingCancellable { marmotIo { dismissCompromiseWarning(accountRef) } }.isSuccess
    refreshSignInAlerts(accountRef)
    return hidden
}

/** Records "Mine, don't link" for [slotIdHex] on [accountRef], kept on this device only (marmot#519). */
internal fun WhiteNoiseAppState.markSignInMine(
    accountRef: String,
    slotIdHex: String,
) {
    val accountIdHex = accounts.firstOrNull { it.label == accountRef }?.accountIdHex ?: return
    signInAlerts.markMine(accountRef, accountIdHex, slotIdHex)
}

/** The sign-ins answered "Mine, don't link" on this device for [accountRef]. */
internal fun WhiteNoiseAppState.signInsMarkedMine(accountRef: String): Set<String> {
    val accountIdHex = accounts.firstOrNull { it.label == accountRef }?.accountIdHex ?: return emptySet()
    return signInAlerts.mine(accountIdHex)
}
