package dev.ipf.whitenoise.android.maestro

import android.content.SharedPreferences
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.json.JSONObject

/** Actual credential or no-credential proof; nonparticipating fixtures retain an explicit false result. */
internal data class MaestroAppLockProof(
    private val credentialEvidence: JSONObject?,
    val verified: Boolean,
) {
    val credentialJsonValue: Any get() = credentialEvidence ?: JSONObject.NULL
}

/** Keep passive journal completion and the OS-prerequisite checks outside the generated fixture host. */
internal suspend fun verifyMaestroAppLock(
    journal: MaestroCredentialJournal?,
    activity: MainActivity,
    state: WhiteNoiseAppState,
    preferences: SharedPreferences,
    postcondition: String?,
): MaestroAppLockProof {
    if (journal != null) {
        journal.observe(state, activity)
        val evidence = journal.verify(activity, state, checkNotNull(postcondition))
        return MaestroAppLockProof(evidence, true)
    }
    return MaestroAppLockProof(
        null,
        verifyMaestroNoAppLockCredential(activity, state, preferences, postcondition),
    )
}
