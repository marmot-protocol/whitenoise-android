package dev.ipf.whitenoise.android.media

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.openNativeAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import java.io.File
import java.util.UUID

/** Carries only generated fixture identities across two independently launched Android instrumentation processes. */
internal object RestartAttachmentRetentionProbe {
    /** Selects only a fresh generated session or the canonical UUID session explicitly requested for readback. */
    fun createRoot(
        context: Context,
        role: String?,
        session: String?,
    ): File {
        if (role == null) {
            return File(context.cacheDir, "controlled-attachment-${UUID.randomUUID()}").apply { check(mkdir()) }
        }
        require(role in setOf("prepare", "read"))
        val validated = UUID.fromString(requireNotNull(session)).toString()
        require(session == validated)
        return File(context.filesDir, "controlled-attachment-restart-$validated").also {
            if (role == "prepare") check(it.mkdir()) else check(it.isDirectory)
        }
    }

    /** Persists the synthetic receipt privately; the runner force-stops only the isolated fixture package. */
    fun prepare(
        root: File,
        received: AttachmentTransferRequest,
        sent: AttachmentTransferRequest,
        controller: Boolean,
    ) {
        val manifest =
            JSONObject()
                .put("schema", 1)
                .put("prepare_pid", Process.myPid())
                .put("controller", controller)
                .put("received", received.toJson())
                .put("sent", sent.toJson())
        File(root, "restart-fixture.json").writeText(manifest.toString())
        report(JSONObject().put("phase", "fixture-stage").put("stage", "android-process-prepared"))
    }

    /** Verifies both directions through native leases in a new process with HTTP acquisition unavailable. */
    suspend fun read(
        context: Context,
        root: File,
        marmot: Marmot,
        accounts: MutableList<String>,
    ) {
        check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
        val manifest = JSONObject(File(root, "restart-fixture.json").readText())
        check(manifest.getInt("schema") == 1)
        val previousPid = manifest.getInt("prepare_pid")
        assertNotEquals("fixture did not cross an Android process boundary", previousPid, Process.myPid())
        val received = request(manifest.getJSONObject("received"))
        val sent = request(manifest.getJSONObject("sent"))
        accounts += listOf(received.accountRef, sent.accountRef)
        val reference = publishedReference(marmot, received)
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val state =
            withContext(Dispatchers.Main.immediate) {
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore(RestartDrafts),
                    accountIdHexResolver = { null },
                    accounts = emptyList(),
                    activeAccountRef = received.accountRef,
                    initialMarmotRuntime = AppMarmotRuntime(root.absolutePath, marmot),
                )
            }
        try {
            ControlledAttachmentProbe.assertNoAndroidCache(state, received)
            ControlledAttachmentProbe.measure("received-native-reopen-unavailable-endpoint") {
                readReceived(state, received, reference, bytes)
            }
            repeat(10) { index ->
                if (index > 0) {
                    ControlledAttachmentProbe.assertNoAndroidCache(state, received)
                    readReceived(state, received, reference, bytes)
                }
                state.openNativeAttachment(sent).use { local ->
                    assertArrayEquals(bytes, requireNotNull(local).toByteArray())
                }
            }
            report(
                JSONObject()
                    .put("phase", "genuine-native-send-retention")
                    .put("available", true)
                    .put("exact_native_lease_bytes", true)
                    .put("native_runtime_reopen_exact_bytes", true)
                    .put("android_send_controller_qualified", manifest.getBoolean("controller"))
                    .put("process_restart_offline_qualified", true)
                    .put("retained_reads_per_direction_after_process_restart", 10),
            )
        } finally {
            state.mutationsScope.cancel()
        }
    }

    /** Ordinary received reads cannot invent a retry permission or substitute seeded retained data. */
    private suspend fun readReceived(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        bytes: ByteArray,
    ) {
        state.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false).use {
            assertArrayEquals(bytes, it.toByteArray())
        }
    }

    /** The restored native history, rather than the private receipt, supplies the attachment reference. */
    private suspend fun publishedReference(
        marmot: Marmot,
        request: AttachmentTransferRequest,
    ): MediaAttachmentReferenceFfi {
        val page =
            (
                marmot.attachmentHistoryPage(request.accountRef, request.groupIdHex, 100u, null)
                    as AttachmentPageReadFfi.Page
            ).page
        return try {
            val entry = page.entries.single { it.messageIdHex == request.messageIdHex }
            (entry.attachment as MediaAttachmentOutcomeFfi.Accepted).reference
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
    }

    /** Keeps generated source/display identity distinct across the process boundary. */
    private fun AttachmentTransferRequest.toJson() =
        JSONObject()
            .put("account", accountRef)
            .put("group", groupIdHex)
            .put("message", messageIdHex)
            .put("source", requireNotNull(sourceMessageIdHex))
            .put("index", attachmentIndex)

    /** Reads only the receipt created under this explicitly generated session directory. */
    private fun request(value: JSONObject) =
        AttachmentTransferRequest(
            value.getString("account"),
            value.getString("group"),
            value.getString("message"),
            value.getInt("index"),
            value.getString("source"),
        )

    /** Reports closed qualification facts while keeping the synthetic identities on device only. */
    private fun report(value: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("controlled_attachment_json", value.toString()) },
        )
    }

    /** The generated session has no persisted user draft. */
    private object RestartDrafts : DraftPersistence {
        /** Never reads installed draft storage. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards only generated-fixture draft writes. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
