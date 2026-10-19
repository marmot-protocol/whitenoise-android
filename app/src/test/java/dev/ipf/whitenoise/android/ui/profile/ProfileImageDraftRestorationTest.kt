package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.whitenoise.android.ui.navigation.rememberProtectedShellSavedState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the production saved draft owner, native refresh rebase and explicit commit/reset transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileImageDraftRestorationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun untouchedPictureFollowsNewNativeBaselineAfterLock() = exercise()

    @Test fun uploadedPictureSurvivesNewNativeBaselineWhileUntouchedBannerRefreshes() {
        exercise(edited = ProfileImageTarget.Picture)
    }

    @Test fun uploadedBannerSurvivesNewNativeBaselineWhileUntouchedPictureRefreshes() {
        exercise(edited = ProfileImageTarget.Banner)
    }

    @Test fun explicitSaveMakesUploadedPictureAnUntouchedBaselineAgain() {
        exercise(edited = ProfileImageTarget.Picture, settleEdit = true)
    }

    @Test fun explicitResetDropsUploadedPictureBeforeRemount() {
        exercise(edited = ProfileImageTarget.Picture, resetEdit = true)
    }

    @Test fun restoredDraftWithACacheMissRefreshesOnlyUntouchedFields() {
        val locked = mutableStateOf(false)
        val native = mutableStateOf<String?>("picture-A")
        var visible = ProfileImageDrafts()
        var remove: (() -> Unit)? = null
        composeRule.setContent {
            val protected = rememberProtectedShellSavedState("account", 1, suspended = locked.value)
            if (!locked.value) {
                protected.holder.SaveableStateProvider(protected.key) {
                    var images by rememberProfileImageDrafts("account", native.value, native.value)
                    remove = { images = images.without(ProfileImageTarget.Picture) }
                    visible = images
                }
            }
        }
        composeRule.runOnIdle { checkNotNull(remove).invoke() }
        composeRule.runOnIdle { locked.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            native.value = null
            locked.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { native.value = "picture-B" }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals("", visible.picture)
            assertEquals("picture-B", visible.banner)
            assertEquals("picture-B", visible.baselinePicture)
        }
    }

    private fun exercise(
        edited: ProfileImageTarget? = null,
        settleEdit: Boolean = false,
        resetEdit: Boolean = false,
    ) {
        val locked = mutableStateOf(false)
        val nativePicture = mutableStateOf("picture-A")
        val nativeBanner = mutableStateOf("banner-A")
        var visible = ProfileImageDrafts()
        var edit: (() -> Unit)? = null
        var settle: (() -> Unit)? = null
        var reset: (() -> Unit)? = null
        composeRule.setContent {
            val protected = rememberProtectedShellSavedState("account", 1, suspended = locked.value)
            if (!locked.value) {
                protected.holder.SaveableStateProvider(protected.key) {
                    var images by rememberProfileImageDrafts("account", nativePicture.value, nativeBanner.value)
                    // This is the editor's actual native image refresh path.
                    LaunchedEffect(nativePicture.value, nativeBanner.value) {
                        images = images.rebased(nativePicture.value, nativeBanner.value)
                    }
                    edit = {
                        images = images.withUploadedImage(checkNotNull(edited), "uploaded-U", "account", "account")
                    }
                    settle = {
                        images = images.rebased(nativePicture.value, nativeBanner.value)
                    }
                    reset = {
                        images = ProfileImageDrafts(nativePicture.value, nativeBanner.value)
                    }
                    visible = images
                }
            }
        }
        if (edited != null) composeRule.runOnIdle { checkNotNull(edit).invoke() }
        if (settleEdit) {
            composeRule.runOnIdle { nativePicture.value = "uploaded-U" }
            composeRule.waitForIdle()
            composeRule.runOnIdle { checkNotNull(settle).invoke() }
        }
        if (resetEdit) composeRule.runOnIdle { checkNotNull(reset).invoke() }
        composeRule.runOnIdle { locked.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            nativePicture.value = "picture-B"
            nativeBanner.value = "banner-B"
            locked.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val retainsEdit = !settleEdit && !resetEdit
            assertEquals(
                if (edited == ProfileImageTarget.Picture && retainsEdit) "uploaded-U" else "picture-B",
                visible.picture,
            )
            assertEquals(
                if (edited == ProfileImageTarget.Banner && retainsEdit) "uploaded-U" else "banner-B",
                visible.banner,
            )
            assertEquals("picture-B", visible.baselinePicture)
            assertEquals("banner-B", visible.baselineBanner)
        }
    }
}
