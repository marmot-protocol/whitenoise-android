package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.ui.conversation.media.FileTransferControl
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentTypeDescription
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentTypeLabel
import dev.ipf.whitenoise.android.ui.conversation.media.fileIconFor
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_ACTION_MENU_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageActionMenu
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationPolishScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actionGridLight() = captureActionMenu("message_action_menu_grid_light", dark = false, amoled = false)

    @Test
    fun actionGridDark() = captureActionMenu("message_action_menu_grid_dark", dark = true, amoled = false)

    @Test
    fun actionGridAmoled() = captureActionMenu("message_action_menu_grid_amoled", dark = true, amoled = true)

    @Test
    fun actionGridLargeFontFallback() =
        captureActionMenu(
            "message_action_menu_grid_font_scale_2x",
            dark = false,
            amoled = false,
            fontScale = 2f,
        )

    @Test
    fun deletedMessageDeleteOnlyActionLight() =
        captureDeletedMessageActionMenu(
            "deleted_message_delete_only_action_light",
            dark = false,
            amoled = false,
        )

    @Test
    fun deletedMessageDeleteOnlyActionDark() =
        captureDeletedMessageActionMenu(
            "deleted_message_delete_only_action_dark",
            dark = true,
            amoled = false,
        )

    @Test
    fun deletedMessageDeleteOnlyActionAmoled() =
        captureDeletedMessageActionMenu(
            "deleted_message_delete_only_action_amoled",
            dark = true,
            amoled = true,
        )

    @Test
    fun deletedMessageDeleteOnlyActionLargeFontRtl() =
        captureDeletedMessageActionMenu(
            "deleted_message_delete_only_action_font_scale_2x_rtl",
            dark = false,
            amoled = false,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
        )

    @Test
    fun attachmentTypesLight() = captureAttachmentTypes("attachment_type_gallery_light", dark = false, amoled = false)

    @Test
    fun attachmentTypesDark() = captureAttachmentTypes("attachment_type_gallery_dark", dark = true, amoled = false)

    @Test
    fun attachmentTypesAmoled() = captureAttachmentTypes("attachment_type_gallery_amoled", dark = true, amoled = true)

    @Test
    fun attachmentTransferStatesLight() {
        captureAttachmentTransferStates("attachment_transfer_states_light", dark = false, amoled = false)
    }

    @Test
    fun attachmentTransferStatesAmoled() {
        captureAttachmentTransferStates("attachment_transfer_states_amoled", dark = true, amoled = true)
    }

    private fun captureActionMenu(
        name: String,
        dark: Boolean,
        amoled: Boolean,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MaximumActionMenu()
                }
            }
        }
        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun captureDeletedMessageActionMenu(
        name: String,
        dark: Boolean,
        amoled: Boolean,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    DeletedMessageActionMenu()
                }
            }
        }
        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    @Composable
    private fun MaximumActionMenu() {
        MessageActionMenu(
            expanded = true,
            anchorBoundsInWindow = null,
            anchorWindowYPx = 8f,
            canReply = true,
            canReact = true,
            canDelete = true,
            canEdit = true,
            canForward = true,
            canSelect = true,
            canCopyText = true,
            canSpeak = true,
            canSelectText = true,
            canSave = true,
            quickReactionEmojis = listOf("👍", "❤️", "😂", "😮", "😢"),
            onDismissRequest = {},
            onReact = {},
            onOpenEmojiPicker = {},
            onReply = {},
            onEdit = {},
            onForward = {},
            onSelect = {},
            onSelectText = {},
            onCopyText = {},
            onSpeak = {},
            onSave = {},
            onInfo = {},
            onDelete = {},
        )
    }

    @Composable
    private fun DeletedMessageActionMenu() {
        MessageActionMenu(
            expanded = true,
            anchorBoundsInWindow = null,
            anchorWindowYPx = 8f,
            canReply = false,
            canReact = false,
            canDelete = true,
            canEdit = false,
            canForward = false,
            canSelect = false,
            canCopyText = false,
            canSpeak = false,
            canSelectText = false,
            canSave = false,
            canInfo = false,
            quickReactionEmojis = emptyList(),
            onDismissRequest = {},
            onReact = {},
            onOpenEmojiPicker = {},
            onReply = {},
            onEdit = {},
            onForward = {},
            onSelect = {},
            onSelectText = {},
            onCopyText = {},
            onSpeak = {},
            onSave = {},
            onInfo = {},
            onDelete = {},
        )
    }

    private fun captureAttachmentTypes(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                AttachmentTypeGallery()
            }
        }
        composeRule.onNodeWithTag(ATTACHMENT_GALLERY_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun captureAttachmentTransferStates(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Surface(modifier = Modifier.width(240.dp).testTag(ATTACHMENT_TRANSFER_GALLERY_TAG)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        listOf(
                            AttachmentTransferState.Remote,
                            AttachmentTransferState.Downloading,
                            AttachmentTransferState.Failed,
                            AttachmentTransferState.Available,
                        ).forEach { state ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = state.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                FileTransferControl(
                                    presentation = resolveAttachmentPresentation("application/pdf", "sample.pdf"),
                                    transferState = state,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule
            .onNodeWithTag(ATTACHMENT_TRANSFER_GALLERY_TAG)
            .captureRoboImage("src/test/snapshots/$name.png")
    }

    @Composable
    private fun AttachmentTypeGallery() {
        val rows =
            listOf(
                "WhiteNoise-release-universal.apk" to "application/vnd.android.package-archive",
                "Protocol architecture.pdf" to "application/pdf",
                "encrypted-export.tar.gz" to "application/octet-stream",
                "community-budget.xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "integration-config.json" to "application/json",
                "unknown-board-layout.pcb" to "application/vnd.example.board-layout",
            )
        Surface(modifier = Modifier.width(328.dp).testTag(ATTACHMENT_GALLERY_TAG)) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (fileName, mediaType) ->
                    val presentation = resolveAttachmentPresentation(mediaType, fileName)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            fileIconFor(presentation.iconCategory),
                            contentDescription = attachmentTypeDescription(presentation.iconCategory),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column {
                            Text(
                                fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                attachmentTypeLabel(presentation),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val ATTACHMENT_GALLERY_TAG = "attachment-type-gallery"
        const val ATTACHMENT_TRANSFER_GALLERY_TAG = "attachment-transfer-gallery"
    }
}
