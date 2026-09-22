package dev.ipf.whitenoise.android.core

/**
 * The title the conversation top bar shows.
 *
 * A direct message prefers the viewer's own private contact nickname. That label is an
 * account-scoped Android preference the engine's prepared title cannot know about, so preferring
 * the prepared title there shows the network profile name over the name the user chose (#2766).
 * Clearing the nickname falls straight back to the prepared title and then to the app's own
 * projection, which ends in the short-identifier fallback.
 *
 * [dmPeerAccountIdHex] is null for every conversation whose title is not a peer's name — groups,
 * pending invites and an unresolved roster — so those keep their prepared titles untouched.
 * [contactNickname] is the account-scoped lookup, and [projectedTitle] is evaluated only when
 * neither a nickname nor a prepared title is available.
 */
internal fun conversationHeaderTitle(
    dmPeerAccountIdHex: String?,
    contactNickname: (String) -> String?,
    preparedTitle: String?,
    projectedTitle: () -> String,
): String {
    val nickname =
        dmPeerAccountIdHex
            ?.takeIf { it.isNotBlank() }
            ?.let(contactNickname)
            ?.let(ProfileSanitizer::displayName)
    return nickname ?: preparedTitle ?: projectedTitle()
}
