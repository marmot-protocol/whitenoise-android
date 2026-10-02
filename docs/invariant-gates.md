# Invariant gates

An invariant gate is a test that enforces a behavioral rule across every call
site that could break it, rather than one reported symptom. Most gates are
source-inspecting `*CoverageTest.kt` classes built on
[`SourceBlockTestHelpers.kt`](../app/src/test/java/dev/ipf/whitenoise/android/SourceBlockTestHelpers.kt).
This page is the checked-in registry: it names the invariant each gate owns and
the production primitive or boundary it protects.

## Bug-fix requirement

A pull request that closes a bug (an issue with the native `Bug` type or the
`bug` label) must do one of the following, stated in its description as a single
line of visible prose (not inside code blocks or HTML comments):

1. **Add or extend a gate.** Change a test registered below, or add a new
   `*CoverageTest.kt` and its registry row. The changed registered test file
   satisfies the check, and naming the gate is still recommended:
   `Invariant gate: FooCoverageTest`.
2. **Name an already-applicable gate.** When an existing registered gate already
   enforces the invariant the fix restores, name it:
   `Invariant gate: FooCoverageTest` (several names may be comma-separated).
   Every name must be a registered gate.
3. **Declare an allowed exemption** with one reason key and an explanation:
   `Invariant gate exemption: <reason> — <why no reusable gate applies>`.

| Reason key | When it applies |
| --- | --- |
| `one-off` | A one-off value or presentation correction for which no reusable behavioral invariant exists. |
| `upstream` | The behavior is owned and enforced upstream, for example in MDK. |
| `non-production` | A source-only or non-production change (tests, tooling, docs, CI) that cannot alter the reported behavior. |

An invariant already enforced by a registered gate is declared with
`Invariant gate:` (path 2), not with an exemption. An unexplained `none` or an
unknown reason key fails the check.

