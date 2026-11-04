package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
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
            followMovingTarget = true,
        )
    SideEffect {
        dictationDestination?.let { owner ->
            dictationMotion.reportSurface(
                owner,
                ControlMorphSurface(
                    Color.Transparent,
                    null,
                    0f,
                    0f,
                    if (owner == ConversationDictationControlOwner.Persistent) 1f else 0f,
                ),
            )
        }
    }
    val dictationMotionAllowed = rememberSystemAnimationsEnabled() && !appLockScreenVisible
    val persistentContainer =
        handoffDestination.takeIf { dictationDestination == ConversationDictationControlOwner.Persistent }
    val dictationRequest =
        remember(dictationMotion, dictationDestination, dictationMotionAllowed, persistentContainer) {
            // A rapid inline -> Chats -> another chat change restarts the existing command clock
            // from its current pixels alongside the new container motion. A settled floating owner
            // still follows the painted edge directly, without starting a second animation.
            dictationMotion.prepare(
                dictationDestination,
                dictationMotionAllowed,
                continueActiveMotion = persistentContainer != null,
            )
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
                        movingChromeSize = {
                            dictationMotion.renderedBounds?.size.takeIf { dictationMotion.active }
                        },
                        chromeVisibility = {
                            if (dictationMotion.active) {
                                dictationMotion.renderedDecorationAlpha
                            } else if (dictationControlOwner == ConversationDictationControlOwner.Persistent) {
                                1f
                            } else {
                                0f
                            }
                        },
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
            composerFabMotion = handoff,
        )
    }
}
