package dev.ipf.whitenoise.android.fuzz

import dev.ipf.whitenoise.android.amber.SignerOp
import dev.ipf.whitenoise.android.amber.parseActivityResult
import dev.ipf.whitenoise.android.amber.parseContentRow
import dev.ipf.whitenoise.android.amber.signedEventPubkey
import dev.ipf.whitenoise.android.amber.signedEventPubkeyMismatchReason
import dev.ipf.whitenoise.android.amber.signerPackageEchoMismatchReason
import dev.ipf.whitenoise.android.amber.trustedSignerPackageFailureReason
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.RecipientReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.core.nostr.NostrEventVerifier
import dev.ipf.whitenoise.android.core.nostr.NostrRelayFrames
import dev.ipf.whitenoise.android.updates.ZapstoreEvents
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Replays checked-in synthetic fuzz corpora through production parser seams. */
object FuzzSyntheticCorpusReplay {
    private val CORPUS_TARGET_DIRS =
        listOf(
            "fuzzZapstoreProtocol",
            "fuzzIdentityReference",
            "fuzzNip55SignerProtocol",
        )

    private const val MAX_STRING_BYTES = 65_536

    enum class Suite(
        internal val targetDir: String,
        internal val subtargetCount: Int,
        internal val acceptsSubtarget: (Int) -> Boolean,
    ) {
        NostrEventVerifier("fuzzZapstoreProtocol", 3, { it == 0 }),
        ZapstoreEvents("fuzzZapstoreProtocol", 3, { it == 0 }),
        ZapstoreReleaseClient("fuzzZapstoreProtocol", 3, { it == 1 || it == 2 }),
        ProfileLink("fuzzIdentityReference", 4, { it == 0 }),
        RecipientReference("fuzzIdentityReference", 4, { it in 1..3 }),
        Nip55SignerParsing("fuzzNip55SignerProtocol", 3, { true }),
    }

    fun replaySuite(suite: Suite) {
        val seeds = seedsForSuite(suite)
        check(seeds.isNotEmpty()) { "No synthetic fuzz corpus seeds found for $suite" }
        seeds.forEach { seed ->
            replaySeed(suite, seed.fileName, seed.bytes)
        }
    }

    fun seedsForSuite(suite: Suite): List<CorpusSeed> =
        allSeeds().filter { seed ->
            seed.targetDir == suite.targetDir &&
                suite.acceptsSubtarget(seed.subtargetId(suite.subtargetCount))
        }

