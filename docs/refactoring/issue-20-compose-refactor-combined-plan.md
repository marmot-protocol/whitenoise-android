# Issue #20: WhiteNoiseApp.kt Decomposition And Compose Refactor Plan

> Working plan only. Not posted to the GitHub issue.

## 0. Ground Truth

Issue #20 is still valid, but the original inventory is stale.

| Area | Original issue | Current reality |
|---|---|---|
| Main UI file | `DarkMatterApp.kt`, roughly 3k lines | `WhiteNoiseApp.kt`, roughly 20k lines |
| Package name | `dev.ipf.darkmatter` | `dev.ipf.whitenoise.android` |
| Navigation | not fully described | custom state-driven navigation, not Jetpack Navigation |
| Theme | not described | Compose Material 3 theme exists under `ui/theme` |
| Main risk | one large UI file | one large UI file plus large state/controller files |

The first refactor should focus on decomposing the UI file without behavior changes. The state/controller split should be planned, but not mixed into the first mechanical UI PRs.

## 1. Goals And Non-Goals

### Goals

- Split `WhiteNoiseApp.kt` into feature-scoped Compose packages.
- Make files small enough to review, preview, and test.
- Preserve current behavior.
- Create clear seams for a later Material 3 Expressive pass.
- Create a path toward `android/skills` usage later: `styles`, `adaptive`, `edge-to-edge`, and `navigation-3`.
- Keep the refactor incremental so active feature work can still land.

### Non-Goals For The First Pass

- No state-management rewrite.
- No Jetpack Navigation migration.
- No Material 3 Expressive visual redesign.
- No behavior changes.
- No broad renames unless required by package ownership.

`WhiteNoiseAppState`, `ChatsController`, and `ConversationController` should remain the state holders during the UI split. They can be decomposed after the UI file is no longer blocking development.

## 2. Refactor Principles

1. Use package-by-feature for screens and product surfaces.
2. Use shared UI primitives only for cross-feature components.
3. Keep the first pass behavior-preserving.
4. Extract leaf components before large screens.
5. Move pure logic out of composables when it is already naturally separable, but avoid turning this into a state rewrite.
6. Prefer stateful screen wrappers that delegate to stateless content where practical.
7. Keep `WhiteNoiseApp.kt` as the root only: app phase routing, theme wrapper, shell handoff, and global snackbar/error gates.
8. Use `internal` for composables shared inside the app module; keep truly local helpers `private`.

## 3. Target UI Structure

The UI split should be feature-first, with a small shared design layer. `ui/design` is the future Material 3 Expressive seam; `ui/common` is for concrete reusable app widgets.

```text
ui/
  WhiteNoiseApp.kt

  navigation/
    AppDestinations.kt
    MainShell.kt

  design/
    AppBackground.kt
    AppBars.kt
    AppButtons.kt
    AppCards.kt
    AppChips.kt
    AppDialogs.kt
    AppDividers.kt
    AppEmptyStates.kt
    AppIcons.kt
    AppMenus.kt
    AppSheets.kt
    AppSnackbars.kt
    AppTextFields.kt
    AppTooltips.kt
    AppMotion.kt
    AppSpacing.kt
    AppShapes.kt

  theme/
    AmoledSurface.kt
    Color.kt
    Dimens.kt
    Shape.kt
    Motion.kt
    Theme.kt
    Type.kt

  common/
    Avatar.kt
    SectionCard.kt
    Snackbar.kt
    Dialogs.kt
    StateScreens.kt
    ErrorContent.kt
    WindowSecureFlag.kt
    RelativeTimeText.kt
    ProfilePublicWarning.kt

  onboarding/
    OnboardingScreen.kt
    SignInScreen.kt

  account/
    AccountSwitcher.kt
    AccountSelectorSheet.kt

  chats/
    ChatsScreen.kt
    ChatRow.kt
    ChatListTopBar.kt
    ChatListFilterChips.kt
    ChatListEmptyStates.kt
    NewChatSheet.kt
    RecipientSearchResults.kt
    QuickActionFabMenu.kt

  conversation/
    ConversationScreen.kt
    ConversationChrome.kt
    ConversationTopBar.kt
    ConversationSearch.kt
    ConversationTimeline.kt
    ConversationTimelineEffects.kt
    ConversationRows.kt
    StreamDebug.kt

    messages/
      MessageBubble.kt
      MessageBubbleLayout.kt
      MessageBubbleBody.kt
      MessageBubbleFooter.kt
      MessageBubbleStatus.kt
      MessageActions.kt
      MessageFullScreen.kt
      MessageInfoSheet.kt
      EditHistory.kt

    replies/
      ReplyPreviewCard.kt
      ReplyTargetNavigation.kt

    reactions/
      ReactionSummaryChip.kt
      ReactionDetailsSheet.kt
      ReactionPicker.kt

    composer/
      ComposerBar.kt
      ComposerTextField.kt
      ComposerActions.kt
      ComposerPills.kt
      PendingAttachmentTray.kt
      EmojiPicker.kt
      VoiceRecording.kt
      RemovedMemberComposerNotice.kt

    media/
      MediaBubbles.kt
      MediaImage.kt
      MediaVideo.kt
      MediaVoice.kt
      MediaFile.kt
      MediaViewer.kt
      MediaPreview.kt
      MediaPending.kt
      MediaControls.kt

  group/
    GroupDetailsScreen.kt
    GroupDetailsHeader.kt
    GroupEditScreen.kt
    GroupMembers.kt
    GroupActions.kt
    GroupMutationErrorBanner.kt
    TransferAdminSheet.kt
    DisappearingMessages.kt
    GroupImageSearch.kt

  settings/
    SettingsScreen.kt
    SettingsHomeScreen.kt
    SettingsRows.kt
    AppearanceScreen.kt
    NotificationsScreen.kt
    SecurityPrivacyScreen.kt
    AutoDownloadScreen.kt
    DiagnosticsScreen.kt
    RelaysScreen.kt
    KeyPackagesScreen.kt
    IdentityScreen.kt
    DeveloperSettingsScreen.kt
    TelemetrySettingsScreen.kt
    AuditLogSettingsScreen.kt

  profile/
    ProfileSheet.kt
    ProfileEditScreen.kt
    ProfileQrSheet.kt
    AddIdentitySheet.kt

  qr/
    QrCodeImage.kt
    QrScannerSheet.kt
    CameraQrScanner.kt

  mediaLibrary/
    MediaLibrary.kt
    MediaLibraryTabs.kt
    MediaLibraryGrid.kt
    MediaLibraryFilters.kt
```

