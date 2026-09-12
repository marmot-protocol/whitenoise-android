package dev.ipf.whitenoise.android.ui.profile

import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Actual profile lookup/local contact owners and secure windows behind the new full-screen presentation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PersonProfileRouteTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shown = mutableStateOf(true)
    private val target = "aa".repeat(32)
    private val publicKey = "npub1424242424242424242424242424242424242424242424242424qamrcaj"
    private val profile =
        UserProfileMetadataFfi(
            name = "Published name",
            displayName = null,
            about = "Public about",
            picture = null,
            banner = null,
            nip05 = null,
            lud16 = null,
        )

    /** Save writes only the viewer's private contact values and never replaces the published profile. */
    @Test fun privateSaveRetainsPublicMetadataAndCopyExcludesNotes() {
        val app = show()
        openPrivate()
        composeRule.onNodeWithTag("person_profile.nickname").performTextInput("Local name")
        composeRule.onNodeWithTag("person_profile.notes").performTextInput("Private contact note")
        composeRule.onNodeWithTag("person_profile.private_save").performClick()
        assertEquals("Local name", app.contactNickname(target))
        assertEquals("Private contact note", app.contactNotes(target))
        assertEquals(profile, app.pendingProfileMetadata)
        composeRule.onNodeWithTag("person_profile.copy_public_key").performScrollTo().performClick()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals(
            publicKey,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        composeRule.onNodeWithText("Private contact note").assertDoesNotExist()
    }

    /** Cancel leaves the native local record unchanged, while explicit clearing restores the public display name. */
    @Test fun privateCancelAndClearRespectTheExistingLocalOwner() {
        val app = show(initialNickname = "Saved name")
        openPrivate()
        composeRule.onNodeWithTag("person_profile.nickname").performTextReplacement("Discard me")
        composeRule.onNodeWithTag("person_profile.private_cancel").performClick()
        assertEquals("Saved name", app.contactNickname(target))
        openPrivate()
        composeRule.onNodeWithTag("person_profile.nickname").performTextReplacement("")
        composeRule.onNodeWithTag("person_profile.private_save").performClick()
        assertEquals(null, app.contactNickname(target))
        composeRule.onNodeWithText("Published name").assertExists()
        assertEquals(profile, app.pendingProfileMetadata)
    }

    /** A captured Save cannot write a different viewer's contact record after account replacement. */
    @Test fun accountChangeRevokesAlreadyCapturedPrivateSave() {
        val app = show()
        openPrivate()
        composeRule.onNodeWithTag("person_profile.nickname").performTextInput("Wrong viewer")
        val save =
            composeRule
                .onNodeWithTag("person_profile.private_save")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setActiveAccountRef", String::class.java)
                .apply { isAccessible = true }
                .invoke(app, "carol")
            save()
        }
        assertEquals(null, app.contactNickname(target))
        assertEquals(null, app.contactNotes(target))
        composeRule.waitForIdle()
        assertFalse(shown.value)
    }

    /** The profile and private editor both honor the caller's secure-window policy. */
    @Test fun privateDialogInheritsExplicitSecureProfilePolicy() {
        show()
        assertSecureWindow()
        openPrivate()
        assertSecureWindow()
    }

    /** The retained native normalizer collapses oversized/multiline nickname input at Save, not into public fields. */
    @Test fun savedNicknameUsesTheExistingEightyCharacterBound() {
        val app = show()
        openPrivate()
        composeRule.onNodeWithTag("person_profile.nickname").performTextInput("Long ".repeat(40))
        composeRule.onNodeWithTag("person_profile.private_save").performClick()
        val nickname = checkNotNull(app.contactNickname(target))
        assertTrue(nickname.length <= 80)
        assertFalse(nickname.contains('\n'))
        assertEquals(profile, app.pendingProfileMetadata)
    }

    /** Back consumes ownership before Compose removes the window, so an old Message tap cannot start native timing. */
    @Test fun sameFrameBackRevokesCapturedMessage() {
        val app = show()
        val message =
            composeRule
                .onNode(hasClickAction() and hasAnyAncestor(hasTestTag(PROFILE_MESSAGE_ACTION_TAG)))
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val back =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.back))
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            back()
            message()
            assertFalse(shown.value)
            assertFalse(app.hasActiveChatCreateOpenTiming())
        }
    }

    /** Start Group consumes this profile immediately; a captured private Save cannot commit in the same frame. */
    @Test fun sameFrameStartGroupRevokesCapturedPrivateSave() {
        val started = mutableListOf<RecipientSearch.Candidate>()
        val app = show(onStartGroup = { started += it })
        val start =
            composeRule
                .onNodeWithTag("person_profile.start_group")
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        openPrivate()
        composeRule.onNodeWithTag("person_profile.nickname").performTextInput("Discarded after leave")
        val save =
            composeRule
                .onNodeWithTag("person_profile.private_save")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            start()
            save()
            assertEquals(listOf(target), started.map { it.accountIdHex })
            assertEquals(null, app.contactNickname(target))
        }
    }

    /** Opens the actual dialog action through the full-screen scroll container. */
    private fun openPrivate() {
        composeRule.onNodeWithTag("person_profile.private_details").performScrollTo().performClick()
    }

    /** Reads the latest real Android dialog window, not a composable-only security marker. */
    private fun assertSecureWindow() {
        composeRule.runOnIdle {
            assertTrue(
                checkNotNull(ShadowDialog.getLatestDialog().window).attributes.flags and
                    WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }

    /** Uses the real AppState local storage/metadata owner and injects only identity resolution. */
    private fun show(
        initialNickname: String? = null,
        onStartGroup: (RecipientSearch.Candidate) -> Unit = {},
    ): WhiteNoiseAppState {
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
                { target },
                listOf(
                    AccountSummaryFfi("alice", "11".repeat(32), true, false, false, true),
                    AccountSummaryFfi("carol", "33".repeat(32), true, false, false, true),
                ),
                "alice",
            )
        initialNickname?.let { app.setContactNickname(target, it) }
        app.presentDiscoveredProfile(publicKey, profile)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (shown.value) {
                    ProfileSheet(
                        app,
                        publicKey,
                        { _, _ -> },
                        onStartGroup,
                        { shown.value = false },
                        securePolicy = SecureFlagPolicy.SecureOn,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return app
    }
}
