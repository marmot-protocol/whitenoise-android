package dev.ipf.whitenoise.android.core

import android.content.Context
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.CursorPersistenceFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import java.io.File

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
    }
}