Existing files such as `EmojiData.kt`, `RecentEmojiPreferences.kt`, `MarkdownRenderer.kt`, `MediaLibrary.kt`, and `IncognitoKeyboard.kt` can stay where they are initially, then move only when a feature extraction naturally touches them.

## 4. Material 3 Expressive Readiness

The UI split should prepare for Material 3 Expressive without applying the redesign yet.

### What To Add During The Split

- `ui/design`: wrappers for common Material primitives used throughout the app.
- `ui/theme/Shape.kt`: app shape scale, including expressive shape candidates.
- `ui/theme/Motion.kt`: app motion durations/easing, ready for expressive motion later.
- Semantic tokens for repeated app surfaces: incoming bubble, outgoing bubble, selected chip, destructive action, warning surface, media placeholder, and muted text.

### What To Defer

- Full Material 3 Expressive visual language.
- Styles API migration.
- Adaptive list-detail layouts.
- Navigation 3 migration.
- Edge-to-edge/insets overhaul.

### Later Android Skills Sequence

After the decomposition lands:

1. Use `styles` to centralize shared component styling.
2. Use `adaptive` to add proper phone/tablet/foldable/list-detail behavior.
3. Use `edge-to-edge` to audit insets for composer, media viewers, sheets, and settings.
4. Use `navigation-3` only after the current custom navigation is isolated in `ui/navigation`.

## 5. Migration Strategy

Move quickly but in small PRs. Every PR should be behavior-preserving unless explicitly stated otherwise.

### PR 1: Shared UI Foundation

Extract:

- loading/failure/error content
- snackbar host
- confirm dialogs
- section cards
- avatar
- window secure flag helper
- common badges/chips
- initial `ui/design` and `ui/theme/Shape.kt`/`Motion.kt`

This is the safest first PR and creates the shared imports future packages need.

### PR 2: Self-Contained Screens

Extract:

- onboarding
- profile sheets
- QR scanner
- diagnostics
- key packages
- relays
- identity
- appearance
- notifications
- security/privacy

These surfaces are easier to smoke test and have less timeline/media coupling.

### PR 3: Chat List And New Chat

Extract:

- chats screen
- chat rows
- chat list top bar
- filters
- search states/results
- new chat sheet
- recipient search rows
- quick-action FAB menu

Move pure chat-list filtering/search helpers into `core/chat` or `state/chats` when doing so is mechanical.

### PR 4: Group Details

Extract:

- group details screen
- group edit screen
- group member rows
- group actions
- admin transfer sheet
- disappearing messages picker
- group image search

Keep group mutation behavior in `ConversationController` for this phase.

### PR 5: Conversation Shell

Extract:

- conversation screen shell
- top bar
- search UI
- timeline list wrapper
- timeline effects
- day separators
- unread divider
- system/debug rows
- auto-accepted invite banner
- removed-member notice

This is a high-risk PR because it touches keyboard, scroll, read-state, and timeline anchoring. Keep it strictly mechanical.

### PR 6: Message Bubble, Composer, Reactions, Media

Extract:

- message bubble layout/body/footer/status
- message actions and full-screen view
- reply preview
- reactions
- edit history
- composer
- emoji picker
- voice recording UI
- media bubbles, viewers, and pending states

This is the largest hot-path extraction. Keep state inputs stable and avoid passing the full controller to every small component when a narrow callback is enough.

### PR 7: Navigation Cleanup

After most screens are extracted:

- move `MainSection` and `SettingsDetail` into `ui/navigation`
- keep the custom navigation behavior unchanged
- reduce `WhiteNoiseApp.kt` to root composition only

