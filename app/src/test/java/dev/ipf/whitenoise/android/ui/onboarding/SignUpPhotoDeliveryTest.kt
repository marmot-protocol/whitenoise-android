package dev.ipf.whitenoise.android.ui.onboarding

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
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ActivityResultRegistry callbacks prepare local bytes only for the still-editable form owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SignUpPhotoDeliveryTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Native photo result prepares one local image. */
    @Test fun photosPrepareLocalDraft() = exercise(files = false, transition = Transition.None)

    /** Document result uses the same staged image owner. */
    @Test fun filesPrepareLocalDraft() = exercise(files = true, transition = Transition.None)

    /** A result returning after Sign Up starts cannot change the accepted publication draft. */
    @Test fun returnDuringSubmitIsIgnored() = exercise(false, Transition.Busy)

    /** Discarding the form invalidates its pending photo result. */
    @Test fun returnAfterBackIsIgnored() = exercise(false, Transition.Dispose)

    /** A different form session cannot adopt the previous launch's image. */
    @Test fun returnAfterOwnerReplacementIsIgnored() = exercise(true, Transition.ReplaceOwner)

    private fun exercise(
        files: Boolean,
        transition: Transition,
    ) {
        val registry = Picker()
        val owner = mutableStateOf<Any>(Any())
        val enabled = mutableStateOf(true)
        val visible = mutableStateOf(true)
        var preparations = 0
        val delivered = mutableListOf<ImageUploadDraft?>()
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                WhiteNoiseTheme {
                    if (visible.value) {
                        SignUpPhotoControls(
                            owner.value,
                            enabled.value,
                            null,
                            onPhoto = { delivered += it },
                            onPreparing = {},
                            prepareUri = {
                                preparations++
                                ImageUploadDraft(byteArrayOf(1, 2), "image/jpeg", null, null, null)
                            },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("onboarding.sign_up.photo").performClick()
        val action = if (files) R.string.profile_choose_files else R.string.profile_choose_photos
        composeRule.onNodeWithText(context.getString(action)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, registry.launches)
            when (transition) {
                Transition.None -> Unit
                Transition.Busy -> enabled.value = false
                Transition.Dispose -> visible.value = false
                Transition.ReplaceOwner -> owner.value = Any()
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { registry.deliver(Uri.parse("content://signup-test/photo")) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(if (transition == Transition.None) 1 else 0, preparations)
            assertEquals(if (transition == Transition.None) 1 else 0, delivered.size)
        }
    }

    private enum class Transition { None, Busy, Dispose, ReplaceOwner }

    /** Dispatches the platform callback without opening another app or decoding actual personal photos. */
    private class Picker :
        ActivityResultRegistry(),
        ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launches = 0
        private var request: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches++
            request = requestCode
        }

        fun deliver(uri: Uri) {
            assertTrue(dispatchResult(checkNotNull(request), uri))
        }
    }
}
