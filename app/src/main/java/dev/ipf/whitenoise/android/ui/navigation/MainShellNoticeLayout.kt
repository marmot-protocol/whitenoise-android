package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.common.LocalFinishControlMorph
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoffHost
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.conversation.LocalShellPlaybackHost
import dev.ipf.whitenoise.android.ui.conversation.ShellPlaybackHost
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationNotificationNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationPersistentControl

/** The shell's single notice layout, using control ownership from this same navigation composition. */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod") // One shell layout owns notice and control slots.
internal fun MainShellNoticeLayout(
    notice: TransientNotice?,
    dictationController: ConversationDictationController,
    dictationComposerRoute: ConversationDictationComposerRoute,
    appLockScreenVisible: Boolean,
    persistentTopContent: @Composable () -> Unit = {},
    persistentTopContentConsumesStatusBars: Boolean = false,
    playbackHost: ShellPlaybackHost? = null,
    handoffOwner: Any? = null,
    handoffDestination: ComposerFabEndpoint? = null,
    content: @Composable (ConversationDictationControlOwner) -> Unit,
) {
    val dictationControlOwner =
        conversationDictationControlOwner(
            state = dictationController.state,
            route = dictationComposerRoute,
            appLockScreenVisible = appLockScreenVisible,
            recoveryHandedToComposer = dictationController.recoveryHandedToComposer,
        )
    val anchor = remember(handoffOwner, dictationController.state.sessionId) { FloatingDictationAnchor() }
    val dictationDestination = dictationControlOwner.takeUnless { it == ConversationDictationControlOwner.Hidden }
    val dictationMotion =
        rememberMeasuredControlHandoff(
            handoffOwner to dictationController.state.sessionId to appLockScreenVisible,
            dictationDestination,
            morphSurface = true,
        )
    val dictationMotionAllowed = rememberSystemAnimationsEnabled() && !appLockScreenVisible
    val dictationRequest =
        remember(dictationMotion, dictationDestination, dictationMotionAllowed) {
            dictationMotion.prepare(dictationDestination, dictationMotionAllowed)
        }
    LaunchedEffect(dictationMotion, dictationRequest) { dictationMotion.animatePrepared(dictationRequest) }
    val handoff =
        rememberMeasuredControlHandoff(
            handoffOwner to appLockScreenVisible,
            handoffDestination,
            morphSurface = true,
            followMovingTarget = true,
        )
    CompositionLocalProvider(
        LocalShellPlaybackHost provides playbackHost,
        LocalFloatingDictationAnchor provides anchor,
        LocalShellDictationControls provides anchor,
        LocalComposerFabHandoff provides handoff,
        LocalFinishControlMorph provides {
            handoff.finish()
            dictationMotion.finish()
        },
    ) {
        FloatingDictationLayout(
            anchor = anchor,
            content = {
                // Composer chrome bridges above the route body but below the one live dictation action set.
                MeasuredControlHandoffHost(
                    state = handoff,
                    destination = handoffDestination,
                    modifier = Modifier.composerFabPenDecoration(handoff),
                    allowed = !appLockScreenVisible && handoffDestination != null,
                ) {
                    ShellTransientNoticeLayout(
                        notice = notice,
                        persistentTopContent = persistentTopContent,
                        persistentTopContentConsumesStatusBars = persistentTopContentConsumesStatusBars,
                        persistentBottomContent = {
                            if (!appLockScreenVisible) {
                                if (dictationController.hasDurableSession &&
                                    dictationController.completionControlsRequired
                                ) {
                                    Box(Modifier.floatingDictationAnchor()) {
                                        ConversationDictationNotificationNotice()
                                    }
                                }
                            }
                        },
                        content = { content(dictationControlOwner) },
                    )
                }
            },
            control = {
                if (dictationControlOwner != ConversationDictationControlOwner.Hidden) {
                    ConversationDictationPersistentControl(
                        state = dictationController.state,
                        controller = dictationController,
                        embedded = dictationControlOwner == ConversationDictationControlOwner.Composer,
                        actionColors =
                            if (dictationControlOwner ==
                                ConversationDictationControlOwner.Composer
                            ) {
                                anchor.composerControlColors ?: accountActionColors(null)
                            } else {
                                accountActionColors(null)
                            },
                    )
                }
            },
            inlineControl = dictationControlOwner == ConversationDictationControlOwner.Composer,
            controlMotion = dictationMotion,
        )
    }
}
