package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.messages.FORWARD_CHAT_PICKER_SCREEN_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.FORWARD_FOLDER_CHIP_ROW_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessagePickerContent
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.appStateWithDirectChats
import dev.ipf.whitenoise.android.ui.share.profile
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ForwardMessagePickerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The no-folder picker keeps its existing destination-first dark presentation. */
    @Test
    fun multiMessageMediaPickerDark() {
        renderPicker(
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            snapshotPath = "src/test/snapshots/forward_message_picker_dark.png",
            folderFixture = FolderFixture.NONE,
        )
    }

    /** A mixed-selection folder remains compact beside enlarged destination text. */
    @Test
    fun multiMessageMediaPickerLargeFont() {
        renderPicker(
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Ltr,
            snapshotPath = "src/test/snapshots/forward_message_picker_large_font.png",
            folderFixture = FolderFixture.ONE,
        )
    }

    /** Narrow RTL with enlarged Arabic folder labels keeps destination rows reachable below one chip region. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h700dp-mdpi")
    fun multiMessageMediaPickerRtl() {
        renderPicker(
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Rtl,
            snapshotPath = "src/test/snapshots/forward_message_picker_rtl.png",
            folderFixture = FolderFixture.MANY,
        )
    }

    /** Light appearance uses the shared chat-list chip language for the single-folder case. */
    @Test
    fun oneFolderPickerLight() {
        renderPicker(
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            snapshotPath = "src/test/snapshots/forward_message_picker_folder_light.png",
            darkTheme = false,
            folderFixture = FolderFixture.ONE,
        )
    }

    /** Many long labels stay in a bounded horizontal control region on AMOLED. */
    @Test
    fun manyLongFoldersPickerAmoled() {
        renderPicker(
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            snapshotPath = "src/test/snapshots/forward_message_picker_folders_amoled.png",
            darkTheme = true,
            amoled = true,
            folderFixture = FolderFixture.MANY,
        )
    }

    /** Captures the production picker before scrolling, then proves destinations remain reachable. */
    private fun renderPicker(
        fontScale: Float,
        layoutDirection: LayoutDirection,
        snapshotPath: String,
        darkTheme: Boolean = true,
        amoled: Boolean = false,
        folderFixture: FolderFixture,
    ) {
        val (appState, groupIds) = pickerFixture(folderFixture, layoutDirection == LayoutDirection.Rtl)

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface {
                        ForwardMessagePickerContent(
                            appState = appState,
                            messageCount = 11,
                            attachmentCount = 11,
                            originGroupIdHex = "ff".repeat(32),
                            sourceAccountRef = ACCOUNT_REF,
                            onDismiss = {},
                            onForward = { _, _ -> true },
                            initialSelectedGroupIds = folderFixture.initialSelection(groupIds),
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG)
            .captureRoboImage(snapshotPath)
        if (folderFixture == FolderFixture.NONE) {
            composeRule.onNodeWithTag(FORWARD_FOLDER_CHIP_ROW_TEST_TAG).assertDoesNotExist()
        } else {
            val region = composeRule.onNodeWithTag(FORWARD_FOLDER_CHIP_ROW_TEST_TAG).getUnclippedBoundsInRoot()
            assertTrue("folder controls must remain one bounded row", (region.bottom - region.top).value <= 80f)
        }
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Person 8"))
        composeRule.onNodeWithText("Person 8").assertIsDisplayed()
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
    }

    /** Creates the real picker state and local folder fixtures before composition begins. */
    private fun pickerFixture(
        folderFixture: FolderFixture,
        rtl: Boolean,
    ): Pair<WhiteNoiseAppState, List<String>> {
        val chats = (0 until 8).map { index -> hexId(0x20 + index) to hexId(0x40 + index) }
        val profiles =
            chats
                .mapIndexed { index, (_, peerId) -> peerId to profile("Person ${index + 1}") }
                .toMap(mutableMapOf())
        val appState =
            appStateWithDirectChats(
                *chats.toTypedArray(),
                profiles = profiles,
                accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX)),
            )
        appState.chatFolderPreferences.clearAllForAccount(ACCOUNT_REF)
        val groupIds = chats.map { it.first }
        seedFolders(
            appState = appState,
            groupIds = groupIds,
            fixture = folderFixture,
            rtl = rtl,
        )
        return appState to groupIds
    }

    /** Seeds only local folder presentation fixtures, including intersecting memberships for mixed selection. */
    private fun seedFolders(
        appState: WhiteNoiseAppState,
        groupIds: List<String>,
        fixture: FolderFixture,
        rtl: Boolean,
    ) {
        val names =
            when (fixture) {
                FolderFixture.NONE -> emptyList()
                FolderFixture.ONE -> listOf("Close friends and family")
                FolderFixture.MANY ->
                    if (rtl) {
                        listOf(
                            "تنسيق إصدار مشروع مارموت",
                            "خطط العائلة عبر المناطق الزمنية",
                            "أصدقاء المشي والتسلق في عطلة نهاية الأسبوع",
                            "متطوعو المساعدة المتبادلة في الحي",
                            "شركاء مراجعة التصميم والبحث",
                            "تخطيط السفر للمجموعة بأكملها",
                        )
                    } else {
                        listOf(
                            "Project Marmot release coordination",
                            "Family plans across every time zone",
                            "Weekend trail and climbing friends",
                            "Neighborhood mutual aid volunteers",
                            "Design review and research partners",
                            "Travel planning for the whole group",
                        )
                    }
            }
        names.forEachIndexed { index, name ->
            val folder =
                requireNotNull(appState.chatFolderPreferences.createFolder(ACCOUNT_REF, name))
            listOf(groupIds[index % groupIds.size], groupIds[(index + 1) % groupIds.size]).forEach { groupId ->
                appState.chatFolderPreferences.setChatInFolder(
                    accountRef = ACCOUNT_REF,
                    folderId = folder.id,
                    chatId = groupId,
                    included = true,
                )
            }
        }
    }

    private fun hexId(byte: Int): String = byte.toString(16).padStart(2, '0').repeat(32)

    private enum class FolderFixture {
        NONE,
        ONE,
        MANY,
        ;

        /** Seeds a deterministic Off/mixed state for each rendered fixture class. */
        fun initialSelection(groupIds: List<String>): List<String> =
            when (this) {
                NONE -> emptyList()
                ONE -> listOf(groupIds.first())
                MANY -> groupIds.take(2)
            }
    }
}
