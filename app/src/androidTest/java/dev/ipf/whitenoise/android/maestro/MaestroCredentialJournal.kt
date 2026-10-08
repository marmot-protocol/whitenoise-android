package dev.ipf.whitenoise.android.maestro

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.WindowManager
import androidx.biometric.BiometricManager
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppLockDelay
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isAppLockCredentialAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Check a real credential and absence of an enrolled biometric; never override production state. */
internal fun requireMaestroSyntheticCredential(
    context: Context,
    state: WhiteNoiseAppState,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("app-lock-credential-") != true) return false
    check(checkNotNull(context.getSystemService(KeyguardManager::class.java)).isDeviceSecure)
    check(isAppLockCredentialAvailable(context))
    val biometric = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    check(
        biometric == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
            biometric == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
    )
    check(state.appLockCredentialAvailable)
    return true
}

/** Bounded passive state/window samples; these do not certify frame-by-frame flash or Recents protection. */
internal class MaestroCredentialJournal {
    private val observations = mutableListOf<JSONObject>()

    suspend fun observe(
        state: WhiteNoiseAppState,
        activity: MainActivity,
    ) {
        val row =
            withContext(Dispatchers.Main.immediate) {
                JSONObject()
                    .put("cover", state.appLockScreenVisible)
                    .put("evaluating", state.appUnlockEvaluationPending)
                    .put("cancelled", state.appUnlockError == AppText.Resource(R.string.app_lock_auth_cancelled))
                    .put("activeSession", state.appUnlockSessions.activeSessionId ?: JSONObject.NULL)
                    .put("latestSession", state.appUnlockSessions.latestSessionId)
                    .put("required", state.requireAppUnlock)
                    .put("available", state.appLockCredentialAvailable)
                    .put("orientation", activity.resources.configuration.orientation)
                    .put("secure", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        if (observations.lastOrNull()?.toString() != row.toString()) {
            check(observations.size < 128) { "App-lock observation budget exhausted" }
            observations += row
        }
    }

    suspend fun verify(
        context: Context,
        state: WhiteNoiseAppState,
        preferences: SharedPreferences,
        postcondition: String,
    ): JSONObject {
        requireMaestroSyntheticCredential(context, state, postcondition)
        val cancelled = observations.indexOfFirst(::cancelledSecure)
        check(cancelled >= 0) { "No actual secure cancellation cover observed" }
        if (postcondition == "app-lock-credential-rotation") verifyRotation(cancelled)
        val final = checkNotNull(observations.lastOrNull())
        check(!final.getBoolean("cover") && !final.getBoolean("evaluating"))
        check(final.isNull("activeSession") && final.getBoolean("required") && final.getBoolean("available"))
        check(final.getLong("latestSession") > observations[cancelled].getLong("latestSession"))
        withContext(Dispatchers.Main.immediate) {
            check(state.appUnlockError == null)
            check(state.appLockDelay == AppLockDelay.Immediately)
        }
        check(preferences.getBoolean("require_app_unlock", false))
        check(preferences.getString("app_lock_delay", null) == null)
        return JSONObject()
            .put("cancelledSecure", true)
            .put("rotatedCover", postcondition == "app-lock-credential-rotation")
            .put("cancelledSession", observations[cancelled].getLong("latestSession"))
            .put("acceptedSession", final.getLong("latestSession"))
            .put("acceptedState", true)
            .put("observations", JSONArray(observations))
    }

    private fun cancelledSecure(row: JSONObject): Boolean {
        val cancelledCover = row.getBoolean("cover") && row.getBoolean("secure") && row.getBoolean("cancelled")
        return cancelledCover && !row.getBoolean("evaluating") && row.isNull("activeSession")
    }

    private fun verifyRotation(cancelled: Int) {
        val landscape =
            observations.withIndex().firstOrNull { (index, row) ->
                index > cancelled && cancelledSecure(row) &&
                    row.getInt("orientation") == Configuration.ORIENTATION_LANDSCAPE
            }
        checkNotNull(landscape) { "Cancelled secure landscape cover was not observed" }
        check(
            observations.withIndex().any { (index, row) ->
                index > landscape.index && cancelledSecure(row) &&
                    row.getInt("orientation") == Configuration.ORIENTATION_PORTRAIT
            },
        ) { "Cancelled secure portrait cover was not restored" }
    }
}
