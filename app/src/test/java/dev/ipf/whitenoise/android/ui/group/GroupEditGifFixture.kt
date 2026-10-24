package dev.ipf.whitenoise.android.ui.group

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.profile.personTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A real editor and Android document result, substituting only provider bytes and native upload I/O. */
internal class GroupEditGifFixture {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val account = "gif-editor-account"
    val self = "01" + "00".repeat(31)
    val firstGroup = "04" + "00".repeat(31)
    val secondGroup = "05" + "00".repeat(31)
    val uploads = AtomicInteger()
    val registry = GifDocumentRegistry()
    val mounted = mutableStateOf(true)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closedStreams = AtomicInteger()
    private val controllers = mutableListOf<ConversationController>()
    val state =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyGifDraftPersistence()),
            accountIdHexResolver = { self },
            accounts = listOf(AccountSummaryFfi(account, self, true, false, false, true)),
            activeAccountRef = account,
            profileReader = { null },
            profileDisplayNameReader = { "Editor account" },
            profileRefreshRequest = {},
            initialMarmotRuntime = AppMarmotRuntime("gif-editor-test", native()),
            marmotIoDispatcher = Dispatchers.IO,
        )
    val controller = mutableStateOf(newController(firstGroup))

    /** A replacement can keep its group id; controller identity must still retire the previous owner. */
    fun newController(groupId: String): ConversationController =
        ConversationController(
            appState = state,
            initialGroup = personTestGroup(groupId, "Editor group", listOf(self), listOf(self)).group,
            initialMemberSnapshot = GroupMemberSnapshot(listOf(AppGroupMemberRecordFfi(self, account, true))),
        ).also(controllers::add)

    /** The same valid, pixel-decodable animation used by the draft processor's own tests. */
    fun uri(
        name: String,
        hold: Boolean = false,
    ): Uri {
        val uri = Uri.parse("content://whitenoise.synthetic/gif-editor/$name.gif")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            if (hold) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Held document was not released" }
            }
            object : ByteArrayInputStream(twoFrameGif()) {
                override fun close() {
                    super.close()
                    closedStreams.incrementAndGet()
                }
            }
        }
        return uri
    }

    /** Release held provider I/O before retiring fixture-owned jobs and controllers. */
    fun close() {
        release.countDown()
        state.mutationsScope.cancel()
        controllers.forEach { it.onCleared() }
    }

    private fun native(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { _, method, args ->
            when (method.name) {
                "uploadProfileImage" -> {
                    assertEquals(account, args!![0])
                    assertEquals("image/gif", args[2])
                    uploads.incrementAndGet()
                    error("Synthetic upload refusal after admission")
                }
                "listMedia" -> emptyList<Any>()
                "catchUpAccounts" -> Unit
                "toString" -> "GifEditorNative"
                "hashCode" -> 1
                "equals" -> false
                else -> error("Unexpected native call ${method.name}")
            }
        } as MarmotInterface
}

/** Dispatch raw Android results so the shipped OpenDocument contract parses the returned URI. */
internal class GifDocumentRegistry :
    ActivityResultRegistry(),
    ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry get() = this
    val launches = CopyOnWriteArrayList<Int>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        check(contract is ActivityResultContracts.OpenDocument)
        launches += requestCode
    }

    /** Retired registrations may retain an undelivered result, but must never route it to a new editor. */
    fun deliver(
        uri: Uri,
        requestCode: Int = launches.last(),
    ): Boolean = dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
}

/** Replacing the controller preserves the containing composition and only changes the real editor input. */
@Composable
internal fun GifEditorTestContent(
    fixture: GroupEditGifFixture,
    dark: Boolean = false,
    amoled: Boolean = false,
    rtl: Boolean = false,
) {
    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides fixture.registry,
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
            if (fixture.mounted.value) GroupEditScreen(fixture.state, fixture.controller.value, onBack = {})
        }
    }
}

private class EmptyGifDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
