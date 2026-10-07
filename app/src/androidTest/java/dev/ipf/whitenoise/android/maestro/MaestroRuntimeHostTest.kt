package dev.ipf.whitenoise.android.maestro

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.LoopbackNostrRelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real MDK state and production Compose screens; never installed-account or public-relay data. */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class MaestroRuntimeHostTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** The host publishes readiness only after the real shell is accessible, then waits for bounded UI work. */
    @Test
    @Suppress("LongMethod") // One fixture generation owns setup, UI handoff and teardown.
    fun hostGeneratedAccounts() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
            val qemu = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
            val emulator =
                android.os.ParcelFileDescriptor.AutoCloseInputStream(qemu).bufferedReader().use { it.readText().trim() }
            check(emulator == "1") { "Disposable emulator required" }
            val generation = checkNotNull(InstrumentationRegistry.getArguments().getString("fixtureGeneration"))
            require(generation.matches(Regex("[a-f0-9]{32}")))
            val directory = File(context.filesDir, "maestro-$generation")
            check(directory.mkdir()) { "Fresh fixture generation required" }
            val root = File(directory, "runtime").apply { mkdirs() }
            val relay = LoopbackNostrRelay()
            val relays = listOf(relay.url)
            MarmotAndroid.initialize(context)
            var native = Marmot.newWithConfiguration(
                root.absolutePath,
                relays,
                MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
            )
            var state: WhiteNoiseAppState? = null
            var activity: ActivityScenario<MainActivity>? = null
            var verifyNative: (suspend () -> Unit)? = null
            try {
                withTimeout(90_000L) {
                    native.start()
                    val accounts = listOf("Maestro Alice", "Maestro Bob", "Maestro Carol").map { name ->
                        native.createIdentity(relays, relays).also {
                            native.publishUserProfile(
                                it.label,
                                UserProfileMetadataFfi(name, name, "Disposable test profile", null, null, null, null),
                                relays,
                                relays,
                            )
                        }
                    }
                    val owner = accounts.first()
                    val group = native.createGroup(owner.label, "Maestro group", listOf(accounts[1].accountIdHex), null)
                    while (runCatching { native.acceptGroupInvite(accounts[1].label, group) }.isFailure) delay(100L)
                    native.sendText(owner.label, group, "Generated fixture message")
                    // Let app bootstrap own start/subscription ordering on a freshly opened runtime.
                    native.shutdownAndClose()
                    native = Marmot.newWithConfiguration(
                        root.absolutePath,
                        relays,
                        MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
                    )
                    verifyNative = {
                        val postcondition = InstrumentationRegistry.getArguments().getString("postcondition", "none")
                        require(postcondition in listOf("none", "send", "dark", "font-large"))
                        if (postcondition == "dark") check(state?.themeMode == AppThemeMode.Dark)
                        if (postcondition == "font-large") check(state?.fontScale == AppFontScale.Large)
                        if (postcondition == "send") {
                            withTimeout(30_000L) {
                                while (true) {
                                    val messages = native.timelineMessages(
                                        accounts[1].label,
                                        TimelineMessageQueryFfi(group, null, null, null, null, null, 100u),
                                    ).messages
                                    val received = messages.count { it.plaintext == "Maestro verified send" }
                                    check(received <= 1) { "Duplicate peer delivery" }
                                    if (received == 1) break
                                    delay(100L)
                                }
                            }
                        }
                    }
                    val app = withContext(Dispatchers.Main.immediate) {
                        WhiteNoiseAppState(
                            context = context,
                            draftStore = DraftStore.forContext(context),
                            accountIdHexResolver = { ref -> accounts.firstOrNull { it.label == ref }?.accountIdHex },
                            accounts = accounts,
                            activeAccountRef = owner.label,
                            marmotRuntimeFactory = { AppMarmotRuntime(root.absolutePath, native) },
                            schedulePushWakeRecovery = { false },
                            preferences = context.getSharedPreferences(directory.name, Context.MODE_PRIVATE),
                        ).also { state = it }
                    }
                    (context.applicationContext as MaestroFixtureApplication).fixtureState = app
                    app.bootstrap()
                    activity = ActivityScenario.launch(MainActivity::class.java)
                    compose.waitUntil(30_000L) {
                        val consentVisible =
                            runCatching { compose.onNodeWithText("Help Improve White Noise").assertIsDisplayed() }.isSuccess
                        if (consentVisible) {
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        }
                        runCatching { compose.onNodeWithText("Maestro group").assertIsDisplayed() }.isSuccess
                    }
                    File(directory, "ready.json").writeText(
                        JSONObject()
                            .put("generation", generation)
                            .put("accounts", accounts.size)
                            .put("ready", true)
                            .toString(),
                    )
                }
                // The controller writes only this generation's finish file; no arbitrary commands.
                withTimeout(300_000L) {
                    while (!File(directory, "finish").exists()) delay(100L)
                }
                checkNotNull(verifyNative).invoke()
                File(directory, "verified.json").writeText(
                    JSONObject().put("generation", generation).put("verified", true).toString(),
                )
            } finally {
                activity?.close()
                state?.stopNotificationListenerForAccountTeardown()
                state?.mutationsScope?.cancel()
                native.shutdownAndClose()
                relay.close()
                context.deleteSharedPreferences(directory.name)
                check(root.deleteRecursively()) { "Fixture runtime cleanup failed" }
                File(directory, "closed.json").writeText(
                    JSONObject().put("generation", generation).put("closed", true).toString(),
                )
            }
        }
}