Do not migrate to Navigation 3 here. That should be a later explicit PR.

## 6. File-Level Conventions

- `XxxScreen` is the stateful entry point.
- `XxxContent` is the stateless previewable body when feasible.
- Feature-local helper composables stay in the feature package.
- Shared concrete widgets go in `ui/common`.
- Shared style primitives go in `ui/design`.
- Pure non-UI logic goes in `core` or `state`, not in `ui`.
- Avoid duplicate helpers across feature packages.
- Keep files generally under roughly 400 lines. If a file is larger but cohesive, split by real concern rather than line count.

## 7. State And Controller Follow-Up

This should not block issue #20, but it should be the next cleanup track.

### Current Problem

`Controllers.kt` and `AppState.kt` are still too broad. Once the UI packages are split, those files will become the next source of merge conflicts and unclear ownership.

### Proposed State Structure

```text
state/
  app/
    WhiteNoiseAppState.kt
    AppPhase.kt
    AppText.kt
    ToastMessage.kt
    RuntimeBootstrap.kt
    AccountSessionCoordinator.kt
    ProfileCacheCoordinator.kt

  accounts/
    AccountUnread.kt
    SignOutCoordinator.kt
    ActiveAccountStore.kt

  chats/
    ChatsController.kt
    ChatListItem.kt
    ChatListProjectionStore.kt
    ChatListSearchState.kt
    UnreadRefreshScheduler.kt

  conversation/
    ConversationController.kt
    TimelineMessage.kt
    MessageStatus.kt
    PendingAttachment.kt
    ConversationTimelineStore.kt
    ConversationTimelineUpdater.kt
    ConversationMutationRunner.kt
    ConversationReadState.kt
    ConversationMembershipState.kt
    OptimisticMessageStore.kt
    ReactionStore.kt
    ConversationStreamingState.kt

  groups/
    GroupMemberSnapshot.kt
    GroupDetailsState.kt
    GroupMutationState.kt

  media/
    AttachmentDownloadGate.kt
    MediaAutoDownloadMatrix.kt
    MediaQuality.kt
    RetainedMediaUploads.kt

  notifications/
    ForegroundCatchUpPolicy.kt
    LocalNotificationSettingsState.kt
    NativePushState.kt

  drafts/
    DraftStore.kt

  disappearing/
    DisappearingMessageSweep.kt
    DisappearingMessageSweepWorker.kt

  util/
    BoundedEntryCache.kt
    BoundedNpubCache.kt
    BoundedStreamTombstones.kt
    Cancellation.kt
    LiveSubscriptionRetry.kt
    ProfileRefreshGate.kt
```

### Suggested Order

1. Move data classes and enums first.
2. Split chat-list controller from conversation controller.
3. Extract conversation timeline update logic.
4. Extract media upload/download coordination.
5. Extract reactions/read-state/group mutations.
6. Split `WhiteNoiseAppState` internals into collaborators while keeping the public app state API stable.

The public surface can stay as `WhiteNoiseAppState` initially. Internals should become smaller collaborators first; UI call sites can migrate later.

## 8. Validation Plan

Every PR should run:

- `git diff --check`
- dev debug compile
- unit tests touched by the move
- ktlint
- device/emulator smoke test for touched surfaces

Conversation-specific smoke:

- open a chat with text, replies, reactions, edits, media, voice, video, deleted messages, and system events
- send a message
- retry a failed message
- open keyboard and verify latest bubbles remain visible
- reply to a message and jump to original
- open group details and return
- switch accounts and return to the same group

Settings-specific smoke:

- open settings home
- open each moved settings detail page
- use toolbar back and OS back
- toggle settings and verify persistence after restart

Material 3 Expressive preparation:

- add screenshot coverage for chat row, message bubble, composer, group details header, settings row, media bubble, and onboarding as surfaces are extracted
- do not make broad visual changes until after the mechanical split lands

## 9. Risks

- Large conflict risk while feature PRs still touch `WhiteNoiseApp.kt`.
- Visibility churn from `private` to `internal`.
- Helper duplication if feature packages are split without a shared home.
- Accidental behavior change in conversation scroll, keyboard, read state, or message actions.
- Refactor fatigue if the work is done as one huge PR.

Mitigation:

- land active feature PRs first
- keep each PR behavior-preserving
- move leaf components first
- compile after every extraction slice
- smoke test the touched feature on device
- avoid Navigation 3 and Material 3 Expressive changes until after decomposition

## 10. Definition Of Done

Issue #20 is done when:

- `WhiteNoiseApp.kt` only owns root app composition and shell handoff.
- onboarding, chats, conversation, group details, settings, profile, QR, diagnostics, media, and common UI live in focused packages.
- `ui/design` and `ui/theme` contain the shared primitives needed for Material 3 Expressive.
- message bubble, composer, media bubbles, and group details are no longer buried inside one file.
- no extracted feature package depends on unrelated feature internals.
- follow-up issue or plan exists for the state/controller split if it is not completed in the same cleanup window.

The refactor is successful if future feature or design PRs touch only the relevant feature package instead of one central app file.
