package dev.ipf.whitenoise.android.media.sandbox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed production entrypoints, controlled SDK close faults and definitive false bindings; no native parsing. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvStreamSourceOwnershipTest {
    @Test
    fun originalFileCloseRetainsAdmissionUntilAckAndHoldsOnFailure() {
        exercise(staged = false, fails = false)
        exercise(staged = false, fails = true)
    }

    @Test
    fun stagedWriterCloseRetainsAdmissionUntilAckAndHoldsOnFailure() {
        exercise(staged = true, fails = false)
        exercise(staged = true, fails = true)
    }

    // Source acquisition, account retirement and synthetic capability teardown share one fixture.
    @Suppress("LongMethod")
    private fun exercise(
        staged: Boolean,
        fails: Boolean,
    ) {
        val admissions = admissions()
        assertNull(admissions.current(AvStreamLane.Analysis, admissions.epoch()))
        assertNull(admissions.current(AvStreamLane.Playback, admissions.epoch()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextSourceOpened = CountDownLatch(1)
        val actors = mutableSetOf<IsolatedAvStream>()
        val calls = mutableListOf<Thread>()
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                var binds = 0

                override fun getApplicationContext(): Context = this

                override fun unbindService(conn: ServiceConnection) = Unit

                override fun bindIsolatedService(
                    service: Intent,
                    flags: Int,
                    instanceName: String,
                    executor: Executor,
                    conn: ServiceConnection,
                ): Boolean {
                    binds++
                    val owner = checkNotNull(admissions.current(AvStreamLane.Analysis, admissions.epoch()))
                    synchronized(actors) { actors += owner }
                    if (!staged && binds == 1) {
                        val resources = resources(owner)
                        val retained = field(owner, "input") as MediaParentResource<*>
                        val original =
                            held(resources)
                                .map { it.value }
                                .filterIsInstance<ParcelFileDescriptor>()
                                .single { it !== retained.value }
                        installCloser(resources) { value ->
                            if (value === original) holdClose(entered, release, fails)
                            value.close()
                        }
                    }
                    return false // No isolated process or parser ever receives this fixture's input.
                }
            }
        val file =
            File
                .createTempFile(
                    "stream-source-owner-",
                    ".bin",
                ).apply { writeBytes(ByteArray(12) { it.toByte() }) }
        val authority = "stream.source.${if (staged) "staged" else "original"}.${if (fails) "fail" else "block"}"
        var opens = 0
        val provider =
            object : EmptyProvider() {
                override fun openAssetFile(
                    uri: Uri,
                    mode: String,
                ): AssetFileDescriptor {
                    opens++
                    if (opens >= if (staged) 2 else 1) nextSourceOpened.countDown()
                    val owner = checkNotNull(admissions.current(AvStreamLane.Analysis, admissions.epoch()))
                    synchronized(actors) { actors += owner }
                    if (staged && opens == 1) {
                        installCloser(resources(owner)) { value ->
                            if (value is FileOutputStream) holdClose(entered, release, fails)
                            value.close()
                        }
                    }
                    val input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    return AssetFileDescriptor(input, if (staged) 1L else 0L, if (staged) 4L else file.length())
                }
            }
        provider.attachInfo(
            context,
            android.content.pm
                .ProviderInfo()
                .apply { this.authority = authority },
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/source")
        val firstFailure = AtomicReference<Throwable?>()
        try {
            calls +=
                launch(firstFailure) {
                    if (staged) {
                        IsolatedAvStream.openUri(context, uri, false, true, 1, AvStreamLane.Analysis)
                    } else {
                        IsolatedAvStream.openFile(context, file, false, true, 1, AvStreamLane.Analysis)
                    }
                }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val secondFailure = AtomicReference<Throwable?>()
            calls +=
                launch(secondFailure) { IsolatedAvStream.openUri(context, uri, false, true, 1, AvStreamLane.Analysis) }
            assertFalse(
                "No next source may open before the original/staged close ACK",
                nextSourceOpened.await(250, TimeUnit.MILLISECONDS),
            )
            release.countDown()
            calls.first().join(2000)
            assertFalse(calls.first().isAlive)
            assertTrue(firstFailure.get() is IOException)
            if (fails) {
                // The held old owner times out before this second request can acquire any source descriptor.
                calls[1].join(AvStreamProtocol.CALL_DEADLINE_MS + 2000)
                assertFalse(calls[1].isAlive)
                assertTrue(secondFailure.get() is MediaAdmissionFailure)
                assertEquals(if (staged) 1 else 0, opens)
                IsolatedAvStream.retireActive()
                val replacementFailure = AtomicReference<Throwable?>()
                calls +=
                    launch(replacementFailure) {
                        IsolatedAvStream.openUri(context, uri, false, true, 1, AvStreamLane.Analysis)
                    }
                assertFalse(
                    "Account reset cannot erase a failed descriptor close",
                    nextSourceOpened.await(250, TimeUnit.MILLISECONDS),
                )
                val owner = checkNotNull(admissions.current(AvStreamLane.Analysis, admissions.epoch()))
                assertFalse(resources(owner).available())
                assertEquals(if (staged) 1 else 0, opens)
            } else {
                calls[1].join(2000)
                assertFalse(calls[1].isAlive)
                assertTrue(secondFailure.get() is IOException)
                assertTrue(nextSourceOpened.await(2, TimeUnit.SECONDS))
                assertEquals(if (staged) 2 else 1, opens)
            }
        } finally {
            release.countDown()
            calls.forEach {
                it.interrupt()
                it.join(2000)
            }
            // Simulate fixture-process exit only after hold assertions; never reset the failed production ledger.
            synchronized(actors) { actors.toList() }.forEach { owner ->
                val resources = resources(owner)
                installCloser(resources) { it.close() }
                held(resources).forEach { runCatching { it.value.close() } }
                owner.close()
                (field(owner, "callbacks") as android.os.HandlerThread).quitSafely()
                (field(owner, "rpc") as java.util.concurrent.ExecutorService).shutdown()
                (field(owner, "retirement") as java.util.concurrent.ExecutorService).shutdown()
                if (admissions.current(AvStreamLane.Analysis, admissions.epoch()) ===
                    owner
                ) {
                    admissions.release(AvStreamLane.Analysis, owner)
                }
            }
            file.delete()
        }
    }

    private fun holdClose(
        entered: CountDownLatch,
        release: CountDownLatch,
        fails: Boolean,
    ) {
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS))
        if (fails) throw IOException("Synthetic descriptor close failure")
    }

    @Suppress("TooGenericExceptionCaught") // Capture exact controlled startup failures on the fixture thread.
    private fun launch(failure: AtomicReference<Throwable?>, open: () -> IsolatedAvStream): Thread =
        Thread {
            try {
                open().close()
                failure.set(AssertionError("The definitive false binding must not return a stream"))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { start() }

    @Suppress("UNCHECKED_CAST")
    private fun admissions(): AvStreamAdmissions<IsolatedAvStream> {
        val current = field(null, "admissions") as AvStreamAdmissions<IsolatedAvStream>
        return current
    }

    private fun resources(owner: IsolatedAvStream): MediaParentResources {
        val current = field(owner, "parentResources") as MediaParentResources
        return current
    }

    @Suppress("UNCHECKED_CAST")
    private fun held(resources: MediaParentResources): List<MediaParentResource<*>> =
        synchronized(resources) {
            MediaParentResources::class.java
                .getDeclaredField("held")
                .apply { isAccessible = true }
                .get(resources)
                .let { (it as Set<MediaParentResource<*>>).toList() }
        }

    private fun installCloser(
        resources: MediaParentResources,
        closer: (AutoCloseable) -> Unit,
    ) {
        MediaParentResources::class.java
            .getDeclaredField("closer")
            .apply { isAccessible = true }
            .set(resources, closer)
    }

    private fun field(
        owner: IsolatedAvStream?,
        name: String,
    ): Any? =
        IsolatedAvStream::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)

    private abstract class EmptyProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "video/mp4"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
