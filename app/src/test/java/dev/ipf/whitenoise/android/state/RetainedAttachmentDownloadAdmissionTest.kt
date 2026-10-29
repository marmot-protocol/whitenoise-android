package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.qualifiedRequest
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the shipping presentation owner: local reads never manufacture durable transfer demand. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetainedAttachmentDownloadAdmissionTest {
    private val dispatcher = StandardTestDispatcher()
    private val request = qualifiedRequest()
    private lateinit var preferences: RecordingPreferences
    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var presentation: ConversationAttachmentPresentation

    /** Installs the real native/cache fixture and records interactive-intent writes before any scheduler runs. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val delegate = context.getSharedPreferences("retained-download-admission", Context.MODE_PRIVATE)
        delegate.edit().clear().commit()
        preferences = RecordingPreferences(delegate)
        fixture = MediaDownloadIntegrationFixture(preferences = preferences)
        presentation =
            ConversationAttachmentPresentation(
                fixture.state,
                request.accountRef,
                request.groupIdHex,
                sourceMessageId = { request.sourceMessageIdHex },
            )
        preferences.interactiveWrites.clear()
    }

    /** Releases only the synthetic owner's work and cache. */
    @After
    fun tearDown() {
        presentation.dispose()
        fixture.close()
        Dispatchers.resetMain()
    }

    /** A ready thumbnail's retained bytes do not start and immediately retire a download job on viewer entry. */
    @Test
    fun memoryHitNeverCreatesInteractiveIntent() =
        runTest(dispatcher) {
            val expected = byteArrayOf(1, 2, 3)
            fixture.state.cacheMediaPlaintext(request.cacheKey(), expected)

            assertArrayEquals(expected, read())

            assertNoNewDownload()
        }

    /** A hydrated encrypted cache hit has the same admission rule as a memory hit. */
    @Test
    fun diskHitNeverCreatesInteractiveIntent() =
        runTest(dispatcher) {
            val expected = byteArrayOf(4, 5, 6)
            fixture.disk.put(request.cacheKey(), expected)

            assertArrayEquals(expected, read())

            assertNoNewDownload()
        }

    /** Canonical native retention is local availability too, even without either Android cache tier. */
    @Test
    fun nativeRetainedHitNeverCreatesInteractiveIntent() =
        runTest(dispatcher) {
            val expected = byteArrayOf(7, 8, 9)
            fixture.retainNatively(0, expected)
            preferences.interactiveWrites.clear()

            assertArrayEquals(expected, read())

            assertTrue(fixture.calls.isEmpty())
            assertFalse(preferences.interactiveWrites.any { it.isNotEmpty() })
            assertEquals(0, fixture.explicitDemands.get())
        }

    /** A true miss records durable interactive demand before the foreground native acquisition can suspend. */
    @Test
    fun cacheMissPersistsIntentBeforeAcquisitionAndClearsItAfterSuccess() =
        runTest(dispatcher) {
            val result = async { read() }
            val pending = fixture.entered.receive()
            assertTrue(preferences.interactiveWrites.any { it.isNotEmpty() })
            assertTrue(fixture.state.hasInteractive(request))
            assertFalse(result.isCompleted)

            val expected = byteArrayOf(10, 11)
            pending.succeed(expected)
            assertArrayEquals(expected, result.await())
            assertFalse(fixture.state.hasInteractive(request))
            assertEquals(1, fixture.calls.size)
        }

    /** Leaving a viewer during a real fetch does not revoke the durable work accepted for that tap. */
    @Test
    fun cancellingAMissingReadKeepsInteractiveRecoveryIntent() =
        runTest(dispatcher) {
            val result = async { read() }
            fixture.entered.receive()
            assertTrue(fixture.state.hasInteractive(request))

            result.cancelAndJoin()

            assertTrue(fixture.state.hasInteractive(request))
            assertEquals(1, fixture.calls.size)
        }

    /** A foreground acquisition failure leaves the same durable intent available for recovery. */
    @Test
    fun failingAMissingReadKeepsInteractiveRecoveryIntent() =
        runTest(dispatcher) {
            val result = async { runCatching { read() } }
            val pending = fixture.entered.receive()
            pending.observed.await()
            val failure = java.io.IOException("synthetic fetch failure")
            pending.fail(failure)

            val received = result.await().exceptionOrNull()
            assertTrue(received is java.io.IOException)
            assertEquals(failure.message, received?.message)
            assertTrue(fixture.state.hasInteractive(request))
            assertEquals(1, fixture.calls.size)
        }

    /** Uses the actual byte-returning viewer path, rather than testing a detached cache selector. */
    private suspend fun read(): ByteArray =
        presentation.downloadAttachment(
            request.messageIdHex,
            request.attachmentIndex,
            reference(0),
            AttachmentDownloadPriority.Interactive,
        )

    /** Rejects the transient true/false intent churn even if the scheduler cannot run in this fixture. */
    private fun assertNoNewDownload() {
        assertFalse(preferences.interactiveWrites.any { it.isNotEmpty() })
        assertTrue(fixture.calls.isEmpty())
        assertFalse(fixture.state.hasInteractive(request))
    }

    /** Observes writes at the real preferences boundary, including a briefly admitted then cleared request. */
    private class RecordingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        val interactiveWrites = mutableListOf<Set<String>>()

        /** Keeps the real editor's behavior while recording the closed interactive-intent key. */
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                /** Records each interactive-identity set written, then forwards the write unchanged. */
                override fun putStringSet(
                    key: String?,
                    values: Set<String>?,
                ): SharedPreferences.Editor {
                    if (key == "attachment_download_interactive_identities") {
                        interactiveWrites.add(values.orEmpty().toSet())
                    }
                    editor.putStringSet(key, values)
                    return this
                }
            }
        }
    }
}
