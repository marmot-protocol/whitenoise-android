package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Delivers native picker results through the real registry without launching another application or
 * uploading bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ProfileImageActionsDeliveryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A photo-library result belongs to the account and target that opened its launcher. */
    @Test
    fun photosReturnToSameAccountDeliversBanner() = exercise(Source.Photos, Transition.None)

    /** OpenDocument keeps the profile-picture target when delivering a same-account selection. */
    @Test
    fun filesReturnToSameAccountDeliversPicture() = exercise(Source.Files, Transition.None)

    /** A library result cannot start uploading into an account selected while the picker was open. */
    @Test
    fun photosReturnAfterAccountSwitchIsIgnored() = exercise(Source.Photos, Transition.AccountSwitch)

    /** A document result cannot start uploading into an account selected while the picker was open. */
    @Test
    fun filesReturnAfterAccountSwitchIsIgnored() = exercise(Source.Files, Transition.AccountSwitch)

    /** Leaving editing disposes the launcher; its eventual photo selection is not a new draft. */
    @Test
    fun photosReturnAfterEditingCanceledIsIgnored() = exercise(Source.Photos, Transition.CancelEdit)

    /** Leaving editing disposes the launcher; its eventual document selection is not a new draft. */
    @Test
    fun filesReturnAfterEditingCanceledIsIgnored() = exercise(Source.Files, Transition.CancelEdit)

    private fun exercise(
        source: Source,
        transition: Transition,
    ) {
        val registry = RecordingPicker()
        val owner = mutableStateOf("account-a")
        val editing = mutableStateOf(true)
        val delivered = mutableListOf<Pair<ProfileImageTarget, Uri>>()
        val target = if (source == Source.Photos) ProfileImageTarget.Banner else ProfileImageTarget.Picture
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                WhiteNoiseTheme {
                    if (editing.value) {
                        ProfileImageActions(
                            owner = owner.value,
                            target = target,
                            hasImage = false,
                            enabled = true,
                            busy = false,
                            onPick = { pickedTarget, uri -> delivered += pickedTarget to uri },
                            onWeb = {},
                            onRemove = {},
                        )
                    }
                }
            }
        }
        val trigger = if (target == ProfileImageTarget.Banner) "profile.banner_actions" else "profile.photo_actions"
        composeRule.onNodeWithTag(trigger).performClick()
        val label = if (source == Source.Photos) R.string.profile_choose_photos else R.string.profile_choose_files
        composeRule.onNodeWithText(context.getString(label)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, registry.launchCount)
            assertEquals(if (source == Source.Photos) "PickVisualMedia" else "OpenDocument", registry.contractName)
            when (transition) {
                Transition.None -> Unit
                Transition.AccountSwitch -> owner.value = "account-b"
                Transition.CancelEdit -> editing.value = false
            }
        }
        composeRule.waitForIdle()
        val selected = Uri.parse("content://profile-test/selected-image")
        composeRule.runOnIdle { registry.deliver(selected) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            if (transition == Transition.None) {
                assertEquals(listOf(target to selected), delivered)
            } else {
                assertTrue(delivered.isEmpty())
            }
        }
        // A newly mounted edit session must not adopt a result returned to the disposed registration either.
        if (transition == Transition.CancelEdit) {
            composeRule.runOnIdle { editing.value = true }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertTrue(delivered.isEmpty()) }
        }
    }

    private enum class Source { Photos, Files }

    private enum class Transition { None, AccountSwitch, CancelEdit }

    /** Records the actual launcher and delivers through ActivityResultRegistry's registered callback. */
    private class RecordingPicker :
        ActivityResultRegistry(),
        ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launchCount = 0
            private set
        var contractName = ""
            private set
        private var requestCode: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            contractName = contract.javaClass.simpleName
            launchCount++
        }

        fun deliver(uri: Uri) {
            assertTrue(dispatchResult(requireNotNull(requestCode), uri))
        }
    }
}