The `Invariant gate` workflow
([`invariant-gate.yml`](../.github/workflows/invariant-gate.yml)) runs
[`check-invariant-gate.js`](../.github/scripts/check-invariant-gate.js) on every
pull request to `master`, including description edits. The checker runs from the
trusted base revision, so a pull request cannot rewrite the check that judges it,
and the pull request's registry is read only as data. It reads the closing issue
references through the GitHub API, so it needs no secrets and runs on fork pull
requests. Pull requests that close no bug, such as feature-only work, pass
without a declaration. The check is separate from, and does not change, the
visual-evidence gate in [Screenshot tests](../README.md#screenshot-tests).

## Maintaining the registry

[`InvariantGateRegistryTest`](../app/src/test/java/dev/ipf/whitenoise/android/InvariantGateRegistryTest.kt)
runs in the ordinary unit suite and fails when a `*CoverageTest.kt` file is not
registered, when a gate name or path is registered twice, when a registered path
no longer exists, when a row's name differs from its file name, or when a row
leaves its invariant or owner blank. Keep the table sorted by gate name.

- **Add:** create the test, then add one row in the same pull request. Link the
  gate name to the test path relative to this page, state the invariant as a rule
  rather than a symptom, and name the owning primitive or boundary (`None` when
  the rule has no single owner). Other test classes may be registered when they
  enforce an invariant, and their file then joins the gate-change path.
- **Rename:** move the test and update the row's name and path together. The
  consistency test rejects both the stale path and the unregistered new file, so
  a rename cannot silently drop coverage.
- **Retire:** delete the row only in the pull request that deletes or folds the
  test, and name the replacement gate in the description when the invariant
  survives. Never remove a row to make a failing gate pass.

## Registry

<!-- invariant-gates:start -->
| Gate | Invariant | Owning primitive or boundary |
| --- | --- | --- |
| [`ActionColorSurfaceCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/ActionColorSurfaceCoverageTest.kt) | Action, unread and bubble colours resolve through the active account's action token, and colour drafts never leak across accounts, chats or themes. | Account action colour token and `ui/settings/ChatBubbleColorsScreen.kt` drafts |
| [`AmberLoginReconciliationCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/AmberLoginReconciliationCoverageTest.kt) | An external-signer login reconciles the runtime before the account is exposed, and only an explicit legacy refusal falls back to legacy login. | `state/AppState.kt` login and activation path |
| [`AndroidPrPreviewWorkflowCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/AndroidPrPreviewWorkflowCoverageTest.kt) | Optimizer diagnostics stay bound to the same unsigned preview APK and never enter the publisher's input. | `android-pr-apk.yml` and `android-pr-preview-publish.yml` workflows |
| [`AndroidReleaseRuntimeWorkflowCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/AndroidReleaseRuntimeWorkflowCoverageTest.kt) | The required release-runtime check aggregates production and Android 17 staging verification, and a Firebase exemption cannot authorize Play publication. | `android-release-runtime.yml` and `scripts/verify-release-runtime.sh` |
| [`AppStateSendLockCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/AppStateSendLockCoverageTest.kt) | Every AppState send path outside the conversation controller takes the send lock, and notification actions stay blocked behind the app lock. | `state/AppState.kt` send, forward and notification-reply paths |
| [`AuditLogDeleteSemanticsCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/AuditLogDeleteSemanticsCoverageTest.kt) | App-side diagnostic cleanup cannot mask a failed native audit deletion. | `state/AppState.kt` audit-log delete path |
| [`BatchDeleteControllerCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/BatchDeleteControllerCoverageTest.kt) | Message deletion authorizes before any optimistic or native mutation, and batch retry state is scoped to the conversation owner. | Conversation controller delete path and `ui/navigation/MainShell.kt` batch state |
| [`ChatListBulkDeleteCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/chats/ChatListBulkDeleteCoverageTest.kt) | Bulk local delete wipes device data only and never leaves the group. | `ui/chats/ChatsScreen.kt` and the local-wipe path in `state/Controllers.kt` |
| [`ChatListFolderFilterNavigationCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/chats/ChatListFolderFilterNavigationCoverageTest.kt) | The selected folder filter survives conversation navigation and is cleared only by the All chip or an account switch. | `ui/navigation/MainShell.kt` folder-filter state |
| [`ChatListProfileReturnSnapCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/chats/ChatListProfileReturnSnapCoverageTest.kt) | Returning from a profile-opened conversation snaps to the captured list head, and stale return heads are never consumed by other opens. | Chat-list return-head state machine in `ui/chats/ChatsScreen.kt` |
| [`ChatRowSelectionIndicatorCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/chats/ChatRowSelectionIndicatorCoverageTest.kt) | Selection mode replaces row metadata without duplicate announcements and restores it on exit. | `ui/chats/ChatRow.kt` selection indicator |
| [`ChatsScreenScrollControlAccessibilityCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreenScrollControlAccessibilityCoverageTest.kt) | The scroll-to-top control is exposed to accessibility services as a button. | `ui/chats/ChatsScreen.kt` |
| [`ChatsScreenSelectionActionsCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreenSelectionActionsCoverageTest.kt) | Selection and long-press actions share one mutation path for read, mute, pin, order and folder changes. | `ui/chats/ChatsScreen.kt` selection actions |
| [`ComposeHotPathCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/ComposeHotPathCoverageTest.kt) | Expensive composer, emoji, chat-list and TTS work stays remembered, off the main thread or outside screen composition. | Compose hot paths in the composer, chat list and conversation screen |
| [`ConversationAnchoringSourceCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/ConversationAnchoringSourceCoverageTest.kt) | Conversation anchoring state follows the current controller, and the read anchor follows the unobstructed viewport. | `ui/conversation/ConversationScreen.kt` anchoring holders |
| [`ConversationDictationPlaybackHandoffSourceCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/ConversationDictationPlaybackHandoffSourceCoverageTest.kt) | Dictation capture pauses and resumes playback through paired callbacks, and resume requires the same paused clip and interruption token. | `state/ConversationDictationPlaybackHandoff.kt` and `audio/VoicePlaybackController.kt` |
| [`ConversationMentionPickerStateCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/conversation/composer/ConversationMentionPickerStateCoverageTest.kt) | Mention candidates use cached contact display names for both labels and filtering. | `ui/conversation/composer/ConversationMentionPickerState.kt` |
| [`ConversationShortcutLifecycleCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/ConversationShortcutLifecycleCoverageTest.kt) | Shortcut and direct-share surfaces are cleared for exactly the account leaving, before the active account changes. | `state/AppState.kt` account lifecycle boundaries |
| [`ForwardMessageSheetCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/ForwardMessageSheetCoverageTest.kt) | Accepted forwards run in app scope, picker state stays out of the saved-state bundle, and member previews use private nicknames. | `ui/conversation/messages/ForwardMessagePicker.kt` |
| [`ForwardProductionBoundaryCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/ForwardProductionBoundaryCoverageTest.kt) | Forwarding materializes under the source owner and publishes under the destination owner, serialized per destination and outside the shared download pool. | Forward transport in `state/AppState.kt` |
| [`FreshSweepCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/FreshSweepCoverageTest.kt) | Conversation-screen derivations are remembered by their real inputs, and media cache probes run off the composition thread. | `ui/conversation/ConversationScreen.kt` and media composables |
| [`GodComposableDecompositionCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/GodComposableDecompositionCoverageTest.kt) | Conversation and bubble UI live in named restart scopes, and media work lives outside screen composition. | `ui/conversation/ConversationScreen.kt` and `messages/MessageBubble.kt` |
| [`GroupDetailsPushDebugCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsPushDebugCoverageTest.kt) | Group push diagnostics load through the controller and render only in developer mode. | `ui/group/GroupDetailsScreen.kt` |
| [`KeyboardPreservingBottomSheetCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/KeyboardPreservingBottomSheetCoverageTest.kt) | App bottom sheets delegate overlay plumbing to the keyboard-safe popup and keep modal semantics. | `ui/design/AppSheets.kt` |
| [`KeyboardSafePopupCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/design/KeyboardSafePopupCoverageTest.kt) | The keyboard-safe popup never takes focus from the IME, dismisses before the IME back callback and stays pinned to the visible frame. | `ui/design` keyboard-safe popup |
| [`LongMessageFullScreenComposerCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/LongMessageFullScreenComposerCoverageTest.kt) | The expanded long-message reader reuses the standard composer and its gate. | `ui/conversation/messages/MessageFullScreen.kt` |
| [`MainActivityAppUnlockLifecycleCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/MainActivityAppUnlockLifecycleCoverageTest.kt) | App unlock requires keystore-backed crypto, binds every terminal callback to the current session and survives activity recreation without relaunching. | `MainActivity.kt` unlock session |
| [`MainActivityRecentsSecureFlagCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/MainActivityRecentsSecureFlagCoverageTest.kt) | Recents privacy applies before the first frame and on every foreground return, owning only its own secure-flag reference. | `MainActivity.kt` secure-flag handling |
| [`MainShellShareRoutingCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/navigation/MainShellShareRoutingCoverageTest.kt) | Inbound shares are persisted before any readiness gate and acknowledged exactly once by their owner. | `ui/navigation/MainShell.kt` share routing |
| [`MainThreadConfinementCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/MainThreadConfinementCoverageTest.kt) | Main-thread-confined caches are accessed only behind the guarded boundary, and slow work stays off main. | Media LRU boundary in `state/AppState.kt` |
| [`Media3PlayerViewReleaseCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/Media3PlayerViewReleaseCoverageTest.kt) | Every video view detaches its player on release, and disposal clears the published handle first. | `ui/conversation/media/MediaVideo.kt` |
| [`MessageBatchSelectionComposerGateCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/conversation/MessageBatchSelectionComposerGateCoverageTest.kt) | Batch reply availability uses the shared composer gate computed once per screen. | `ui/conversation/ConversationScreen.kt` composer gate |
| [`MessageBubbleReplyPreviewMentionResolverCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/MessageBubbleReplyPreviewMentionResolverCoverageTest.kt) | The reply-preview mention resolver stays stable until profile presentation changes. | `ui/conversation/messages/MessageBubble.kt` |
| [`MessageBubbleSelectionIndicatorCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubbleSelectionIndicatorCoverageTest.kt) | In selection mode the row toggle owns taps and semantics, and the gutter keeps a fixed leading slot. | Message bubble selection gutter |
| [`MessageMultiSelectCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/conversation/MessageMultiSelectCoverageTest.kt) | Selection actions belong to the revealed transcript, and batch selection state is keyed on controller identity. | `ui/conversation/ConversationScreen.kt` selection state |
| [`NewMessageEntryRouteCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/chats/NewMessageEntryRouteCoverageTest.kt) | Empty and non-empty chat lists enter new-message through the same host transition. | Chat-list new-message entry route |
| [`NotificationConversationOpenCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/navigation/NotificationConversationOpenCoverageTest.kt) | Notification opens keep their deciding account, publish routes after card dismissal and invalidate replaced visibility requests. | Notification open route in `ui/navigation` |
| [`NotificationGroupSystemTextCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/notifications/NotificationGroupSystemTextCoverageTest.kt) | Every structured group system event uses the localized projection, and only renames override the conversation title. | `state/NotificationFirstPostResolution.kt` |
| [`NotificationNetworkReconnectCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/notifications/NotificationNetworkReconnectCoverageTest.kt) | Only a validated offline-to-online edge reconnects the notification runtime, independent of push-wake markers. | Notification network reconnect coordinator |
| [`NotificationPushWakeDrainCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/notifications/NotificationPushWakeDrainCoverageTest.kt) | A push wake records durable catch-up first and holds its wake lock until the notification drain completes. | Push-wake bootstrap and drain owner |
| [`NotificationReplyWorkerCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/notifications/NotificationReplyWorkerCoverageTest.kt) | Notification-reply recovery resends only on a definitive miss, never on indeterminate evidence. | Notification reply worker recovery |
| [`ProfileSheetContactEditorCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/profile/ProfileSheetContactEditorCoverageTest.kt) | The contact private-details row prefers the nickname, then the notes label, then the add prompt. | `profileSheetContactPrivateDetailsRowValue` |
| [`StalenessGuardCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/StalenessGuardCoverageTest.kt) | Every suspend-then-publish path is guarded by the shared latest-wins primitive or carries a reasoned exemption. | Shared latest-wins staleness guard |
| [`StartupUnreadHydrationCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/StartupUnreadHydrationCoverageTest.kt) | Startup publishes ready at the local activation boundary and defers unread hydration until after the first local frame. | `state/AppState.kt` bootstrap |
| [`TtsAutoReadWiringCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/group/TtsAutoReadWiringCoverageTest.kt) | Automatic speech requires an owned session and a revealed transcript on every open, live and resume lane. | `ui/conversation/ConversationTtsEffects.kt` |
| [`TtsQuickTransportWiringCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/conversation/messages/TtsQuickTransportWiringCoverageTest.kt) | The read-aloud gesture is attached to every message row and claimed before reply swipe and long press. | `ui/conversation/messages/MessageBubble.kt` gesture wiring |
| [`TtsStartupDiscoveryCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/state/TtsStartupDiscoveryCoverageTest.kt) | TTS discovery starts independently of account bootstrap and never overwrites a runtime voice resolution. | `state/AppState.kt` TTS discovery |
| [`VideoPlaybackCoordinationCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/ui/VideoPlaybackCoordinationCoverageTest.kt) | Every video player owns audio focus and pauses voice playback, and pager video prepares only while current. | `ui/conversation/media/MediaVideo.kt` |
| [`VoicePlaybackAudioFocusPolicyCoverageTest`](../app/src/test/java/dev/ipf/whitenoise/android/audio/VoicePlaybackAudioFocusPolicyCoverageTest.kt) | Transient audio-focus changes never use the user-pause path and preserve their pause, duck or gain intent. | `audio/VoicePlaybackController.kt` |
<!-- invariant-gates:end -->
