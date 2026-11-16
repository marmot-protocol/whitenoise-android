package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.GroupCommitDeliveryFfi
import dev.ipf.marmotkit.GroupCommitRefusedRelayFfi

/**
 * Relays that refused one of the user's confirmed changes to this chat for good, as MDK reports them through
 * `groupCommitDelivery`, and the user's dismissal. MDK keeps the refusal across restarts until it is acknowledged,
 * and blocks further group changes meanwhile.
 */
internal class GroupCommitRefusalState {
    /** The refusing relays from the latest successful read; a failed read keeps the previous answer. */
    var refusedRelays by mutableStateOf<List<GroupCommitRefusedRelayFfi>>(emptyList())
        internal set

    /** Whether the user's dismissal is running. */
    var dismissing by mutableStateOf(false)
        internal set

    /** Why the latest dismissal failed, as a localized message, or null. */
    @get:StringRes
    var dismissFailure by mutableStateOf<Int?>(null)
        internal set

    /** Test seam for MDK's commit-delivery read; null reads through the runtime. */
    internal var deliveryReader: (suspend (String, String) -> GroupCommitDeliveryFfi?)? = null

    /** Test seam for MDK's acknowledgement of the refusal; null acknowledges through the runtime. */
    internal var acknowledger: (suspend (String, String) -> Boolean)? = null
}
