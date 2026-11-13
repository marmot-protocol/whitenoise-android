package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.group.ConversationNotificationSettingsScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileNotificationOverrideScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileNotificationOverrideState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every switch, link and action row on a settings subpage leads with its own glyph (#3799).
 *
 * The screens render tall enough that their lazy lists compose every group, so the checks see each
 * row a user can reach: Appearance with its readability preview expanded, both conversation
 * notification pages and the per-person override.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h4000dp-mdpi")
class SettingsLeadingIconCoverageTest(
    private val screen: Screen,
) {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Each settings page the icon audit covers beyond the hub. */
    enum class Screen {
        APPEARANCE,
        GROUP_NOTIFICATIONS,
        DIRECT_NOTIFICATIONS,
        PROFILE_OVERRIDE,
    }

    /** Every row in a settings group carries a leading glyph. */
    @Test
    fun everyRowHasLeadingIcon() {
        render()
        val rows = composeRule.onAllNodes(settingsRow).fetchSemanticsNodes()
        assertTrue("$screen rendered no settings rows", rows.isNotEmpty())
        val missing = rows.filter { SettingsLeadingIconKey !in it.config }.map { it.label() }
        assertEquals("$screen rows without a leading icon", emptyList<String>(), missing)
    }

    /** No glyph repeats within one group, so the icon column tells rows apart at a glance. */
    @Test
    fun noGlyphRepeatsWithinGroup() {
        render()
        val groups = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SettingsGroupKey)).fetchSemanticsNodes()
        assertTrue("$screen rendered no settings groups", groups.isNotEmpty())
        groups.forEach { group ->
            val icons = group.rows().mapNotNull { it.config.getOrNull(SettingsLeadingIconKey) }
            val repeated =
                icons
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            assertTrue(
                "$screen group repeats ${repeated.map(context.resources::getResourceEntryName)}",
                repeated.isEmpty(),
            )
        }
    }

    /** The glyphs are decorative and add no description for a screen reader to repeat. */
    @Test
    fun leadingIconsAreDecorative() {
        render()
        val described =
            composeRule
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SettingsLeadingIconKey) and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertTrue("$screen leading icons carry descriptions", described.isEmpty())
    }

    private val settingsRow =
        SemanticsMatcher.keyIsDefined(SettingsRowKey) and
            hasAnyAncestor(SemanticsMatcher.keyIsDefined(SettingsGroupKey))

    /** The rows below this group node, not descending into the controls a row nests in its end slot. */
    private fun SemanticsNode.rows(): List<SemanticsNode> =
        children.flatMap { child ->
            if (SettingsRowKey in child.config) listOf(child) else child.rows()
        }

    private fun SemanticsNode.label(): String {
        val text = config.getOrNull(SemanticsProperties.Text) ?: return "node $id"
        return text.joinToString { it.text }
    }

    private fun render() {
        when (screen) {
            Screen.APPEARANCE -> renderAppearance()
            Screen.GROUP_NOTIFICATIONS -> renderConversationNotifications(isDm = false)
            Screen.DIRECT_NOTIFICATIONS -> renderConversationNotifications(isDm = true)
            Screen.PROFILE_OVERRIDE ->
                composeRule.setContent {
                    WhiteNoiseTheme {
                        ProfileNotificationOverrideScreen(ProfileNotificationOverrideState(), {}, {}, {}, {})
                    }
                }
        }
        composeRule.waitForIdle()
    }

    /** Appearance with the readability preview expanded, so its RTL and large-text rows are included. */
    private fun renderAppearance() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "missing-account",
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, fontScale = appState.fontScale.factor, appFont = appState.appFont) {
                AppearanceScreen(appState = appState, onBack = {}, onOpenActionColor = {}, onOpenChatBubbleColors = {})
            }
        }
        val preview = context.getString(R.string.appearance_accessibility_preview)
        composeRule.onNodeWithTag("settings.list").performScrollToNode(hasText(preview))
        composeRule.onNodeWithText(preview).performScrollTo().performClick()
    }

    /** A chat's notification page once its channel categories have loaded. */
    private fun renderConversationNotifications(isDm: Boolean) {
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { "icon-coverage-self" },
                accounts = emptyList(),
                activeAccountRef = "icon-coverage-account",
                profileReader = { null },
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationNotificationSettingsScreen(
                    appState = appState,
                    groupIdHex = "icon-coverage-chat",
                    conversationTitle = "Icon coverage",
                    conversationAvatarUrl = null,
                    isDm = isDm,
                    isMuted = false,
                    muteCommandPending = false,
                    muteExpiryMillis = null,
                    notifyForMode = ChatNotifyMode.ALL,
                    vibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
                    onBack = {},
                    onToggleMute = {},
                    onChooseVibrationPattern = {},
                )
            }
        }
        val primary = if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithTag("open-conversation-notification-${primary.id}")
                .fetchSemanticsNodes()
                .size == 1
        }
    }

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = Screen.entries.map { arrayOf(it) }
    }
}
