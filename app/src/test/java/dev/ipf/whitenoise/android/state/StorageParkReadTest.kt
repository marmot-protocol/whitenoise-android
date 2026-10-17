package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * A surface entered after MDK parked its body for storage asks MDK once whether the park still holds (#3289), so it
 * can restore the reader's download. The read must never demand anything and must stay one snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageParkReadTest {
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    /** A body MDK holds parked for storage reads as parked, from one snapshot and no demand. */
    @Test
    fun aParkedBodyReadsAsParkedWithoutDemandingAnything() =
        runBlocking {
            val state = appState { snapshot(AttachmentTransferStateFfi.STORAGE_BLOCKED) }

            assertTrue(state.nativeAttachmentParkedForStorage(REQUEST))
            assertEquals(listOf("attachmentTransferSnapshot"), calls)
        }

    /** Any other phase, such as a finished or failed body, is not a park. */
    @Test
    fun otherPhasesAreNotAPark() =
        runBlocking {
            for (phase in listOf(AttachmentTransferStateFfi.READY, AttachmentTransferStateFfi.FAILED)) {
                assertFalse(phase.name, appState { snapshot(phase) }.nativeAttachmentParkedForStorage(REQUEST))
            }
        }

    /** Without a known native target nothing is read, so an idle tile never pages through attachment history. */
    @Test
    fun anUnresolvedTargetReadsNothing() =
        runBlocking {
            val state = appState { snapshot(AttachmentTransferStateFfi.STORAGE_BLOCKED) }

            assertFalse(state.nativeAttachmentParkedForStorage(REQUEST.copy(sourceMessageIdHex = null)))
            assertTrue(calls.isEmpty())
        }

    /** A read that fails is not a park, so the surface simply offers Download as before. */
    @Test
    fun aFailedReadIsNotAPark() =
        runBlocking {
            val state = appState { error("snapshot unavailable") }

            assertFalse(state.nativeAttachmentParkedForStorage(REQUEST))
        }

    /** One signed-in account whose runtime answers the snapshot with [answer]; anything else is unexpected. */
    private fun appState(answer: () -> AttachmentTransferSnapshotFfi) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { it },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT,
                        accountIdHex = ACCOUNT,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT,
            initialMarmotRuntime =
                AppMarmotRuntime(
                    "test",
                    Proxy.newProxyInstance(
                        MarmotInterface::class.java.classLoader,
                        arrayOf(MarmotInterface::class.java),
                    ) { _, method, _ ->
                        val name = method.name.substringBefore('-')
                        calls += name
                        check(name == "attachmentTransferSnapshot") { "Unexpected native call: $name" }
                        answer()
                    } as MarmotInterface,
                ),
        )

    /** The one-item snapshot MDK returns for a single target. */
    private fun snapshot(state: AttachmentTransferStateFfi) =
        AttachmentTransferSnapshotFfi(listOf(AttachmentTransferStatusFfi(null, state, 0u, 0u, null, null)))

    /** Draft storage that keeps nothing. */
    private object NoDrafts : DraftPersistence {
        /** No drafts are ever restored. */
        override fun read(): Map<String, String> = emptyMap()

        /** Writes are discarded. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT = "alice"
        val REQUEST = AttachmentTransferRequest(ACCOUNT, "group", "d1".repeat(32), 0, "51".repeat(32))
    }
}
