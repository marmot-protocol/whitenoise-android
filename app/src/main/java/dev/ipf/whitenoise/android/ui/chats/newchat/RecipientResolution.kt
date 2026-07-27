package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.Nip05Resolver
import dev.ipf.whitenoise.android.core.NostrProfileReference
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientReference
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_SEARCH_DEBOUNCE_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Resolve an identifier input (npub / profile link / NIP-05 / bare hex) to a
 * pubkey, reporting the same [RecipientResolution] contract the old preview
 * card exposed (issue #631). Plain-text names and empty input stay Empty so
 * the local name search renders instead; callers present the resolved person
 * as an ordinary contact row.
 */
@Composable
internal fun rememberRecipientResolution(
    input: String,
    appState: WhiteNoiseAppState,
): RecipientResolution {
    val trimmed = input.trim()
    var resolving by remember(trimmed) { mutableStateOf(trimmed.isNotEmpty() && !isPlainNameQuery(trimmed)) }
    var resolvedHex by remember(trimmed) { mutableStateOf<String?>(null) }

    LaunchedEffect(trimmed) {
        if (trimmed.isEmpty() || isPlainNameQuery(trimmed)) {
            resolving = false
            resolvedHex = null
            return@LaunchedEffect
        }
        resolving = true
        resolvedHex = null
        val hex =
            NostrProfileReference.accountIdHex(trimmed)
                ?: when (val id = ChatListIdentifierSearch.classify(trimmed)) {
                    is ChatListIdentifierSearch.Identifier.Npub -> appState.accountIdHex(id.npub)
                    is ChatListIdentifierSearch.Identifier.Nip05 -> {
                        // Debounce so a mid-typed domain doesn't fire a lookup on
                        // every keystroke; the effect re-keys and cancels the prior
                        // attempt as typing continues.
                        delay(CHAT_LIST_SEARCH_DEBOUNCE_MS)
                        Nip05Resolver.resolve(id.identifier)
                    }
                    null -> appState.accountIdHex(trimmed)
                }
        resolvedHex = hex
        if (hex != null) appState.refreshProfile(hex)
        resolving = false
    }

    val profile = resolvedHex?.let { appState.userProfile(it) }
    val pictureUrl = resolvedHex?.let { appState.avatarUrl(it) } ?: ProfileSanitizer.imageUrl(profile?.picture)
    val about = ProfileSanitizer.about(profile?.about)
    val nip05 = profile?.nip05?.trim()?.takeIf { ProfileFieldValidation.isAcceptableNip05(it) }
    val hasProfile =
        profile != null &&
            (
                !ProfileSanitizer.displayName(profile.displayName ?: profile.name).isNullOrBlank() ||
                    about != null ||
                    pictureUrl != null ||
                    nip05 != null
            )
    return RecipientResolution(
        recipientPreviewState(trimmed.isNotEmpty(), resolving, resolvedHex, hasProfile),
        resolvedHex,
    )
}

/**
 * True when [query] is plain text (name search) rather than an identifier
 * (npub / profile link / NIP-05 / bare hex) that routes to the preview card.
 */
internal fun isPlainNameQuery(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return false
    if (NostrProfileReference.accountIdHex(trimmed) != null) return false
    if (ChatListIdentifierSearch.classify(trimmed) != null) return false
    if (RecipientReference.normalize(trimmed) != null) return false
    return true
}

/**
 * Distinct, non-active-account recipient candidates derived on demand from the
 * already-loaded chat-list state — UI-derived, not a new cache of protocol data
 * (AGENTS.md), since no profile-enumeration FFI exists.
 *
 * Candidates are ordered by recency of last activity (#831): the chat list is
 * walked most-recent-first so the first chat that surfaces a pubkey fixes its
 * position, letting the picker present a browsable list before the user types.
 * Each candidate carries a [RecipientSearch.Source] hint — "in DM" when the
 * person shares a 1:1 with the active account, otherwise "in N groups" — with
 * the DM signal winning over the group count.
 */
internal fun deriveRecipientCandidates(
    appState: WhiteNoiseAppState,
    activeAccountIdHex: String?,
): List<RecipientSearch.Candidate> {
    val active = activeAccountIdHex?.trim()?.lowercase(Locale.ROOT)
    // Order is fixed by first appearance while walking the recency-sorted chat
    // list, so a LinkedHashSet preserves the recency order for the browse list.
    val order = LinkedHashSet<String>()
    val inDm = HashSet<String>()
    val groupIdsByHex = HashMap<String, MutableSet<String>>()

    fun note(
        rawHex: String?,
        dm: Boolean,
        groupId: String?,
    ) {
        val hex = rawHex?.trim()?.lowercase(Locale.ROOT) ?: return
        if (hex.isEmpty()) return
        if (active != null && hex == active) return
        order.add(hex)
        if (dm) inDm.add(hex)
        if (groupId != null) {
            groupIdsByHex.getOrPut(hex) { LinkedHashSet() }.add(groupId)
        }
    }
    // Most-recent-first so recency wins the first-appearance ordering above.
    val items = appState.chatListItems.sortedByDescending { it.latestAt ?: 0uL }
    for (item in items) {
        val dm = GroupProjector.isDm(item.memberCount, item.group.name)
        val groupId = item.id.takeUnless { dm }
        // Group rosters give the members. A DM's roster, though, often holds only
        // the active account — the counterpart isn't an enumerable member — so
        // also take the resolved DM counterpart and the latest message's sender
        // (the recent-sender source) to surface DM partners.
        item.memberSnapshot?.members?.forEach { note(it.memberIdHex, dm = dm, groupId = groupId) }
        note(item.otherMemberAccount, dm = dm, groupId = groupId)
        note(item.latest?.sender, dm = dm, groupId = groupId)
        note(item.group.welcomerAccountIdHex, dm = dm, groupId = groupId)
    }
    return order.map { hex ->
        val source =
            when {
                hex in inDm -> RecipientSearch.Source.InDm
                else -> RecipientSearch.Source.InGroups(groupIdsByHex[hex]?.size ?: 0)
            }
        RecipientSearch.Candidate(
            accountIdHex = hex,
            displayName = appState.displayName(hex),
            npub = appState.npub(hex),
            source = source,
        )
    }
}

internal fun canSubmitNewChatSheet(
    directMessage: Boolean,
    busy: Boolean,
    pendingRecipient: String,
    groupName: String,
): Boolean =
    !busy &&
        if (directMessage) {
            pendingRecipient.isNotBlank()
        } else {
            groupName.isNotBlank()
        }

internal fun newChatMemberRefs(
    directMessage: Boolean,
    normalizedPendingRecipients: List<String>,
    initialMemberRefs: List<String> = emptyList(),
): List<String> =
    if (directMessage) {
        normalizedPendingRecipients.distinct().take(1)
    } else {
        initialMemberRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }

/**
 * Display state for the recipient profile-preview card shown below the
 * new-chat / add-member input (issue #631). The card lets the user visually
 * confirm "this is the right person" before inviting them — a wrong invite can
 * leak group history to a stranger.
 *
 * Pulled out as a pure sealed enum + mapper (no Compose deps) so the
 * input→state decision is unit-testable and pinned against regression, the
 * same way [canSubmitNewChatSheet] / [canInviteFromEmptyGroup] are.
 */
internal sealed interface RecipientPreviewState {
    /** No input yet — render nothing. */
    data object Empty : RecipientPreviewState

    /**
     * The identifier is being resolved (a NIP-05 `/.well-known` lookup is in
     * flight, or the resolved key's kind:0 is still being fetched). Show a
     * spinner + "Resolving…" text.
     */
    data object Resolving : RecipientPreviewState

    /**
     * Resolved to a real pubkey with published kind:0 metadata — render the
     * full card (avatar, name, NIP-05, bio, npub fragment).
     */
    data object Loaded : RecipientPreviewState

    /**
     * Resolved to a real pubkey but no kind:0 metadata published. Show the npub
     * fragment + a "No profile published" note. The action stays ENABLED — the
     * user may legitimately know an npub without a profile.
     */
    data object NoProfile : RecipientPreviewState

    /** The identifier does not resolve to a pubkey at all. Block the action. */
    data object Invalid : RecipientPreviewState
}

/**
 * Whether [RecipientPreviewState] permits the surrounding action button to be
 * enabled. Loaded and NoProfile are confirmable; Resolving/Invalid are not;
 * Empty passes through. The card's gate is AND-ed into the existing per-surface enable
 * predicates so the button can never fire on an unresolved/invalid identifier
 * (issue #631), while an unparsed plain-text input (Empty) still defers to the
 * surface's own validation.
 */
internal fun recipientPreviewAllowsSubmit(state: RecipientPreviewState): Boolean =
    when (state) {
        RecipientPreviewState.Loaded, RecipientPreviewState.NoProfile -> true
        RecipientPreviewState.Empty -> true
        RecipientPreviewState.Resolving, RecipientPreviewState.Invalid -> false
    }

/**
 * Pure mapper from the raw resolution signals to a [RecipientPreviewState]
 * (issue #631):
 *  - blank input → [RecipientPreviewState.Empty];
 *  - still resolving (NIP-05 lookup / kind:0 fetch in flight) → [RecipientPreviewState.Resolving];
 *  - resolution settled with no pubkey → [RecipientPreviewState.Invalid];
 *  - resolved to a pubkey with metadata → [RecipientPreviewState.Loaded];
 *  - resolved to a pubkey but no metadata → [RecipientPreviewState.NoProfile].
 *
 * @param hasInput whether the trimmed input is non-blank.
 * @param resolving whether an async resolve/fetch is still in flight.
 * @param resolvedHex the resolved 64-char hex pubkey, or null when resolution
 *   has settled without a key (invalid) or hasn't produced one yet.
 * @param hasProfile whether kind:0 metadata with any usable field is present.
 */
internal fun recipientPreviewState(
    hasInput: Boolean,
    resolving: Boolean,
    resolvedHex: String?,
    hasProfile: Boolean,
): RecipientPreviewState =
    when {
        !hasInput -> RecipientPreviewState.Empty
        resolving -> RecipientPreviewState.Resolving
        resolvedHex == null -> RecipientPreviewState.Invalid
        hasProfile -> RecipientPreviewState.Loaded
        else -> RecipientPreviewState.NoProfile
    }

/**
 * What [RecipientPreviewCard] hoists to its host (issue #631): the display
 * [state] AND the resolved hex pubkey the card actually settled on.
 *
 * Hoisting [resolvedHex] (not just the state) is what makes a NIP-05 entry
 * actually submittable: the card resolves `alice@example.com` to a hex pubkey
 * over the network, but the submit path's [RecipientReference.normalize] only
 * accepts npub/profile-link/64-char-hex and CANNOT re-do that lookup. Without
 * the resolved key, a resolved NIP-05 preview enables the button and then the
 * create/invite fails with "valid npub/profile link/hex". The host submits
 * [resolvedHex] directly (the engine accepts a bare hex recipient ref, the
 * same way the card's own resolve calls `accountIdHex(hex)`).
 */
internal data class RecipientResolution(
    val state: RecipientPreviewState,
    val resolvedHex: String?,
) {
    companion object {
        val Empty = RecipientResolution(RecipientPreviewState.Empty, null)
    }
}

/**
 * The recipient ref(s) to actually submit for a new chat / add-member action
 * (issue #631 blocking fix). When the preview card resolved the input to a hex
 * pubkey ([resolvedHex] non-null), submit THAT — it is the authoritative key
 * the user just visually confirmed, and is the only way a NIP-05 entry (which
 * [RecipientReference.normalize] cannot parse) reaches the engine. Otherwise
 * fall back to the caller-supplied [normalizedFallback] (npub/hex tokens the
 * normalize path already produced), preserving the prior multi-token behavior.
 *
 * Returns null when nothing resolvable is available, so the caller surfaces its
 * "valid recipient reference" error instead of submitting a bad ref.
 */
internal fun resolvedRecipientRefs(
    resolvedHex: String?,
    normalizedFallback: List<String>,
): List<String>? =
    when {
        resolvedHex != null -> listOf(resolvedHex)
        normalizedFallback.isNotEmpty() -> normalizedFallback
        else -> null
    }

/**
 * Whether a kind:0-declared NIP-05 ([declaredNip05]) may be rendered with a
 * VERIFIED check next to it (issue #631 blocking fix).
 *
 * A profile can self-assert any `nip05` string in its kind:0 metadata; passing
 * [ProfileFieldValidation.isAcceptableNip05] only proves the string is
 * well-formed, NOT that the domain's `/.well-known/nostr.json` actually maps
 * that name to this pubkey. Showing a check on an unverified self-assertion is
 * actively harmful for this safety checkpoint — it makes the wrong-key /
 * NIP-05-hijack case the card exists to catch HARDER to spot. We only show the
 * check when the declared NIP-05 has been independently resolved (via
 * [Nip05Resolver]) back to the SAME pubkey the card resolved
 * ([resolvedHex]); otherwise the address renders plainly, with no check.
 *
 * @param declaredNip05 the (already syntax-validated) kind:0 `nip05`, or null.
 * @param nip05ResolvedHex the hex the declared NIP-05 resolved to over the
 *   network, or null when that lookup hasn't completed / failed.
 * @param resolvedHex the hex pubkey the card resolved the input to.
 */
internal fun recipientNip05Verified(
    declaredNip05: String?,
    nip05ResolvedHex: String?,
    resolvedHex: String?,
): Boolean =
    declaredNip05 != null &&
        resolvedHex != null &&
        nip05ResolvedHex != null &&
        nip05ResolvedHex.equals(resolvedHex, ignoreCase = true)

internal fun canInviteFromEmptyGroup(
    isSelfMember: Boolean,
    isSelfAdmin: Boolean,
    membersLoaded: Boolean,
    memberCount: Int,
): Boolean =
    isSelfMember &&
        isSelfAdmin &&
        membersLoaded &&
        memberCount == 1

/**
 * Whether the pubkey [resolvedHex] the Add Member preview settled on is already
 * in the group's roster ([memberHexes]) — the cheap, source-of-truth pre-check
 * for issue #899. MLS rejects the add commit with a raw
 * `DuplicateSignatureKey` when the proposed member already holds a seat (the
 * common case), so we catch it here against the group's own member records
 * rather than letting the user fire a doomed invite and read a Rust enum path.
 *
 * Comparison is case-insensitive on the hex, matching every other roster check
 * in this file. Returns false for a null/blank resolved key (nothing to add
 * yet) so the gate never blocks an unresolved identifier on this basis.
 */
internal fun groupContainsResolvedMember(
    memberHexes: List<String>,
    resolvedHex: String?,
): Boolean {
    val target = resolvedHex?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return memberHexes.any { it.equals(target, ignoreCase = true) }
}
