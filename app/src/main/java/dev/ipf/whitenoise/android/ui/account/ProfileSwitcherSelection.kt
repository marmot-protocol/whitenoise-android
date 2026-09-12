package dev.ipf.whitenoise.android.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** One visible sheet owns selection intent; native activation and post-activation work retain their existing owner. */
internal class ProfileSwitcherSelection {
    private var open = true
    private var generation = 0
    var pendingLabel by mutableStateOf<String?>(null)
        private set

    /** Invalidate UI intent synchronously on Back, Close, another route or sheet disposal. */
    fun close() {
        open = false
        generation++
        pendingLabel = null
    }

    /** Match the same UI request, runtime and native account identity before any activation stage. */
    private fun ownsIntent(
        appState: WhiteNoiseAppState,
        label: String,
        accountIdHex: String,
        request: Int,
        runtime: Int,
    ): Boolean {
        val currentRequest = open && generation == request && appState.runtimeGeneration == runtime
        return currentRequest &&
            !appState.profileSwitcherBlocked() &&
            appState.accounts.any {
                it.label == label && it.accountIdHex.equals(accountIdHex, ignoreCase = true)
            }
    }

    /** Validate the current row at the tap and again after every native suspension before account publication. */
    fun select(
        appState: WhiteNoiseAppState,
        label: String,
        onActivated: () -> Unit,
    ) {
        val target = appState.accounts.firstOrNull { it.label == label } ?: return
        if (!open || pendingLabel == label || appState.profileSwitcherBlocked()) return
        val source = appState.activeAccountRef
        val runtime = appState.runtimeGeneration
        val request = ++generation
        pendingLabel = label

        fun ownsIntent(): Boolean = this.ownsIntent(appState, label, target.accountIdHex, request, runtime)
        appState.launchMutation {
            try {
                if (!ownsIntent() || appState.activeAccountRef != source) return@launchMutation
                appState.setActiveAccount(
                    label = label,
                    preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS,
                    shouldActivate = { ownsIntent() && appState.activeAccountRef == source },
                    onActivated = {
                        if (ownsIntent() && appState.activeAccountRef == label) {
                            pendingLabel = null
                            onActivated()
                        }
                    },
                )
            } finally {
                if (generation == request) pendingLabel = null
            }
        }
    }
}

/** Retained reactivation and destructive account operations own their own UI transition. */
internal fun WhiteNoiseAppState.profileSwitcherBlocked(): Boolean {
    val accountOperationInProgress = signOutInProgress || wipeInProgress
    return accountOperationInProgress || retainedAccountReactivationRef != null
}
