package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG
import dev.ipf.whitenoise.android.ui.share.SHARE_CHAT_PICKER_SCREEN_TEST_TAG
import dev.ipf.whitenoise.android.ui.share.ShareChatPickerAccountSheetContent
import dev.ipf.whitenoise.android.ui.share.ShareChatPickerFullScreenContent
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChats
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Baseline for the dedicated inbound system-share recipient destination. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareChatPickerFullScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun populatedPickerDark() {
        renderMultiAccountPicker()

        composeRule
            .onNodeWithTag(SHARE_CHAT_PICKER_SCREEN_TEST_TAG)
            .captureRoboImage("src/test/snapshots/share_chat_picker_full_screen_dark.png")
    }

    /** Captures the actual recipient picker at the supported maximum app font scale. */
    @Test
    fun populatedPickerDarkLargeFont() {
        renderMultiAccountPicker(fontScale = 2f)

        composeRule
            .onNodeWithTag(SHARE_CHAT_PICKER_SCREEN_TEST_TAG)
            .captureRoboImage("src/test/snapshots/share_chat_picker_full_screen_dark_large.png")
    }

    /** Captures the local-first empty route with its non-destructive offline error. */
    @Test
    fun emptyOfflinePickerDark() {
        val appState = emptyAppState()
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.publishInitialLoadFailureForTest(
            ErrorPresentation(AppText.Plain("Offline — showing chats on this device"), "operation=SHARE_PICKER"),
        )
        appState.attachChatsController(controller)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ShareChatPickerFullScreenContent(
                        appState = appState,
                        requestId = "empty-offline-screenshot",
                        payload = SharePayload("Local-first share", emptyList(), "text/plain"),
                        onDismiss = {},
                        onStage = { _, _ -> true },
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(SHARE_CHAT_PICKER_SCREEN_TEST_TAG)
            .captureRoboImage("src/test/snapshots/share_chat_picker_full_screen_empty_offline_dark.png")
    }

    @Test
    fun accountChoiceSheetDark() {
        val accounts =
            listOf(
                testAccount(ACCOUNT_REF, ACCOUNT_HEX),
                testAccount("work", hexId(0x71)),
            )
        val appState = emptyAppState(accounts = accounts)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    ShareChatPickerAccountSheetContent(
                        appState = appState,
                        accounts = accounts,
                        selectedAccountRef = ACCOUNT_REF,
                        onChooseAccount = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG)
            .captureRoboImage("src/test/snapshots/share_chat_picker_account_sheet_dark.png")
    }

    @Test
    fun accountChoiceSheetDarkLargeFont() {
        val accounts =
            listOf(
                testAccount(ACCOUNT_REF, ACCOUNT_HEX),
                testAccount("work", hexId(0x71)),
            )
        val appState = emptyAppState(accounts = accounts)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                WhiteNoiseTheme(darkTheme = true) {
                    Surface {
                        ShareChatPickerAccountSheetContent(
                            appState = appState,
                            accounts = accounts,
                            selectedAccountRef = ACCOUNT_REF,
                            onChooseAccount = {},
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG)
            .captureRoboImage("src/test/snapshots/share_chat_picker_account_sheet_dark_large.png")
    }

    /** Renders the populated multi-account surface at one deterministic density. */
    private fun renderMultiAccountPicker(fontScale: Float = 1f) {
        val chats =
            (0 until 10).map { index ->
                hexId(0x20 + index) to hexId(0x40 + index)
            }
        val profiles =
            chats
                .mapIndexed { index, (_, peerId) ->
                    peerId to profile(displayName = "Person ${index + 1}")
                }.toMap(mutableMapOf())
        val appState =
            appStateWithDirectChats(
                *chats.toTypedArray(),
                profiles = profiles,
                accounts =
                    listOf(
                        testAccount(ACCOUNT_REF, ACCOUNT_HEX),
                        testAccount("work", hexId(0x71)),
                    ),
            )

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                WhiteNoiseTheme(darkTheme = true) {
                    Surface {
                        ShareChatPickerFullScreenContent(
                            appState = appState,
                            requestId = "screenshot-request",
                            payload =
                                SharePayload(
                                    text = "Planning notes for tomorrow",
                                    streamUris = emptyList(),
                                    intentMimeType = "text/plain",
                                ),
                            onDismiss = {},
                            onStage = { _, _ -> true },
                        )
                    }
                }
            }
        }
    }

    private fun hexId(byte: Int): String = byte.toString(16).padStart(2, '0').repeat(32)
}
