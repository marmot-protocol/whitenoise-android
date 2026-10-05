package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.RecipientReference
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.Locale

private const val MEMBER_SEARCH_TIMEOUT_MS = 2_000L

/** Roster-only search: public references are validated by MDK, never sent to discovery. */
internal object GroupMemberIdentitySearch {
    private val publicKeyHex = Regex("^[0-9a-fA-F]{64}$")
    private val identityPrefixes =
        listOf(
            "npub1",
            "nprofile1",
            "nsec1",
            "note1",
            "nevent1",
            "naddr1",
            "nostr:",
            "http:",
            "https:",
            "marmot:",
            "whitenoise:",
            "whitenoise-staging:",
            "whitenoise-dev:",
            "${BuildConfig.WHITENOISE_DEEP_LINK_SCHEME.lowercase(Locale.ROOT)}:",
        )

    fun isIdentityQuery(raw: String): Boolean {
        val query = raw.trim().lowercase(Locale.ROOT)
        return publicKeyHex.matches(query) || query.contains("://") || identityPrefixes.any(query::startsWith)
    }

    /** Shape screening only. MDK must validate checksum and public identity before matching. */
    fun decoderInput(raw: String): String? {
        if (!isIdentityQuery(raw)) return null
        val query = raw.trim()
        val uri = if (query.contains("://")) runCatching { URI(query) }.getOrNull() else null
        val malformedUrl =
            uri == null ||
                uri.userInfo != null ||
                uri.path
                    .orEmpty()
                    .trim('/')
                    .split('/')
                    .size > 2
        val ambiguousUrl = query.contains("://") && malformedUrl
        val bare = if (query.startsWith("nostr:", ignoreCase = true)) query.drop("nostr:".length) else query
        val publicBech32 =
            bare.startsWith("npub1", ignoreCase = true) ||
                bare.startsWith("nprofile1", ignoreCase = true)
        return if (ambiguousUrl) {
            null
        } else {
            RecipientReference.normalize(query) ?: bare.takeIf {
                publicBech32 && it.none { char -> char.isWhitespace() || char == ':' || char == '/' }
            }
        }
    }

    fun clipboardInput(raw: String?): String? = raw?.trim()?.takeIf { decoderInput(it) != null }

    fun matches(
        query: String,
        resolvedHex: String?,
        memberHex: String,
        title: String,
    ): Boolean =
        if (isIdentityQuery(query)) {
            resolvedHex != null && memberHex.equals(resolvedHex, ignoreCase = true)
        } else {
            title.contains(query.trim(), ignoreCase = true)
        }
}

internal data class GroupMemberSearchResolution(
    val hex: String? = null,
    val resolving: Boolean = false,
    val canRetry: Boolean = false,
)

/** UI state is keyed to the query and its owner; roster/profile data stays with the controller. */
@Composable
internal fun rememberGroupMemberSearchResolution(
    query: String,
    appState: WhiteNoiseAppState,
    owner: Any,
    retry: Int,
): GroupMemberSearchResolution {
    val input = remember(query) { GroupMemberIdentitySearch.decoderInput(query) }
    val account = appState.activeAccountRef
    val generation = appState.runtimeGeneration
    val unavailable = appState.signOutInProgress || appState.wipeInProgress
    var resolution by remember(query, owner, account, generation, retry, unavailable) {
        mutableStateOf(GroupMemberSearchResolution(resolving = input != null && !unavailable))
    }
    LaunchedEffect(query, owner, account, generation, retry, unavailable) {
        if (input != null && !unavailable) {
            val hex = resolveMemberPublicIdentity(appState, input)
            currentCoroutineContext().ensureActive()
            val sameOwner = appState.activeAccountRef == account && appState.runtimeGeneration == generation
            val stillAvailable = !appState.signOutInProgress && !appState.wipeInProgress
            if (sameOwner && stillAvailable) {
                resolution = GroupMemberSearchResolution(hex = hex, canRetry = hex == null)
            }
        }
    }
    return resolution
}

private suspend fun resolveMemberPublicIdentity(
    appState: WhiteNoiseAppState,
    input: String,
): String? =
    withContext(Dispatchers.Default) {
        runCatchingCancellable {
            withTimeoutOrNull(MEMBER_SEARCH_TIMEOUT_MS) { appState.accountIdHex(input) }
        }.getOrNull()
    }
