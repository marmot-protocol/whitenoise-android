package dev.ipf.darkmatter.state

internal data class AutoAcceptedInviteMarker(
    val accountRef: String,
    val groupIdHex: String,
    val invitedAtMs: Long,
    val inviterAccountIdHex: String?,
    val opened: Boolean,
    val bannerDismissed: Boolean,
)

internal data class AutoAcceptedInviteBannerState(
    val inviterAccountIdHex: String?,
)

internal object AutoAcceptedInviteMarkers {
    const val BADGE_TTL_MILLIS: Long = 24L * 60L * 60L * 1_000L

    // Unit Separator is outside the hex identifiers/timestamps/booleans stored
    // here. Keep malformed future fields from corrupting the whole StringSet.
    private const val SEP = "\u001F"

    private fun encode(marker: AutoAcceptedInviteMarker): String? {
        val fields =
            listOf(
                marker.accountRef,
                marker.groupIdHex,
                marker.invitedAtMs.toString(),
                marker.inviterAccountIdHex.orEmpty(),
                marker.opened.toString(),
                marker.bannerDismissed.toString(),
            )
        if (fields.any { SEP in it }) return null
        return fields.joinToString(SEP)
    }

    fun decode(raw: String): AutoAcceptedInviteMarker? {
        val parts = raw.split(SEP, limit = 6)
        if (parts.size != 6) return null
        val accountRef = parts[0].takeIf { it.isNotBlank() } ?: return null
        val groupIdHex = parts[1].takeIf { it.isNotBlank() } ?: return null
        val invitedAtMs = parts[2].toLongOrNull() ?: return null
        return AutoAcceptedInviteMarker(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            invitedAtMs = invitedAtMs,
            inviterAccountIdHex = parts[3].takeIf { it.isNotBlank() },
            opened = parts[4].toBooleanStrictOrNull() ?: false,
            bannerDismissed = parts[5].toBooleanStrictOrNull() ?: false,
        )
    }

    fun markerFor(
        encoded: Set<String>,
        accountRef: String?,
        groupIdHex: String,
    ): AutoAcceptedInviteMarker? {
        val account = accountRef?.takeIf { it.isNotBlank() } ?: return null
        return encoded
            .asSequence()
            .mapNotNull(::decode)
            .firstOrNull { marker ->
                marker.accountRef == account && marker.groupIdHex.equals(groupIdHex, ignoreCase = true)
            }
    }

    fun upsert(
        encoded: Set<String>,
        accountRef: String,
        groupIdHex: String,
        invitedAtMs: Long,
        inviterAccountIdHex: String?,
        opened: Boolean = false,
    ): Set<String> {
        val existing = markerFor(encoded, accountRef, groupIdHex)
        val replacement =
            AutoAcceptedInviteMarker(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                invitedAtMs = invitedAtMs,
                inviterAccountIdHex = inviterAccountIdHex?.takeIf { it.isNotBlank() },
                opened = existing?.opened == true || opened,
                bannerDismissed = existing?.bannerDismissed ?: false,
            )
        val encodedReplacement = encode(replacement) ?: return encoded
        return encoded
            .asSequence()
            .mapNotNull(::decode)
            .filterNot { it.accountRef == accountRef && it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            .mapNotNull(::encode)
            .toSet() + encodedReplacement
    }

    fun markOpened(
        encoded: Set<String>,
        accountRef: String?,
        groupIdHex: String,
    ): Set<String> = update(encoded, accountRef, groupIdHex) { it.copy(opened = true) }

    fun dismissBanner(
        encoded: Set<String>,
        accountRef: String?,
        groupIdHex: String,
    ): Set<String> = update(encoded, accountRef, groupIdHex) { it.copy(bannerDismissed = true) }

    fun remove(
        encoded: Set<String>,
        accountRef: String?,
        groupIdHex: String,
    ): Set<String> {
        val account = accountRef?.takeIf { it.isNotBlank() } ?: return encoded
        return encoded
            .asSequence()
            .mapNotNull(::decode)
            .filterNot { it.accountRef == account && it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            .mapNotNull(::encode)
            .toSet()
    }

    fun badgeVisible(
        marker: AutoAcceptedInviteMarker?,
        nowMs: Long,
    ): Boolean = marker != null && !marker.opened && nowMs - marker.invitedAtMs <= BADGE_TTL_MILLIS

    fun bannerState(marker: AutoAcceptedInviteMarker?): AutoAcceptedInviteBannerState? =
        marker
            ?.takeUnless { it.bannerDismissed }
            ?.let { AutoAcceptedInviteBannerState(inviterAccountIdHex = it.inviterAccountIdHex) }

    private fun update(
        encoded: Set<String>,
        accountRef: String?,
        groupIdHex: String,
        transform: (AutoAcceptedInviteMarker) -> AutoAcceptedInviteMarker,
    ): Set<String> {
        val account = accountRef?.takeIf { it.isNotBlank() } ?: return encoded
        var changed = false
        val updated =
            encoded
                .asSequence()
                .mapNotNull(::decode)
                .map { marker ->
                    if (marker.accountRef == account && marker.groupIdHex.equals(groupIdHex, ignoreCase = true)) {
                        changed = true
                        transform(marker)
                    } else {
                        marker
                    }
                }.mapNotNull(::encode)
                .toSet()
        return if (changed) updated else encoded
    }
}
