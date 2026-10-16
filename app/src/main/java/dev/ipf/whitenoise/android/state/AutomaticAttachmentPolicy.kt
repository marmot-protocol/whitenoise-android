package dev.ipf.whitenoise.android.state

import androidx.work.NetworkType

/** Whether the receiving account is loaded locally, and signed in, when an automatic-download decision is made. */
internal enum class AutomaticPolicyAccount {
    SignedIn,
    SignedOut,
    Unknown,
}

/**
 * Why automatic work for one attachment type cannot run right now. Only [Network] can clear without the user
 * acting, so it is the only hold that durable work waits out instead of finishing.
 */
internal enum class AutomaticAttachmentHold(
    val reason: String,
) {
    Paused("paused"),
    SignedOut("signed_out"),
    UnknownAccount("unknown_account"),
    Disabled("disabled"),
    Network("network"),
    ;

    /**
     * Whether durable work held back for this reason, on its [runAttemptCount]th run, runs again later. Only a
     * network hold can clear without the user, and only for a bounded number of runs, so a connection that never
     * validates cannot keep the work alive.
     */
    fun waitsAfterRun(runAttemptCount: Int): Boolean {
        val withinBound = runAttemptCount < AUTOMATIC_NETWORK_DEFERRAL_RUNS
        return this == Network && withinBound
    }
}

/** Runs a network-held automatic download may wait through, about two hours at the worker's backoff. */
internal const val AUTOMATIC_NETWORK_DEFERRAL_RUNS = 8

/**
 * One account's automatic-download inputs at one instant (#3308). [allowedNow] evaluates them exactly as the
 * native permission is evaluated, and the rest derives what receipt scheduling may queue and where it may run.
 */
internal data class AutomaticAttachmentPolicy(
    val account: AutomaticPolicyAccount,
    val paused: Boolean,
    val matrix: MediaAutoDownloadMatrix,
    val networks: Set<MediaAutoDownloadNetwork>,
    val validatedInternet: Boolean,
) {
    /** The types the native permission allows on the live network: none unless the account is loaded and signed in. */
    fun allowedNow(): Set<MediaAutoDownloadType> {
        if (account != AutomaticPolicyAccount.SignedIn) return emptySet()
        val permission = matrix.nativePermission(networks, validatedInternet, paused)
        return buildSet {
            if (permission.images) add(MediaAutoDownloadType.Image)
            if (permission.videos) add(MediaAutoDownloadType.Video)
            if (permission.audio) add(MediaAutoDownloadType.Audio)
            if (permission.files) add(MediaAutoDownloadType.Document)
        }
    }

    /** Why [type] cannot download automatically now, or null when it can. */
    fun hold(type: MediaAutoDownloadType): AutomaticAttachmentHold? =
        accountHold() ?: when {
            !enabledOnAnyNetwork(type) -> AutomaticAttachmentHold.Disabled
            type !in allowedNow() -> AutomaticAttachmentHold.Network
            else -> null
        }

    /**
     * A hold that applies to every type, decided before durable work looks its attachment up: a signed-out,
     * removed or paused account, or a policy that enables nothing anywhere.
     */
    fun accountHold(): AutomaticAttachmentHold? =
        when {
            account == AutomaticPolicyAccount.SignedOut -> AutomaticAttachmentHold.SignedOut
            account == AutomaticPolicyAccount.Unknown -> AutomaticAttachmentHold.UnknownAccount
            paused -> AutomaticAttachmentHold.Paused
            MediaAutoDownloadType.entries.none(::enabledOnAnyNetwork) -> AutomaticAttachmentHold.Disabled
            else -> null
        }

    /**
     * The types a receipt queues durable work for: every type enabled on some network, so work waits in
     * WorkManager for a network its policy allows. While the account is not loaded yet, as on a cold start,
     * every type is queued and the worker decides once the account's own policy is readable.
     */
    fun receiptTypes(): Set<MediaAutoDownloadType> =
        when {
            account == AutomaticPolicyAccount.SignedOut || paused -> emptySet()
            account == AutomaticPolicyAccount.Unknown -> MediaAutoDownloadType.entries.toSet()
            else -> MediaAutoDownloadType.entries.filterTo(mutableSetOf(), ::enabledOnAnyNetwork)
        }

    /** Why a receipt queues nothing at all, for a privacy-safe log line. */
    fun receiptSkipReason(): AutomaticAttachmentHold =
        when {
            account == AutomaticPolicyAccount.SignedOut -> AutomaticAttachmentHold.SignedOut
            paused -> AutomaticAttachmentHold.Paused
            else -> AutomaticAttachmentHold.Disabled
        }

    /**
     * The narrowest WorkManager network on which [type] could be allowed, so queued work does not wake on a
     * connection whose policy refuses it. With the default settings no type is allowed on metered networks,
     * so automatic work waits for an unmetered one.
     */
    fun networkType(type: MediaAutoDownloadType): NetworkType =
        when {
            account == AutomaticPolicyAccount.Unknown -> NetworkType.CONNECTED
            !matrix.isEnabled(type, MediaAutoDownloadNetwork.Metered) -> NetworkType.UNMETERED
            !matrix.isEnabled(type, MediaAutoDownloadNetwork.Roaming) -> NetworkType.NOT_ROAMING
            else -> NetworkType.CONNECTED
        }

    /** Whether any network row enables [type]. */
    private fun enabledOnAnyNetwork(type: MediaAutoDownloadType): Boolean {
        val rows = MediaAutoDownloadNetwork.entries
        return rows.any { matrix.isEnabled(type, it) }
    }
}