    fun allSeeds(): List<CorpusSeed> {
        val loader = FuzzSyntheticCorpusReplay::class.java
        return CORPUS_TARGET_DIRS
            .flatMap { targetDir ->
                val rootUrl =
                    loader.getResource(targetDir)
                        ?: loader.getResource("/$targetDir")
                        ?: error("Missing fuzz synthetic corpus directory: $targetDir")
                val rootPath = Paths.get(rootUrl.toURI())
                Files
                    .walk(rootPath)
                    .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".input") }
                    .map { path ->
                        CorpusSeed(
                            targetDir = targetDir,
                            relativePath = "$targetDir/${rootPath.relativize(path)}".replace('\\', '/'),
                            fileName = path.fileName.toString(),
                            bytes = Files.readAllBytes(path),
                        )
                    }.use { stream -> stream.toList() }
            }.sortedBy { it.relativePath }
    }

    internal fun replaySeed(
        suite: Suite,
        fileName: String,
        rawBytes: ByteArray,
    ) {
        val payload = EndBytePayload(rawBytes)
        val subtarget = payload.consumeSubtarget(suite.subtargetCount)
        when (suite) {
            Suite.NostrEventVerifier -> replayNostrEventJson(payload)
            Suite.ZapstoreEvents -> replayZapstoreEvents(payload)
            Suite.ZapstoreReleaseClient -> replayZapstoreReleaseClient(subtarget, payload, fileName)
            Suite.ProfileLink -> replayProfileLink(payload)
            Suite.RecipientReference -> replayRecipientReference(subtarget, payload, fileName)
            Suite.Nip55SignerParsing -> replayNip55SignerParsing(subtarget, payload, fileName)
        }
    }

    private fun replayNostrEventJson(payload: EndBytePayload) {
        val jsonText = payload.remainingUtf8FromStart()
        if (jsonText.isNotBlank()) {
            runCatching { JSONObject(jsonText) }
                .getOrNull()
                ?.let { NostrEvent.fromJson(it) }
                ?.let(NostrEventVerifier::verifies)
        }
    }

    private fun replayZapstoreEvents(payload: EndBytePayload) {
        val jsonText = payload.remainingUtf8FromStart()
        if (jsonText.isNotBlank()) {
            runCatching { JSONObject(jsonText) }
                .getOrNull()
                ?.let { NostrEvent.fromJson(it) }
                ?.takeIf(NostrEventVerifier::verifies)
                ?.let { parsed ->
                    ZapstoreEvents.latestReleaseVersion(parsed, "org.parres.darkmatter", parsed.pubkey)
                    ZapstoreEvents.releaseVersionForApp(parsed, "org.parres.darkmatter")
                }
        }
    }

    private fun replayZapstoreReleaseClient(
        subtarget: Int,
        payload: EndBytePayload,
        fileName: String,
    ) {
        when (subtarget) {
            1 -> replayRelayEnvelopeFrame(payload)
            2 -> replayRelayEnvelopeSequence(payload)
            else -> error("Unhandled fuzz subtarget $subtarget for ${Suite.ZapstoreReleaseClient}: $fileName")
        }
    }

    private fun replayRelayEnvelopeFrame(payload: EndBytePayload) {
        val direct = payload.consumeBooleanFromEnd()
        if (direct) {
            val frameText = payload.remainingUtf8FromStart().trim()
            if (frameText.isNotBlank()) {
                NostrRelayFrames.parseMessage(frameText)?.let { message ->
                    NostrRelayFrames.parseEventForSubscription(message, "dm-update-test")
                    NostrRelayFrames.frameType(message)
                    NostrRelayFrames.isTerminalForSubscription(message, "dm-update-test")
                }
            }
        }
    }

    private fun replayRelayEnvelopeSequence(payload: EndBytePayload) {
        val direct = payload.consumeBooleanFromEnd()
        if (!direct) return
        val frames = payload.remainingUtf8FromStart()
        if (frames.isBlank()) return
        val subscriptionId = "dm-update-test"
        frames
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(32)
            .forEach { frameText ->
                val message = NostrRelayFrames.parseMessage(frameText) ?: return@forEach
                NostrRelayFrames.parseEventForSubscription(message, subscriptionId)
                NostrRelayFrames.isTerminalForSubscription(message, subscriptionId)
            }
    }

    private fun replayProfileLink(payload: EndBytePayload) {
        val direct = payload.consumeBooleanFromEnd()
        if (!direct) return
        ProfileLink.parse(payload.remainingUtf8FromStart().trim())
    }

    private fun replayRecipientNormalize(payload: EndBytePayload) {
        val direct = payload.consumeBooleanFromEnd()
        if (!direct) return
        RecipientReference.normalize(payload.remainingUtf8FromStart().trim())
    }

    private fun replayRecipientTokenize(payload: EndBytePayload) {
        val direct = payload.consumeBooleanFromEnd()
        if (!direct) return
        RecipientReference.tokenize(payload.remainingUtf8FromStart().trim())
    }

    private fun replayPlausibleClipboard(payload: EndBytePayload) {
        val field = payload.consumeDirectOrFramedString()
        RecipientReference.plausibleClipboardInput(
            field.value.ifBlank { null },
            allowHexPublicKey = false,
        )
    }

    private fun replayRecipientReference(
        subtarget: Int,
        payload: EndBytePayload,
        fileName: String,
    ) {
        when (subtarget) {
            1 -> replayRecipientNormalize(payload)
            2 -> replayRecipientTokenize(payload)
            3 -> replayPlausibleClipboard(payload)
            else -> error("Unhandled fuzz subtarget $subtarget for ${Suite.RecipientReference}: $fileName")
        }
    }

    private fun replayParseContentRow(payload: EndBytePayload) {
        val direct = payload.remainingUtf8FromStart()
        val parts = direct.split('|', limit = 4)
        val op = parts.firstOrNull()?.let { type -> SignerOp.entries.firstOrNull { it.intentType == type } } ?: return
        if (parts.size != 4) return
        parseContentRow(
            op = op,
            rejected = parts[1] == "1",
            resultColumn = parts[2].ifBlank { null },
            eventColumn = parts[3].ifBlank { null },
        )
    }

    private fun replayParseActivityResult(payload: EndBytePayload) {
        val direct = payload.remainingUtf8FromStart()
        val parts = direct.split('|', limit = 6)
        val op = parts.firstOrNull()?.let { type -> SignerOp.entries.firstOrNull { it.intentType == type } } ?: return
        if (parts.size != 6) return
        parseActivityResult(
            op = op,
            resultOk = parts[1] == "1",
            rejected = parts[2] == "1",
            resultExtra = parts[3].ifBlank { null },
            eventExtra = parts[4].ifBlank { null },
            packageExtra = parts[5].ifBlank { null },
        )
    }

    private fun replaySignedEventPubkeyHelpers(payload: EndBytePayload) {
        val eventField = payload.consumeDirectOrFramedString()
        val eventJson = eventField.value
        val expectedPubkey =
            if (eventField.consumedAllRemaining) {
                ""
            } else {
                payload.consumeFramedString().ifBlank { "" }
            }
        val expectedPackageName =
            if (eventField.consumedAllRemaining) {
                "com.example.signer"
            } else {
                payload.consumeOptionalFramedString()?.takeIf { it.isNotBlank() } ?: "com.example.signer"
            }
        val handledPackage = if (eventField.consumedAllRemaining) null else payload.consumeOptionalFramedString()
        val echoedPackage = if (eventField.consumedAllRemaining) null else payload.consumeOptionalFramedString()

        signedEventPubkey(eventJson)
        signedEventPubkeyMismatchReason(eventJson, expectedPubkey)
        if (!echoedPackage.isNullOrBlank()) {
            signerPackageEchoMismatchReason(echoedPackage, expectedPackageName)
        }
        trustedSignerPackageFailureReason(handledPackage, expectedPackageName)
        if (!handledPackage.isNullOrBlank()) {
            trustedSignerPackageFailureReason(handledPackage, echoedPackage)
        }
    }

    private fun replayNip55SignerParsing(
        subtarget: Int,
        payload: EndBytePayload,
        fileName: String,
    ) {
        when (subtarget) {
            0 -> replayParseContentRow(payload)
            1 -> replayParseActivityResult(payload)
            2 -> replaySignedEventPubkeyHelpers(payload)
            else -> error("Unhandled fuzz subtarget $subtarget for ${Suite.Nip55SignerParsing}: $fileName")
        }
    }

    data class CorpusSeed(
        val targetDir: String,
        val relativePath: String,
        val fileName: String,
        val bytes: ByteArray,
    ) {
        fun subtargetId(count: Int): Int {
            check(bytes.isNotEmpty()) { "Synthetic fuzz corpus seed has no trailing selector: $relativePath" }
            return (bytes.last().toInt() and 0xFF) % count
        }
    }

    /** Mirrors Jazzer end-byte consumption for replay outside the fuzz harness. */
    private class EndBytePayload(
        private val data: ByteArray,
        private var startIndex: Int = 0,
        private var endExclusive: Int = data.size,
    ) {
        fun consumeSubtarget(count: Int): Int {
            val id = consumeByteFromEnd()
            return id % count
        }

        fun consumeBooleanFromEnd(): Boolean = (consumeByteFromEnd() and 1) != 0

        fun remainingUtf8FromStart(): String =
            remainingBytesFromStart()
                .decodeToString()

        fun consumeDirectOrFramedString(): FramedValue {
            if (remainingBytes() == 0) {
                return FramedValue("", consumedAllRemaining = false)
            }
            val direct = consumeBooleanFromEnd()
            return if (direct) {
                FramedValue(
                    value = remainingBytesFromStart().decodeToString(),
                    consumedAllRemaining = true,
                )
            } else {
                consumeFramedValue()
            }
        }

        fun consumeFramedString(): String = consumeFramedValue().value

        fun consumeOptionalFramedString(): String? =
            if (remainingBytes() == 0) {
                null
            } else {
                consumeFramedString().ifBlank { null }
            }

        private fun consumeFramedValue(): FramedValue {
            if (remainingBytes() == 0) {
                return FramedValue("", consumedAllRemaining = false)
            }
            val tag = consumeByteFromEnd()
            val available = remainingBytesFromStart()
            return when (tag) {
                0 -> FramedValue("", consumedAllRemaining = false)
                0xFF -> {
                    val bytes = remainingBytesFromStart().bounded(MAX_STRING_BYTES)
                    startIndex = endExclusive
                    FramedValue(bytes.decodeToString(), consumedAllRemaining = true)
                }
                else -> {
                    val length = minOf(tag, available.size, MAX_STRING_BYTES)
                    val bytes = data.copyOfRange(startIndex, startIndex + length)
                    startIndex += length
                    FramedValue(
                        value = bytes.decodeToString(),
                        consumedAllRemaining = remainingBytes() == 0,
                    )
                }
            }
        }

        private fun remainingBytesFromStart(): ByteArray {
            val length = endExclusive - startIndex
            return if (length <= 0) {
                ByteArray(0)
            } else {
                data.copyOfRange(startIndex, endExclusive)
            }
        }

        private fun remainingBytes(): Int = endExclusive - startIndex

        private fun consumeByteFromEnd(): Int {
            if (startIndex >= endExclusive) {
                return 0
            }
            return data[--endExclusive].toInt() and 0xFF
        }

        private fun ByteArray.bounded(maxBytes: Int): ByteArray =
            if (size <= maxBytes) {
                this
            } else {
                copyOf(maxBytes)
            }

        private fun ByteArray.decodeToString(): String = String(this, StandardCharsets.UTF_8)

        data class FramedValue(
            val value: String,
            val consumedAllRemaining: Boolean,
        )
    }
}
