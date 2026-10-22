package dev.ipf.whitenoise.android.state

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.MdkAdoptionDeviceAcceptance
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.StartupDiagnostics
import dev.ipf.whitenoise.android.diagnostics.shareStartupDiagnostics
import dev.ipf.whitenoise.android.ui.common.STARTUP_COPY_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.STARTUP_RETRY_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.STARTUP_SHARE_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.StartupFailureScreen
import dev.ipf.whitenoise.android.ui.common.StartupProgressContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/** Real AppState/native/FileProvider integration; the focused workflow builds diagnostics explicitly enabled. */
@ManualDeviceFixture
@MdkAdoptionDeviceAcceptance
@RunWith(AndroidJUnit4::class)
class NativeStartupDiagnosticDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun requireEnabledDiagnosticBuild() {
        check(BuildConfig.STARTUP_DIAGNOSTICS_ENABLED) { "Run the focused MDK adoption diagnostic build" }
        MarmotAndroid.initialize(context)
        StartupDiagnostics.bind(context)
        PerformanceDiagnostics.bind(context)
        PerformanceDiagnostics.stop()
    }

    @Test
    @Suppress("LongMethod") // One held attempt proves Retry, Copy and share without constructing another runtime.
    fun copyAndShareFinishWhileNativeConstructionIsHeld() =
        runBlocking<Unit> {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val fixture =
                Fixture(timeoutMs = 100) {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                }
            try {
                fixture.app.bootstrap()
                withTimeout(10_000) { while (entered.count > 0) delay(10) }
                fixture.app.retryBootstrap()
                assertFalse(fixture.constructed)
                val capture = ShareCapture(compose.activity)
                compose.setContent {
                    val scope = rememberCoroutineScope()
                    WhiteNoiseTheme {
                        val failed = fixture.app.phase as? AppPhase.Failed
                        if (failed == null) {
                            StartupProgressContent()
                        } else {
                            StartupFailureScreen(
                                title = "Held startup fixture",
                                error = failed.error,
                                onShareDiagnostics = { scope.launch { shareStartupDiagnostics(capture) { true } } },
                                onRetry = { scope.launch { fixture.app.retryBootstrap() } },
                            )
                        }
                    }
                }
                compose.onNodeWithTag(STARTUP_RETRY_TEST_TAG).performClick()
                compose.waitUntil(10_000) { fixture.app.phase is AppPhase.Failed }
                compose.onNodeWithTag(STARTUP_COPY_TEST_TAG).performClick()
                val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
                val copied =
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        .toString()
                assertTrue(copied.contains("ROOT_LEASE"))
                compose.onNodeWithTag(STARTUP_SHARE_TEST_TAG).performClick()
                compose.waitUntil(10_000) { capture.started.get() != null }
                val first = report(requireNotNull(capture.started.get()))
                assertTrue(first.decodeToString().contains("native_startup=unavailable"))
                assertFalse(fixture.constructed)
                assertEquals(1, calls.get())
                release.countDown()
                withTimeout(30_000) {
                    while (fixture.app.phase is AppPhase.Failed) delay(50)
                }
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    @Test
    @Suppress("LongMethod") // Actual native work, immutable share files and active/inactive archives form one flow.
    fun publishedNativeRuntimeFeedsSharesAndOnlyActivePerformanceArchives() =
        runBlocking<Unit> {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                Fixture(
                    beforeStart = {
                        entered.complete(Unit)
                        release.await()
                    },
                )
            try {
                val bootstrap = launch { fixture.app.bootstrap() }
                withTimeout(30_000) { entered.await() }
                assertTrue(fixture.native.startupDiagnosticsSnapshot().enabled)
                val held = report(share()).decodeToString()
                assertTrue(held.contains("active stage=MARMOT_START"))
                assertTrue(held.contains("native_startup schema=1 enabled=true"))
                release.complete(Unit)
                bootstrap.join()
                assertEquals(AppPhase.Onboarding, fixture.app.phase)
                val account = fixture.native.createIdentity(listOf(fixture.relay.url), listOf(fixture.relay.url))
                fixture.native.createGroup(account.label, "Native diagnostics", emptyList(), null)
                val native = fixture.native.startupDiagnosticsSnapshot()
                assertTrue(native.enabled)
                assertTrue(native.events.isNotEmpty())
                val firstIntent = share()
                val first = report(firstIntent)
                val text = first.decodeToString()
                assertTrue(text.contains("native_startup schema=1 enabled=true"))
                assertTrue(text.contains("event stage="))
                repeat(2) { report(share()) }
                assertArrayEquals(first, report(firstIntent))
                report(share())
                val copies = File(context.cacheDir, "startup_diagnostics").listFiles().orEmpty()
                assertEquals(3, copies.count { it.name.startsWith("startup-") })

                assertTrue(PerformanceDiagnostics.start().active)
                val expected =
                    fixture.native
                        .appPerformanceSnapshot()
                        .appStart.attempts
                assertTrue(expected > 0uL)
                val archive = requireNotNull(fixture.app.prepareAuditLogArchiveForExport())
                ZipFile(archive).use { zip ->
                    val entry = requireNotNull(zip.getEntry("native-performance-snapshot.txt"))
                    val performance = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    assertTrue(performance.contains("scope=process_cumulative selected_operations=true"))
                    val attempts =
                        Regex("(?m)^app_start attempts=(\\d+)").find(performance)!!.groupValues[1].toULong()
                    assertTrue(attempts >= expected)
                }
                PerformanceDiagnostics.stop()
                fixture.app.prepareAuditLogArchiveForExport()?.let { inactive ->
                    ZipFile(inactive).use { assertEquals(null, it.getEntry("native-performance-snapshot.txt")) }
                }
            } finally {
                release.complete(Unit)
                PerformanceDiagnostics.stop()
                fixture.close()
            }
        }

    @Test
    fun failedStartClosesAndUnbindsNativeCaptureBeforeSharingTheHostFailure() =
        runBlocking<Unit> {
            val fixture = Fixture(failStart = true)
            try {
                fixture.app.bootstrap()
                assertTrue(fixture.app.phase is AppPhase.Failed)
                assertEquals(1, fixture.closeCalls)
                val text = report(share()).decodeToString()
                assertTrue(text.contains("failed_stage=MARMOT_START"))
                assertTrue(text.contains("native_startup=unavailable"))
                assertFalse(text.contains(PRIVATE_FAILURE))
                assertFalse(text.contains(fixture.root.path))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun constructionFailureStillSharesHostEvidenceWithoutANativeHandle() =
        runBlocking<Unit> {
            val fixture = Fixture { error(PRIVATE_FAILURE) }
            try {
                fixture.app.bootstrap()
                assertTrue(fixture.app.phase is AppPhase.Failed)
                assertFalse(fixture.constructed)
                val text = report(share()).decodeToString()
                assertTrue(text.contains("native_startup=unavailable"))
                assertFalse(text.contains(PRIVATE_FAILURE))
            } finally {
                fixture.close()
            }
        }

    private suspend fun share(): Intent {
        val capture = ShareCapture(compose.activity)
        withContext(Dispatchers.Main.immediate) { shareStartupDiagnostics(capture) { true } }
        return requireNotNull(capture.started.get())
    }

    /** Reads the real production FileProvider grant; the intercepted chooser cannot send data elsewhere. */
    private fun report(chooser: Intent): ByteArray {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        val uri = requireNotNull(send.clipData).getItemAt(0).uri
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(bytes.size <= 32 * 1024)
        assertTrue(bytes.decodeToString().contains("schema=whitenoise-startup-v1"))
        return bytes
    }

    private class ShareCapture(
        context: Context,
    ) : ContextWrapper(context) {
        val started = AtomicReference<Intent>()

        override fun startActivity(intent: Intent) {
            check(intent.action == Intent.ACTION_CHOOSER)
            started.set(intent)
        }
    }

    private class Fixture(
        failStart: Boolean = false,
        timeoutMs: Long = 30_000,
        beforeStart: suspend () -> Unit = {},
        beforeConstruct: () -> Unit = {},
    ) {
        val root = File(context.filesDir, "Marmot/adoption-diagnostics-${UUID.randomUUID()}").apply { mkdirs() }
        val relay = LoopbackNostrRelay()
        lateinit var native: Marmot
            private set
        private lateinit var observed: MarmotInterface
        var closeCalls = 0
            private set
        val constructed: Boolean get() = ::native.isInitialized
        val app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyDraftPersistence),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
                marmotRuntimeFactory = {
                    beforeConstruct()
                    native =
                        Marmot.newWithConfiguration(
                            root.path,
                            listOf(relay.url),
                            MarmotOptions(
                                relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                                deployment = marmotDeploymentWhiteNoise(),
                            ),
                        )
                    observed =
                        object : MarmotInterface by native {
                            override suspend fun start() {
                                beforeStart()
                                if (failStart) error(PRIVATE_FAILURE)
                                native.start()
                            }

                            override suspend fun shutdownAndClose() {
                                closeCalls++
                                native.shutdownAndClose()
                            }
                        }
                    AppMarmotRuntime(root.path, observed)
                },
                notificationSubscriber = { SilentNotifications },
                bootstrapActionableTimeoutMillis = { timeoutMs },
                schedulePushWakeRecovery = { false },
                preferences = context.getSharedPreferences(root.name, Context.MODE_PRIVATE),
            )

        suspend fun close() =
            withContext(NonCancellable) {
                withTimeout(15_000) {
                    withContext(Dispatchers.Main.immediate) { app.accountSetup.close() }
                    app.stopNotificationListenerForAccountTeardown()
                    app.mutationsScope.coroutineContext[Job]?.cancelAndJoin()
                    if (::observed.isInitialized) {
                        StartupDiagnostics.unbindNative(observed)
                        if (closeCalls == 0) observed.shutdownAndClose()
                    }
                    relay.close()
                    AvatarImageLoader.resetProfileImageFetcherForTests()
                    context.deleteSharedPreferences(root.name)
                    check(root.deleteRecursively())
                }
            }
    }

    private object SilentNotifications : AppNotificationSubscription {
        override suspend fun next(): Nothing = awaitCancellation()

        override fun close() = Unit
    }

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val PRIVATE_FAILURE = "private-fixture-start-failure"
        val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
