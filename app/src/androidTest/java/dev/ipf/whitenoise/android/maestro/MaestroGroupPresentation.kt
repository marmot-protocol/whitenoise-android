package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.media.ImageSearchClient
import dev.ipf.whitenoise.android.media.ImageSearchException
import dev.ipf.whitenoise.android.media.ImageSearchResult
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.ui.chats.ChatLeaveAndDeleteConfirmationDialog
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEntityPickerSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.group.DisappearingMessagesPickerDialog
import dev.ipf.whitenoise.android.ui.group.GroupEmojiImagePickerSheet
import dev.ipf.whitenoise.android.ui.group.GroupInfoScreen
import dev.ipf.whitenoise.android.ui.group.ImageSearchSheet
import dev.ipf.whitenoise.android.ui.group.MuteDurationDialog
import dev.ipf.whitenoise.android.ui.group.NotifyForDialog
import dev.ipf.whitenoise.android.ui.group.SoleAdminDeletePicker
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Actual group chrome, with bounded callback ports instead of group mutations or public search. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroGroupPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("group-ui-info-") ->
            GroupInfoScreen(
                groupIdHex = "11".repeat(32),
                nostrGroupIdHex = "22".repeat(32),
                relays =
                    if (fixture.scenario.endsWith("empty")) emptyList() else listOf("wss://fixture.example.invalid"),
                onBack = { fixture.finish("dismiss") },
            )
        fixture.scenario.startsWith("group-ui-notify-") ->
            NotifyForDialog(
                currentMode = ChatNotifyMode.ALL,
                onDismiss = { fixture.finish("dismiss") },
                onSelect = {
                    val expected =
                        if (fixture.scenario.endsWith("mentions")) ChatNotifyMode.MENTIONS_ONLY else ChatNotifyMode.ALL
                    check(it == expected)
                    fixture.finish("notify-selected")
                },
            )
        fixture.scenario.startsWith("group-ui-mute-") ->
            MuteDurationDialog(
                onDismiss = { fixture.finish("dismiss") },
                onSelect = { error("Cancellation must not apply a mute") },
                nowMillis = { 1_800_000_000_000L },
                zoneId = { ZoneOffset.UTC },
                initialCustomDateTime = LocalDateTime.of(2027, 1, 16, 12, 0),
            )
        fixture.scenario.startsWith("group-ui-disappearing-") ->
            DisappearingMessagesPickerDialog(
                currentSecs = 0,
                onDismiss = { fixture.finish("dismiss") },
                onPick = { error("Cancellation must not persist a timer") },
            )
        fixture.scenario.startsWith("group-ui-entity-") ||
            fixture.scenario.startsWith("group-ui-choice-") ||
            fixture.scenario.startsWith("group-ui-leave-") -> EntityPresentation(fixture)
        fixture.scenario == "group-ui-successor-empty" ->
            SoleAdminDeletePicker(
                candidates = emptyList(),
                appState = fixture.appState,
                onPick = { error("Empty successor list cannot select a member") },
                onDismiss = { fixture.finish("dismiss") },
            )
        else -> GroupImagePresentation(fixture)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun EntityPresentation(fixture: MaestroPresentationFixture) {
    when (fixture.scenario) {
        "group-ui-leave-cancel", "group-ui-leave-confirm" ->
            ChatLeaveAndDeleteConfirmationDialog(
                onConfirm = { fixture.finish("confirm") },
                onDismiss = { fixture.finish("dismiss") },
            )
        "group-ui-choice-cancel", "group-ui-choice-select" ->
            ChoiceDialog(
                title = "Fixture choices",
                values = listOf("Fixture first", "Fixture second"),
                selected = "Fixture first",
                label = { it },
                onDismiss = { fixture.finish("dismiss") },
                onSelect = {
                    check(it == "Fixture second")
                    fixture.finish("choice-selected")
                },
            )
        else ->
            WhiteNoiseEntityPickerSheet(
                title = "Fixture entities",
                items =
                    if (fixture.scenario.endsWith("empty")) {
                        emptyList()
                    } else {
                        listOf(
                            WhiteNoisePickerItem(
                                "fixture",
                                "Fixture member",
                                enabled = !fixture.scenario.endsWith("disabled"),
                            ),
                        )
                    },
                onDismiss = { fixture.finish("dismiss") },
                onSelect = {
                    check(it == "fixture" && fixture.scenario.endsWith("select"))
                    fixture.finish("entity-selected")
                },
            )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun GroupImagePresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario == "group-ui-emoji-empty") {
        GroupEmojiImagePickerSheet(
            applyInFlight = false,
            recentEmojis = emptyList(),
            onEmojiUsed = { error("Empty preview must not record an emoji") },
            onApply = { error("Empty preview must not produce a group image") },
            onDismiss = { fixture.finish("dismiss") },
            renderer = { error("Empty preview must not render an image") },
        )
    } else {
        ImageSearchSheet(
            initialUrl = "",
            header = "Fixture image chooser",
            title = "Fixture group",
            seed = "fixture-group",
            urlLabel = "Fixture image URL",
            applyInFlight = false,
            onApply = { error("Search cancellation must not publish an image") },
            onDismiss = { fixture.finish("dismiss") },
            searchClient =
                object : ImageSearchClient {
                    override suspend fun search(query: String): List<ImageSearchResult> {
                        check(query == "fixture search")
                        fixture.record("search-requested")
                        if (fixture.scenario.endsWith("error")) throw ImageSearchException.BadResponse()
                        return emptyList()
                    }
                },
            resultImageLoader = { error("Empty results must not load external image bytes") },
            resultImageCacheLookup = { null },
        )
    }
}
