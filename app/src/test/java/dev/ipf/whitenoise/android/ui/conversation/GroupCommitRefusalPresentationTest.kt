package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.GroupCommitRefusalCauseFfi
import dev.ipf.marmotkit.GroupCommitRefusedRelayFfi
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The refusal banner's wording: one relay by host, several counted, and "too large" only when every relay said so. */
class GroupCommitRefusalPresentationTest {
    /** A lone relay is named by its lowercased host, with or without the size reason. */
    @Test
    fun oneRelayIsNamedByHostAndSaysWhenTheChangeWasTooLarge() {
        assertEquals(
            GroupCommitRefusalMessage.Named(R.string.group_commit_refused_relay_too_large, "relay.example"),
            groupCommitRefusalMessage(
                listOf(refusal("wss://Relay.Example./path", GroupCommitRefusalCauseFfi.TOO_LARGE)),
            ),
        )
        assertEquals(
            GroupCommitRefusalMessage.Named(R.string.group_commit_refused_relay, "relay.example"),
            groupCommitRefusalMessage(listOf(refusal("wss://relay.example", GroupCommitRefusalCauseFfi.REJECTED))),
        )
    }

    /** Several relays are counted, and mixed causes never blame the change's size. */
    @Test
    fun severalRelaysAreCountedAndOnlyBlameSizeWhenEveryOneDid() {
        val mixed =
            listOf(
                refusal("wss://a.example", GroupCommitRefusalCauseFfi.TOO_LARGE),
                refusal("wss://b.example", GroupCommitRefusalCauseFfi.REJECTED),
            )
        val allLarge = mixed.map { it.copy(cause = GroupCommitRefusalCauseFfi.TOO_LARGE) }

        assertEquals(
            GroupCommitRefusalMessage.Counted(R.plurals.group_commit_refused_relays, 2),
            groupCommitRefusalMessage(mixed),
        )
        assertEquals(
            GroupCommitRefusalMessage.Counted(R.plurals.group_commit_refused_relays_too_large, 2),
            groupCommitRefusalMessage(allLarge),
        )
    }

    /** A relay URL with no readable host falls back to the count instead of naming garbage. */
    @Test
    fun anUnreadableRelayUrlIsCounted() {
        assertEquals(
            GroupCommitRefusalMessage.Counted(R.plurals.group_commit_refused_relays, 1),
            groupCommitRefusalMessage(listOf(refusal("not a relay url", GroupCommitRefusalCauseFfi.REJECTED))),
        )
    }

    /** One refusing relay at [url] for [cause]. */
    private fun refusal(
        url: String,
        cause: GroupCommitRefusalCauseFfi,
    ) = GroupCommitRefusedRelayFfi(relayUrl = url, cause = cause, sizeClass = null)
}
