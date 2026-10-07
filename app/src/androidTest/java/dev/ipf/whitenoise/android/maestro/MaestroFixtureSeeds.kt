package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.PollTypeFfi

/** Stored fixture messages enter the real native timeline; no rendered rows or parsed content are fabricated. */
internal suspend fun seedMaestroFixtureMessages(
    native: Marmot,
    account: String,
    group: String,
    fixture: String,
) {
    require(fixture in listOf("basic", "reader", "links", "poll-single", "poll-multiple", "consent"))
    native.sendText(account, group, "Generated fixture message")
    when (fixture) {
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
