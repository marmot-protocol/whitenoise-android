package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.keptMessagePresentations

/** Builds a synthetic projected attachment; neither its name nor its bytes come from user data. */
internal fun keptMediaTestMessage(
    mediaType: String,
    name: String,
    caption: String = "",
): TimelineMessage {
    val surface =
        swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = false, media = true)
    val projected = surface.item.projected!!
    val reference =
        MessageAttachments.acceptedReferences(projected.media).single().copy(mediaType = mediaType, fileName = name)
    return surface.item.copy(
        record = surface.item.record.copy(plaintext = caption),
        projected = projected.copy(plaintext = caption, media = MessageAttachments.acceptedOutcomes(listOf(reference))),
    )
}

/** Hosts the production mapper, stack and card; null simulates the native row leaving the retained window. */
@Composable
internal fun KeptMediaTestHost(
    item: TimelineMessage?,
    onOpen: (KeptMessageKey) -> Unit = {},
    presentationOverride: ((KeptMessagePresentation) -> KeptMessagePresentation)? = null,
) {
    val key = KeptMessageKey(SWIPE_TEST_ACCOUNT_REF, SWIPE_TEST_GROUP_ID, SWIPE_TEST_MESSAGE_ID)
    val controller = remember { KeptMessagesController().apply { keep(key) } }
    Box(Modifier.fillMaxSize().testTag("kept-media-fixture")) {
        val entries = rememberKeptMessageEntries(controller, SWIPE_TEST_ACCOUNT_REF) { item }
        val presentations = keptMessagePresentations(entries, "Design", "You", { false }, { "Alice" })
        KeptMessagesOverlay(
            state = KeptMessagesOverlayState(entries, controller, SWIPE_TEST_ACCOUNT_REF),
            composerHeight = 96.dp,
            presentation = {
                val original = presentations.getValue(it.key)
                presentationOverride?.invoke(original) ?: original
            },
            onOpenMessage = onOpen,
        )
    }
}
