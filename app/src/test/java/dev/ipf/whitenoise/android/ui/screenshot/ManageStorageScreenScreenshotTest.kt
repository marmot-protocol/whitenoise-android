package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.ChatStorageEntry
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.KeepMediaPeriod
import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.StorageMigration
import dev.ipf.whitenoise.android.state.StorageUsage
import dev.ipf.whitenoise.android.state.StoredAttachment
import dev.ipf.whitenoise.android.state.StoredAttachmentsUiState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.LocalStorageChatTitles
import dev.ipf.whitenoise.android.ui.settings.ManageStorageActions
import dev.ipf.whitenoise.android.ui.settings.ManageStorageContent
import dev.ipf.whitenoise.android.ui.settings.StoredAttachmentsActions
import dev.ipf.whitenoise.android.ui.settings.StoredAttachmentsContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Manage Storage: the overview's usage, actions and chats, its loading and empty states, and attachment lists. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1000dp-mdpi")
class ManageStorageScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Light theme with figures, Keep media and chats. */
    @Test
    @Config(qualifiers = "w360dp-h1500dp-mdpi")
    fun overviewLight() = overview("manage_storage_light", Populated, darkTheme = false)

    /** Dark theme. */
    @Test
    @Config(qualifiers = "w360dp-h1500dp-mdpi")
    fun overviewDark() = overview("manage_storage_dark", Populated, darkTheme = true)

    /** AMOLED: outlined groups on black. */
    @Test
    @Config(qualifiers = "w360dp-h1500dp-mdpi")
    fun overviewAmoled() = overview("manage_storage_amoled", Populated, darkTheme = true, amoled = true)

    /** RTL at 200 %: the legend and chat sizes wrap and mirror. */
    @Test
    @Config(qualifiers = "w360dp-h2600dp-mdpi")
    fun overviewRtlLargeFont() {
        overview("manage_storage_rtl_large_font", Populated, darkTheme = false, fontScale = 2f, rtl = true)
    }

    /** Before the first figures: Calculating over a pulsing track, actions disabled. */
    @Test
    fun overviewLoading() = overview("manage_storage_loading", ManageStorageUiState(), darkTheme = false)

    /** A paused migration explains what is needed while chats are still loading. */
    @Test
    fun overviewMigrationEmpty() = overview("manage_storage_migration_empty", MigrationEmpty, darkTheme = false)

    /** The account's largest attachments with two selected and the selection bar. */
    @Test
    fun largestSelecting() = attachments("manage_storage_largest_selecting", groupIdHex = null, darkTheme = false)

    /** One chat's actions above its attachments, in the dark theme. */
    @Test
    fun chatAttachmentsDark() = attachments("manage_storage_chat_dark", groupIdHex = DESIGN, darkTheme = true)

    /** Records the overview for [state]. */
    @Suppress("LongParameterList")
    private fun overview(
        name: String,
        state: ManageStorageUiState,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val appState = testAppState(name)
        render(darkTheme, amoled, fontScale, rtl) {
            ManageStorageContent(appState, state, onBack = {}, actions = ManageStorageActions({}, {}, {}, {}))
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Records an attachment list, account-wide when [groupIdHex] is null, with two rows selected. */
    private fun attachments(
        name: String,
        groupIdHex: String?,
        darkTheme: Boolean,
    ) {
        val appState = testAppState(name)
        val rows = if (groupIdHex == null) LargestRows else LargestRows.filter { it.groupIdHex == groupIdHex }
        val list =
            StoredAttachmentsUiState(
                items = rows,
                complete = true,
                selected = if (groupIdHex == null) rows.take(2).mapTo(HashSet()) { it.key } else emptySet(),
            )
        render(darkTheme) {
            StoredAttachmentsContent(
                appState = appState,
                storage = Populated,
                list = list,
                groupIdHex = groupIdHex,
                onBack = {},
                actions = StoredAttachmentsActions({}, {}, {}, {}, {}),
            )
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Sets [content] in the app theme with fixed chat titles. */
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalStorageChatTitles provides { ChatTitles[it] },
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) { content() }
            }
        }
        composeRule.waitForIdle()
    }

    /** One signed-in account on its own cleared preference file. */
    private fun testAppState(name: String): WhiteNoiseAppState {
        val preferences = context.getSharedPreferences("manage-storage-screenshot-$name", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = "ab".repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
            preferences = preferences,
        )
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val MB = 1_000_000L
        val DESIGN = "d1".repeat(32)
        val FAMILY = "f2".repeat(32)
        val ANA = "a3".repeat(32)
        val ChatTitles = mapOf(DESIGN to "Design team", FAMILY to "Family", ANA to "Ana")

        val Populated =
            ManageStorageUiState(
                usage = StorageUsage(1_910 * MB, 214, 310 * MB, 40 * MB, 96 * MB, 54 * MB),
                keepMedia = KeepMediaPeriod.Forever,
                chats =
                    listOf(
                        ChatStorageEntry(DESIGN, 1_120 * MB, 88),
                        ChatStorageEntry(FAMILY, 610 * MB, 96),
                        ChatStorageEntry(ANA, 180 * MB, 30),
                    ),
                chatsComplete = true,
            )

        val MigrationEmpty =
            ManageStorageUiState(
                usage = StorageUsage(860 * MB, 41, 1_240 * MB, 0, 18 * MB, 0, StorageMigration.Paused, 220 * MB),
                keepMedia = KeepMediaPeriod.ThirtyDays,
            )

        val LargestRows =
            listOf(
                stored(DESIGN, "m1", 412 * MB, "video/mp4"),
                stored(FAMILY, "m2", 188 * MB, "video/mp4"),
                stored(DESIGN, "m3", 64 * MB, "application/pdf"),
                stored(ANA, "m4", 12 * MB, "image/jpeg"),
                stored(DESIGN, "m5", 3 * MB, "audio/ogg"),
            )

        /** A retained attachment received at a fixed instant, so relative times stay stable. */
        fun stored(
            group: String,
            message: String,
            bytes: Long,
            type: String,
        ) = StoredAttachment(group, message.padEnd(64, '0'), message.padEnd(64, '0'), 0, bytes, type, 0)
    }
}
