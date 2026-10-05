package dev.ipf.whitenoise.android.ui.profile

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CancellationException

/** Screen-local presentation of native metadata; optional public fields never determine readiness. */
internal data class ProfileReadiness(
    @param:StringRes val summary: Int,
    val fields: List<Boolean> = emptyList(),
) {
    companion object {
        val Loading = ProfileReadiness(R.string.profile_readiness_loading)
        val Unavailable = ProfileReadiness(R.string.profile_readiness_unavailable)
    }
}

/** Treat only a nonblank effective name as required; preserve a loaded empty record as distinct from no record. */
internal fun profileReadiness(profile: UserProfileMetadataFfi): ProfileReadiness {
    val named =
        !profile.displayName
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
            .ifBlank { profile.name.orEmpty() }
            .isBlank()
    val fields =
        listOf(
            named,
            !profile.picture.isNullOrBlank(),
            !profile.about.isNullOrBlank(),
            !profile.banner.isNullOrBlank(),
            !profile.nip05.isNullOrBlank(),
            !profile.lud16.isNullOrBlank(),
        )
    val summary =
        when {
            !named -> R.string.profile_readiness_setup
            fields.all { it } -> R.string.profile_readiness_ready
            else -> R.string.profile_readiness_optional
        }
    return ProfileReadiness(summary, fields)
}

/** Retains only a screen projection, never another profile store; failed refreshes retain known authoritative state. */
internal class ProfileReadinessState(
    cached: UserProfileMetadataFfi?,
) {
    var value by mutableStateOf(cached?.let(::profileReadiness) ?: ProfileReadiness.Loading)
        private set
    private var generation = 0
    private var readSucceeded = false

    /** The newest native read wins; cancellation and obsolete completions cannot overwrite it. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun refresh(
        cached: UserProfileMetadataFfi?,
        read: suspend () -> UserProfileMetadataFfi?,
    ) {
        val current = ++generation
        if (!readSucceeded) cached?.let { value = profileReadiness(it) }
        val profile =
            try {
                read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        if (current != generation) return
        if (profile != null) {
            readSucceeded = true
            value = profileReadiness(profile)
        } else if (value == ProfileReadiness.Loading) {
            value = ProfileReadiness.Unavailable
        }
    }
}

/** Bind each transient projection to one account and reload on native invalidation or accepted publication. */
@Composable
internal fun rememberProfileReadiness(
    owner: Any,
    accountId: String?,
    revision: Any,
    cachedProfile: (String) -> UserProfileMetadataFfi?,
    loadProfile: suspend (String) -> UserProfileMetadataFfi?,
): ProfileReadiness {
    val cached = accountId?.let(cachedProfile)
    val state = remember(owner, accountId) { ProfileReadinessState(cached) }
    LaunchedEffect(owner, accountId, revision) {
        accountId?.let { id -> state.refresh(cached) { loadProfile(id) } }
    }
    return state.value
}
