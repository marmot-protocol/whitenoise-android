package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import dev.ipf.whitenoise.android.ui.conversation.share.PickContactPhoneRow
import dev.ipf.whitenoise.android.ui.navigation.rememberProtectedShellSavedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs the conversation's photo, document and contact launchers through the protected shell's lock
 * disposal. An accepted selection reaches its original account/chat exactly once, never another one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentPickerAppLockCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    /** Every conversation picker and the camera permission request use the origin-guarded launcher. */
    @Test
    fun conversationPickersShareTheOriginGuardedLauncher() {
        val source =
            sequenceOf(
                File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
            ).first(File::isFile).readText()
        listOf(
            "val imagePickerLauncher =",
            "val cameraPermissionLauncher =",
            "val contactPickerLauncher =",
            "val documentPickerLauncher =",
        ).forEach { declaration ->
            val body = source.substringAfter(declaration).take(GUARDED_DECLARATION_WINDOW)
            assertTrue(
                "$declaration must keep its origin across lock disposal",
                "rememberOriginGuardedResultLauncher(" in body,
            )
        }
        assertTrue("a full shelf must explain a refused capture", "onRejected =" in source)
    }

    /** A photo selection accepted before lock disposal reaches its chat once after unlock. */
    @Test fun photoResultBeforeLockAttachesOnce() = photos().exercise(PickerOrdering.BeforeLock)

    /** A photo selection delivered while locked waits for unlock and attaches once. */
    @Test fun photoResultWhileLockedAttachesOnce() = photos().exercise(PickerOrdering.WhileLocked)

    /** A photo selection survives saved-state restoration while locked. */
    @Test fun photoResultAfterRestoreAttachesOnce() = photos().exercise(PickerOrdering.RestoredWhileLocked)

    /** A photo selection after a locked account change is not attached to the new account. */
    @Test fun photoResultAfterAccountChangeIsDropped() = photos().exercise(PickerOrdering.AccountChanged)

    /** A photo selection after the screen moved to another chat is not attached there. */
    @Test fun photoResultAfterChatChangeIsDropped() = photos().exercise(PickerOrdering.ChatChanged)

    /** A document selection delivered while locked waits for unlock and attaches once. */
    @Test fun documentResultWhileLockedAttachesOnce() = documents().exercise(PickerOrdering.WhileLocked)

    /** A document selection survives saved-state restoration while locked. */
    @Test fun documentResultAfterRestoreAttachesOnce() = documents().exercise(PickerOrdering.RestoredWhileLocked)

    /** A document selection after a locked account change is not attached to the new account. */
    @Test fun documentResultAfterAccountChangeIsDropped() = documents().exercise(PickerOrdering.AccountChanged)

    /** A contact selection accepted before lock disposal reaches its chat once after unlock. */
    @Test fun contactResultBeforeLockAttachesOnce() = contacts().exercise(PickerOrdering.BeforeLock)

    /** A contact selection delivered while locked waits for unlock and attaches once. */
    @Test fun contactResultWhileLockedAttachesOnce() = contacts().exercise(PickerOrdering.WhileLocked)

    /** A contact selection after the screen moved to another chat is not attached there. */
    @Test fun contactResultAfterChatChangeIsDropped() = contacts().exercise(PickerOrdering.ChatChanged)

    /** Builds the photo picker fixture with the production multi-select contract. */
    private fun photos() =
        PickerFixture(
            composeRule,
            ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            listOf(Uri.parse("content://fixture/photo/1")),
        )

    /** Builds the document picker fixture with the production multi-document contract. */
    private fun documents() =
        PickerFixture(
            composeRule,
            ActivityResultContracts.OpenMultipleDocuments(),
            arrayOf("*/*"),
            listOf(Uri.parse("content://fixture/document/1")),
        )

    /** Builds the contact picker fixture with the production phone-row contract. */
    private fun contacts() =
        PickerFixture(
            composeRule,
            PickContactPhoneRow(),
            Unit,
            Uri.parse("content://fixture/contact/1"),
        )
}

private const val GUARDED_DECLARATION_WINDOW = 160

/** One lock/result ordering for a guarded picker request. */
internal enum class PickerOrdering {
    BeforeLock,
    WhileLocked,
    RestoredWhileLocked,
    AccountChanged,
    ChatChanged,
}

/** Mounts one guarded launcher under the protected-shell saved-state provider and a recording registry. */
private class PickerFixture<I, O>(
    private val composeRule: androidx.compose.ui.test.junit4.ComposeContentTestRule,
    private val contract: ActivityResultContract<I, O>,
    private val input: I,
    private val selection: O,
) {
    private val registry = PickerRegistry()
    private val restoration = StateRestorationTester(composeRule)
    private val locked = mutableStateOf(false)
    private val account = mutableStateOf("picker-account")
    private val chat = mutableStateOf("picker-chat")
    private var launcher: OriginGuardedResultLauncher<I>? = null
    private var accepted = emptyList<String>()
    private var composed = false

    /** Launches, applies [ordering], unlocks and checks the selection landed only at its origin, once. */
    fun exercise(ordering: PickerOrdering) {
        mount()
        composeRule.runOnIdle { checkNotNull(launcher).launch(input) }
        if (ordering == PickerOrdering.BeforeLock) deliver()
        setLocked(true)
        composeRule.runOnIdle { assertFalse("protected content must be uncomposed", composed) }
        if (ordering == PickerOrdering.RestoredWhileLocked) restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            if (ordering == PickerOrdering.AccountChanged) account.value = "other-account"
            if (ordering == PickerOrdering.ChatChanged) chat.value = "other-chat"
        }
        if (ordering != PickerOrdering.BeforeLock) deliver()
        setLocked(false)
        val dropped = ordering == PickerOrdering.AccountChanged || ordering == PickerOrdering.ChatChanged
        val expected = if (dropped) emptyList() else listOf(selection.toString())
        composeRule.runOnIdle {
            assertTrue(composed)
            assertEquals(expected, accepted)
        }
        // A duplicate platform delivery cannot attach the selection a second time.
        deliver()
        composeRule.runOnIdle { assertEquals(expected, accepted) }
    }

    /** Composes the guarded launcher and its saved selection list under the lock gate. */
    private fun mount() {
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                val state = rememberProtectedShellSavedState("picker-account", 4, suspended = locked.value)
                if (!locked.value) {
                    state.holder.SaveableStateProvider(state.key) {
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            composed = true
                            onDispose { composed = false }
                        }
                        var selections by rememberSaveable { mutableStateOf(emptyList<String>()) }
                        launcher =
                            rememberOriginGuardedResultLauncher(
                                contract,
                                "4:${account.value}:${chat.value}",
                                isCurrent = { account.value == "picker-account" },
                            ) { result -> selections = selections + result.toString() }
                        accepted = selections
                    }
                }
            }
        }
    }

    /** Toggles the lock gate and waits for the protected subtree to follow. */
    private fun setLocked(value: Boolean) {
        composeRule.runOnIdle { locked.value = value }
        composeRule.waitForIdle()
    }

    /** Delivers the platform's parsed selection to the last launched request code. */
    private fun deliver() {
        composeRule.runOnIdle { registry.dispatchResult(registry.requestCode, selection) }
        composeRule.waitForIdle()
    }
}

/** Records the last launched request code so a test can answer it like the platform does. */
private class PickerRegistry :
    ActivityResultRegistry(),
    ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry get() = this
    var requestCode = 0

    /** Captures the launch instead of starting an external Activity. */
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        this.requestCode = requestCode
    }
}
