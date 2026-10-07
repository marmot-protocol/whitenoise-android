package dev.ipf.whitenoise.android.maestro

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppPhase
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private val MAESTRO_POSTCONDITIONS =
    setOf(
        "none",
        "send",
        "reactions-retained",
        "dark",
        "light",
        "amoled",
        "font-large",
        "folder-saved",
        "folder-absent",
        "poll-no-vote",
        "poll-single-vote",
        "poll-change-vote",
        "poll-multiple-vote",
        "poll-published",
        "chat-deleted",
        "chat-pinned",
        "chat-unpinned",
        "consent-pending",
        "consent-declined",
        "notification-denied",
        "notification-granted",
        "camera-denied",
    )

/** Real MDK state and production Compose screens; never installed-account or public-relay data. */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class MaestroRuntimeHostTest {
    /** Prepare the real native runtime and activity, hand UI observation to Maestro, then verify and dispose. */
    @Test
    @Suppress("LongMethod") // One fixture generation owns setup, UI handoff and teardown.
    fun hostGeneratedAccounts() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
            requireMaestroEmulator()
            val generation = checkNotNull(InstrumentationRegistry.getArguments().getString("fixtureGeneration"))
            require(generation.matches(Regex("[a-f0-9]{32}")))
            val directory = File(context.filesDir, "maestro-$generation")
            check(directory.mkdir()) { "Fresh fixture generation required" }
            val root = File(directory, "runtime").apply { mkdirs() }
            val relay = LoopbackNostrRelay()
            val relays = listOf(relay.url)
            MarmotAndroid.initialize(context)
            var native =
                Marmot.newWithConfiguration(
                    root.absolutePath,
                    relays,
                    MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
                )
            var state: WhiteNoiseAppState? = null
            var activity: ActivityScenario<MainActivity>? = null
            var peerLabel: String? = null
            var groupId: String? = null
            try {
                withTimeout(90_000L) {
                    native.start()
                    val accounts =
                        listOf("Maestro Alice", "Maestro Bob", "Maestro Carol").map { name ->
                            native.createIdentity(relays, relays).also {
                                native.publishUserProfile(
                                    it.label,
                                    UserProfileMetadataFfi(
                                        name,
                                        name,
                                        "Disposable test profile",
                                        null,
                                        null,
                                        null,
                                        null,
                                    ),
                                    relays,
                                    relays,
                                )
                            }
                        }
                    val owner = accounts.first()
                    val group = native.createGroup(owner.label, "Maestro group", listOf(accounts[1].accountIdHex), null)
                    while (runCatching { native.acceptGroupInvite(accounts[1].label, group) }.isFailure) delay(100L)
                    val fixture = InstrumentationRegistry.getArguments().getString("fixtureScenario", "basic")
                    seedMaestroFixtureMessages(native, owner.label, accounts[1].label, group, fixture)
                    // Let app bootstrap own start/subscription ordering on a freshly opened runtime.
                    native.shutdownAndClose()
                    native =
                        Marmot.newWithConfiguration(
                            root.absolutePath,
                            relays,
                            MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
                        )
                    peerLabel = accounts[1].label
                    groupId = group
                    val app =
                        withContext(Dispatchers.Main.immediate) {
                            WhiteNoiseAppState(
                                context = context,
                                draftStore = DraftStore.forContext(context),
                                accountIdHexResolver = { ref ->
                                    accounts.firstOrNull { it.label == ref }?.accountIdHex
                                },
                                accounts = accounts,
                                activeAccountRef = owner.label,
                                profileReader = { id -> withContext(Dispatchers.IO) { native.userProfile(id) } },
                                profileRefreshRequest = { id ->
                                    withContext(Dispatchers.IO) { native.refreshProfile(id, relays) }
                                },
                                marmotRuntimeFactory = { AppMarmotRuntime(root.absolutePath, native) },
                                schedulePushWakeRecovery = { false },
                                preferences = context.getSharedPreferences(directory.name, Context.MODE_PRIVATE),
                            ).also { state = it }
                        }
                    (context.applicationContext as MaestroFixtureApplication).fixtureState = app
                    app.bootstrap()
                    check(app.phase == AppPhase.Ready) { "Generated app bootstrap did not reach Ready: ${app.phase}" }
                    val nativeRow =
                        checkNotNull(native.presentedChatListRow(owner.label, group)) {
                            "Generated group missing from native presentation"
                        }
                    File(directory, "setup.json").writeText(
                        JSONObject()
                            .put("generation", generation)
                            .put("phase", app.phase.toString())
                            .put("kind", nativeRow.row.conversationKind.toString())
                            .put("title", nativeRow.presentation.title.toString())
                            .toString(),
                    )
                    activity = ActivityScenario.launch(MainActivity::class.java)
                    // Maestro alone owns Android accessibility; this receipt certifies native handoff only.
                    File(directory, "ready.json").writeText(
                        JSONObject()
                            .put("generation", generation)
                            .put("accounts", accounts.size)
                            .put("fixture", fixture)
                            .put("uiObserver", "maestro")
                            .put("ready", true)
                            .toString(),
                    )
                }
                // The controller writes only this generation's finish file; no arbitrary commands.
                withTimeout(300_000L) {
                    while (!File(directory, "finish").exists()) delay(100L)
                }
                verifyNativeState(native, state, checkNotNull(peerLabel), checkNotNull(groupId))
                File(directory, "verified.json").writeText(
                    JSONObject().put("generation", generation).put("verified", true).toString(),
                )
            } finally {
                val activityClosed = runCatching { activity?.close() }
                val listenerStopped =
                    runCatching { withTimeout(10_000L) { state?.stopNotificationListenerForAccountTeardown() } }
                state?.mutationsScope?.cancel()
                val nativeClosed = runCatching { withTimeout(15_000L) { native.shutdownAndClose() } }
                val relayClosed = runCatching { relay.close() }
                val preferencesRemoved = runCatching { context.deleteSharedPreferences(directory.name) }
                val rootRemoved = nativeClosed.isSuccess && root.deleteRecursively()
                check(activityClosed.isSuccess && listenerStopped.isSuccess && nativeClosed.isSuccess) {
                    "Fixture owner teardown failed"
                }
                check(relayClosed.isSuccess && preferencesRemoved.isSuccess && rootRemoved) {
                    "Fixture storage cleanup failed"
                }
                File(directory, "closed.json").writeText(
                    JSONObject().put("generation", generation).put("closed", true).toString(),
                )
            }
        }

    /** Validate the actual native peer result and preference projection independently of UI text. */
    private suspend fun verifyNativeState(
        native: Marmot,
        state: WhiteNoiseAppState?,
        peerLabel: String,
        group: String,
    ) {
        val postcondition = InstrumentationRegistry.getArguments().getString("postcondition", "none")
        require(postcondition in MAESTRO_POSTCONDITIONS)
        if (postcondition.startsWith("poll-")) {
            verifyMaestroPoll(native, checkNotNull(state?.activeAccountRef), peerLabel, group, postcondition)
        }
        if (postcondition.startsWith("chat-")) {
            verifyMaestroChatList(native, checkNotNull(state?.activeAccountRef), peerLabel, group, postcondition)
        }
        if (postcondition.startsWith("notification-")) verifyMaestroNotificationPermission(postcondition)
        if (postcondition == "camera-denied") verifyMaestroCameraDenied()
        if (postcondition.startsWith("consent-")) {
            verifyMaestroConsent(native, checkNotNull(state), postcondition)
        }
        if (postcondition.startsWith("folder-")) verifyMaestroFolder(checkNotNull(state), postcondition)
        if (postcondition == "light") check(state?.themeMode == AppThemeMode.Light)
        if (postcondition == "amoled") check(state?.themeMode == AppThemeMode.Amoled)
        if (postcondition == "dark") check(state?.themeMode == AppThemeMode.Dark)
        if (postcondition == "font-large") check(state?.fontScale == AppFontScale.Large)
        if (postcondition == "send") verifyMaestroSend(native, checkNotNull(state), peerLabel, group)
        if (postcondition == "reactions-retained") {
            verifyMaestroReactions(native, checkNotNull(state?.activeAccountRef), peerLabel, group)
        }
    }
}
