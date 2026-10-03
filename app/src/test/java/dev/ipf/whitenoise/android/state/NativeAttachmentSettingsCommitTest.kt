package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentAutomaticPermissionFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real setting/permission wiring while preference publication is deliberately held off-main. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeAttachmentSettingsCommitTest {
    /** Permission evaluation must consume the enabled matrix after its queued commit, without another host event. */
    @Test
    fun enabledDocumentPermissionWaitsForItsPreferenceCommit() = checkCommittedPermission(enabled = true)

    /** Beginning an update revokes old permission before a held disabling commit can finish. */
    @Test
    fun disabledDocumentPermissionRevokesBeforeItsPreferenceCommit() = checkCommittedPermission(enabled = false)

    /** Keep Android preferences and native generations real at their boundary; delay only durable preference commit. */
    private fun checkCommittedPermission(enabled: Boolean) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication().applicationContext
        val preferences = context.getSharedPreferences("native-settings-commit", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val initial =
            MediaAutoDownloadMatrix(emptySet()).withToggle(
                MediaAutoDownloadType.Document,
                MediaAutoDownloadNetwork.WiFi,
                !enabled,
            )
        persistMediaAutoDownloadMatrix(preferences, "media_auto_download_matrix:account-id", initial)
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val granted = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<String>()
        val permissions = CopyOnWriteArrayList<AttachmentAutomaticPermissionFfi>()
        val engine =
            nativeBoundary { method, args ->
                when (method) {
                    "beginAttachmentPermissionUpdate" -> {
                        calls += "revoke"
                        "generation"
                    }
                    "setAttachmentAutomaticPermission" -> {
                        permissions += args[2] as AttachmentAutomaticPermissionFfi
                        granted.countDown()
                        true
                    }
                    "recordHostPerformance" -> Unit
                    else -> error("unexpected native call: $method")
                }
            }
        val state = createState(heldPreferences(preferences, commitStarted, releaseCommit), engine)
        try {
            setValidatedWifi(state)
            state.setMediaAutoDownload(MediaAutoDownloadType.Document, MediaAutoDownloadNetwork.WiFi, enabled)
            assertTrue("commit did not start", commitStarted.await(5, TimeUnit.SECONDS))
            assertTrue("native permission was not revoked before persistence", "revoke" in calls)
            assertTrue(
                "latest matrix must be observable immediately",
                state.mediaAutoDownloadMatrix.isEnabled(
                    MediaAutoDownloadType.Document,
                    MediaAutoDownloadNetwork.WiFi,
                ) == enabled,
            )
            assertFalse("permission was granted using uncommitted preference data", permissions.isNotEmpty())
            releaseCommit.countDown()
            assertTrue("commit did not refresh native permission", granted.await(5, TimeUnit.SECONDS))
            assertTrue("native permission used the obsolete matrix", permissions.single().files == enabled)
        } finally {
            releaseCommit.countDown()
            state.mutationsScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /** Keep the unit test focused on preference ordering, with an explicit validated-Wi-Fi input. */
    private fun setValidatedWifi(state: WhiteNoiseAppState) {
        setField(state, "hasActiveNetworkSnapshot", true)
        setField(state, "activeNetworkTypesSnapshot", setOf(MediaAutoDownloadNetwork.WiFi))
        val tracker =
            WhiteNoiseAppState::class.java
                .getDeclaredField("validatedInternetNetworks")
                .apply {
                    isAccessible = true
                }.get(state) as ValidatedInternetNetworkTracker
        tracker.update(1L, available = true)
    }

    /** Bind a generated account to the scripted native boundary without starting Android services. */
    private fun createState(
        preferences: SharedPreferences,
        engine: MarmotInterface,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = RuntimeEnvironment.getApplication().applicationContext,
            draftStore =
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
            accountIdHexResolver = { "account-id" },
            accounts = listOf(AccountSummaryFfi("account", "account-id", true, false, false, true)),
            activeAccountRef = "account",
            initialMarmotRuntime = AppMarmotRuntime("generated-settings-test", engine),
            preferences = preferences,
        )

    /** Delegate Android editor behavior while a latch delays only commit, never apply or preference reads. */
    private fun heldPreferences(
        delegate: SharedPreferences,
        started: CountDownLatch,
        release: CountDownLatch,
    ): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "edit") {
                val editor = delegate.edit()
                lateinit var wrapped: SharedPreferences.Editor
                wrapped =
                    Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { _, editMethod, editArgs ->
                        if (editMethod.name == "commit") {
                            started.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "held preference commit was not released" }
                        }
                        val result = editMethod.invoke(editor, *editArgs.orEmpty())
                        if (result === editor) wrapped else result
                    } as SharedPreferences.Editor
                wrapped
            } else {
                method.invoke(delegate, *args.orEmpty())
            }
        } as SharedPreferences

    /** Inject only unit-test connectivity inputs; device probes use real Android callbacks. */
    private fun setField(
        state: WhiteNoiseAppState,
        name: String,
        value: Any,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(state, value)
    }
}
