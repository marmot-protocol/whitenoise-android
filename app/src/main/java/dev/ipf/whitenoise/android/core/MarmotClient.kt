package dev.ipf.whitenoise.android.core

import android.content.Context
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.CursorPersistenceFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Serializes root ownership while a cold preflight opens, reads, and closes its temporary client. */
internal object MarmotClientRootGate {
    private val mutex = Mutex()

    /** Foreground construction publishes its client before a waiting preflight checks for reuse. */
    suspend fun <T> withLease(block: suspend () -> T): T = mutex.withLock { block() }
}

class MarmotClient(
    context: Context,
    relayUrls: List<String> = bootstrapRelays,
) {
    init {
        MarmotAndroid.initialize(context.applicationContext)
    }

    val rootPath: String =
        File(context.filesDir, "Marmot")
            .apply { mkdirs() }
            .absolutePath

    val marmot: Marmot =
        Marmot.newWithConfiguration(
            rootPath = rootPath,
            relayUrls = relayUrls,
            options =
                MarmotOptions(
                    clientName = CLIENT_NAME,
                    cursorPersistence = CursorPersistenceFfi.ADVANCE,
                    attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
                ),
        )

    companion object {
        private const val CLIENT_NAME = "White Noise Android"

        val bootstrapRelays =
            listOf(
                "wss://relay.us.whitenoise.chat",
                "wss://relay.eu.whitenoise.chat",
            )

        // Existing identities may never have published to our messaging relays.
        val discoveryRelays =
            bootstrapRelays +
                listOf(
                    "wss://purplepag.es",
                    "wss://relay.vertexlab.io",
                    "wss://nos.lol",
                    "wss://relay.ditto.pub",
                    "wss://relay.primal.net",
                )

        /**
         * General-purpose relays added to new accounts' NIP-65 (kind 10002) lists. White Noise
         * relays accept only the event kinds White Noise needs, so other Nostr clients need these
         * to publish and read the account's other events.
         */
        val generalPurposeRelays =
            listOf(
                "wss://nos.lol",
                "wss://relay.primal.net",
                "wss://whitenoise.nostrdev.com",
            )

        /** NIP-65 defaults for new accounts and onboarding relay repairs; the inbox list keeps [bootstrapRelays]. */
        val accountRelays = bootstrapRelays + generalPurposeRelays
    }
}
