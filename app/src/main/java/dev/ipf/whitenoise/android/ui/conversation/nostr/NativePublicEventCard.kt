package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.PublicEventPreviewStateFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState.Loaded
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** MDK owns account-scoped persistence, signature verification, deletion and refresh policy. */
internal suspend fun WhiteNoiseAppState.readPublicEventCard(
    accountRef: String?,
    reference: String,
    refresh: Boolean,
): NostrEventCardState {
    if (accountRef == null) return NostrEventCardState.Failed
    return marmotIo {
        val preview =
            if (refresh) {
                resolvePublicEventPreview(accountRef, reference)
            } else {
                cachedPublicEventPreviews(accountRef, listOf(reference)).single()
            }
        when (preview.state) {
            PublicEventPreviewStateFfi.PRESENT ->
                preview.eventJson
                    ?.let { NostrEvent.fromJson(JSONObject(it)) }
                    ?.let { event ->
                        val author =
                            try {
                                userProfile(event.pubkey)?.toEventAuthorMetadata()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        NostrEventCardState.Loaded(event.toCardModel().copy(authorMetadata = author))
                    }
                    ?: NostrEventCardState.Invalid
            PublicEventPreviewStateFfi.DELETED,
            PublicEventPreviewStateFfi.MISSING,
            -> NostrEventCardState.NotFound
            PublicEventPreviewStateFfi.UNSUPPORTED -> NostrEventCardState.Invalid
        }
    }
}

/** Enrichment follows publication of the verified event, and MDK persists the profile. */
internal suspend fun WhiteNoiseAppState.enrichPublicEventCardAuthor(loaded: Loaded): Loaded {
    val relays = publicEventCardRelays()
    val author =
        marmotIo {
            refreshProfile(loaded.card.authorPubkeyHex, relays)
            userProfile(loaded.card.authorPubkeyHex)?.toEventAuthorMetadata()
        }
    return if (author == null) loaded else loaded.copy(card = loaded.card.copy(authorMetadata = author))
}

private fun UserProfileMetadataFfi.toEventAuthorMetadata(): NostrEventAuthorMetadata =
    NostrEventAuthorMetadata(
        displayName = ProfileSanitizer.displayName(displayName) ?: ProfileSanitizer.displayName(name),
        pictureUrl = ProfileSanitizer.protocolImageUrl(picture),
    )
