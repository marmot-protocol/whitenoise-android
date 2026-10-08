package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.PollTypeFfi

/** Stored fixture messages enter the real native timeline; no rendered rows or parsed content are fabricated. */
internal suspend fun seedMaestroFixtureMessages(
    native: Marmot,
    account: String,
    peer: String,
    group: String,
    fixture: String,
) {
    require(
        fixture in
            listOf(
                "basic",
                "reader",
                "links",
                "poll-single",
                "poll-multiple",
                "consent",
                "notifications",
                "incoming",
                "reactions",
                "creation",
                "departed",
                "contacts",
                "share-invalid-owned",
                "share-invalid-http",
                "share-partial",
                "share-text",
                "share-external-document",
                "share-external-caption",
                "share-external-multiple",
                "share-external-duplicate",
                "share-external-expired-grant",
                "share-external-denied",
                "share-external-denied-caption",
                "share-external-empty",
                "share-external-empty-caption",
            ),
    )
    native.sendText(account, group, "Generated fixture message")
    if (fixture == "creation") {
        // Declare navigation metadata through MDK, publishing only via the existing loopback bootstrap.
        // This reserved hostname is not a functioning receiver and never proves network delivery.
        val declared = listOf("wss://maestro-inbox.example.invalid")
        val status = native.setAccountInboxRelays(account, declared, native.accountRelayLists(account).nip65.relays)
        check(status.inbox.relays.map { it.trimEnd('/') } == declared)
    }
    when (fixture) {
        "reactions" -> seedMaestroReactions(native, account, peer, group)
        "incoming" -> native.sendText(peer, group, "Generated incoming fixture message")
        "reader" -> native.sendText(account, group, maestroReaderMessage())
        "links" -> native.sendText(account, group, "Maestro link https://example.invalid/maestro")
        "poll-single", "poll-multiple" ->
            native.createPoll(
                account,
                group,
                "Maestro fixture poll",
                listOf("Maestro Tea", "Maestro Coffee", "Maestro Water"),
                if (fixture == "poll-single") PollTypeFfi.SINGLE_CHOICE else PollTypeFfi.MULTIPLE_CHOICE,
                null,
            )
    }
}

/** Synthetic long Markdown forces the actual Read More route without fetching external content. */
private fun maestroReaderMessage(): String =
    buildString {
        appendLine("# Maestro reader heading")
        repeat(20) { index ->
            appendLine()
            appendLine("Maestro paragraph $index contains synthetic text for scrolling the expanded message.")
        }
        appendLine()
        appendLine("Maestro reader ending")
    }
