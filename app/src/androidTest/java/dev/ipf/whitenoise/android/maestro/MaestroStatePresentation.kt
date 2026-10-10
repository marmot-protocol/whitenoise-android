package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.notifications.AndroidNotificationSettingsTarget
import dev.ipf.whitenoise.android.notifications.ConversationNotificationCategorySetting
import dev.ipf.whitenoise.android.notifications.ConversationNotificationScope
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.state.WipeFailureItem
import dev.ipf.whitenoise.android.state.WipeReport
import dev.ipf.whitenoise.android.state.WipeStage
import dev.ipf.whitenoise.android.state.WipeStageReport
import dev.ipf.whitenoise.android.ui.group.ConversationNotificationCategoriesList
import dev.ipf.whitenoise.android.ui.settings.BugReportContent
import dev.ipf.whitenoise.android.ui.settings.ConversationFixtureSeedDialog
import dev.ipf.whitenoise.android.ui.settings.SignOutProgressDialog
import dev.ipf.whitenoise.android.ui.settings.WipeOutcomeSheet
import dev.ipf.whitenoise.android.ui.settings.WipeProgressSheet

/** Native account state stays intact while the actual result, disclosure and developer chrome is exercised. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroStatePresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario == "extra-wait-signout" -> SignOutProgressDialog()
        fixture.scenario == "extra-wait-wipe" -> WipeProgressSheet()
        fixture.scenario.startsWith("extra-help-") ->
            BugReportContent(
                onBack = { fixture.finish("dismiss") },
                onOpenReport = {
                    fixture.record("open-refused")
                    false
                },
            )
        fixture.scenario.startsWith("extra-seed-") ->
            ConversationFixtureSeedDialog(fixture.appState, onDismiss = { fixture.finish("dismiss") })
        fixture.scenario.startsWith("extra-wipe-outcome-") ->
            WipeOutcomeSheet(
                report =
                    WipeReport(
                        listOf(
                            WipeStageReport(WipeStage.LeavingGroups, 2, emptyList()),
                            WipeStageReport(
                                WipeStage.DeletingKeyPackages,
                                0,
                                listOf(WipeFailureItem(null, "Fixture failure")),
                            ),
                            WipeStageReport(WipeStage.WipingLocalData, null, emptyList()),
                        ),
                    ),
                onDismiss = { fixture.finish("dismiss") },
            )
        else -> NotificationScopePresentation(fixture)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun NotificationScopePresentation(fixture: MaestroPresentationFixture) {
    val setting =
        ConversationNotificationCategorySetting(
            channel = NotificationChannelSpec.GROUP_MESSAGES,
            scope = ConversationNotificationScope.USE_GLOBAL_DEFAULT,
            canChangeScope = true,
            settingsTarget = AndroidNotificationSettingsTarget.Global(NotificationChannelSpec.GROUP_MESSAGES),
        )
    ConversationNotificationCategoriesList(
        settings = listOf(setting),
        onOpen = { error("Scope test must not open Android settings") },
        onScopeChange = { selected, custom ->
            check(selected == setting && custom)
            fixture.finish("scope-selected")
        },
    )
}
