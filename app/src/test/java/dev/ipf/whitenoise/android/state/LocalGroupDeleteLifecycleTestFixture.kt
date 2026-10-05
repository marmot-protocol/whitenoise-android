package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState

/**
 * Simulate lifecycle replacement without adding writable production APIs for tests.
 * Depends on AppState's runtimeGeneration and retainedAccountReactivationRef delegated field names.
 */
internal fun WhiteNoiseAppState.advanceLocalDeleteTestRuntime() {
    val field = javaClass.getDeclaredField("runtimeGeneration\$delegate").apply { isAccessible = true }
    val generation = field.get(this) as MutableIntState
    generation.intValue++
}

@Suppress("UNCHECKED_CAST") // The named production delegate is MutableState<String?>.
internal fun WhiteNoiseAppState.setLocalDeleteTestReactivation(account: String?) {
    val field = javaClass.getDeclaredField("retainedAccountReactivationRef\$delegate").apply { isAccessible = true }
    val reactivation = field.get(this) as MutableState<String?>
    reactivation.value = account
}
