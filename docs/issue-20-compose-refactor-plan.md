# Issue #20: Compose Refactor Plan

## Reader And Goal

This plan is for an Android engineer working on White Noise after the current release work settles. After reading it, they should be able to split the Compose app into focused packages without changing behavior, while leaving the codebase ready for a later Material 3 Expressive design pass.

The goal is not to hit an arbitrary line-count target. The goal is to create clear feature boundaries, smaller recomposition surfaces, easier review diffs, and a shared UI foundation that can absorb future design-system changes without another large rewrite.

## Current Problem

The app has one dominant UI file that owns too much of the product surface: onboarding, chat list, conversation, message bubbles, media bubbles, group details, settings, diagnostics, profile sheets, QR, common cards, snackbars, and assorted helpers. That makes everyday work slower because unrelated changes collide in the same file.

Two related files are also large and should be handled after the UI split:

- `Controllers.kt` mixes chat-list state, conversation state, timeline mutation handling, media download orchestration, group management, reactions, reads, streaming, and member refresh logic.
- `AppState.kt` owns app bootstrap, accounts, notifications, native push, profile caches, media preferences, drafts, relays, audit logs, telemetry, and presentation helpers.

The first refactor should focus on UI file boundaries. The second phase should split state/controller ownership so the UI packages are not coupled to one giant controller surface forever.

## Principles

1. Prefer feature packages over one flat `ui/components` dump.
2. Keep pure formatting, projection, search, and reconciliation logic out of composables.
3. Extract leaf components first, then screens, then the conversation surface.
4. Avoid behavior changes in the first pass. Mechanical movement should be reviewable.
5. Keep public APIs small. Most composables should remain package-private or `internal`.
6. Prepare for Material 3 Expressive by introducing stable app UI primitives before changing visual language.
7. Treat `WhiteNoiseApp.kt` as the app shell only: app phase routing, snackbar host wiring, top-level navigation state, and no feature implementation.

## Proposed Package Structure

```text
dev/ipf/whitenoise/android/
  MainActivity.kt
  WhiteNoiseApplication.kt

  core/
    avatars/
      AvatarImageLoader.kt
    chat/
      ChatListIdentifierSearch.kt
      ChatListMessageSearch.kt
      MessageSearch.kt
      RecipientSearch.kt
      RecipientReference.kt
    conversation/
      ConversationTranscriptExport.kt
      MessageProjector.kt
      MessageTextCopy.kt
      MessageEdits.kt
      MessageDebugStyle.kt
      ReplyNavigation.kt
      ReplySwipe.kt
      TimelineProjector.kt
    groups/
      GroupProjector.kt
      GroupSystemEvents.kt
    identity/
      IdentityFormatter.kt
      Nip05Resolver.kt
      ProfileFieldValidation.kt
      ProfileLink.kt
      ProfilePseudonymGenerator.kt
      ProfileSanitizer.kt
    markdown/
      MarkdownDocumentHelpers.kt
    mentions/
      MentionComposer.kt
    nostr/
      MarmotClient.kt
    qr/
      QrCodeEncoder.kt
    security/
      HostSafety.kt
    text/
      ClipboardPasteAffordance.kt
      RelativeTime.kt
    emoji/
      RecentEmojiList.kt
    streams/
      StreamDebugEvent.kt

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
      AccountSwitcherState.kt
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
      ConversationMembershipState.kt
      ConversationReadState.kt
      ConversationTimelineStore.kt
      ConversationTimelineUpdater.kt
      ConversationMutationRunner.kt
      ConversationStreamingState.kt
      OptimisticMessageStore.kt
      PendingAttachmentStore.kt
      ReactionStore.kt
    groups/
      GroupMemberSnapshot.kt
      GroupMutationState.kt
      GroupDetailsState.kt
    media/
      AttachmentDownloadGate.kt
      MediaAutoDownloadMatrix.kt
      MediaQuality.kt
      RetainedMediaUploads.kt
    settings/
      AppThemeMode.kt
      EnterKeyBehavior.kt
      LanguageSettings.kt
      SecurityPrivacySettings.kt
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

  media/
    cache/
      ByteSizeLruCache.kt
      DiskByteCache.kt
      MediaCacheDirs.kt
    inventory/
      MediaInventory.kt
    pipeline/
      MediaPipeline.kt
      MediaReferenceParser.kt
      Thumbhash.kt
    search/
      ImageSearchClient.kt

  audio/
    AudioWaveformExtractor.kt
    VoicePlaybackController.kt
    VoiceRecorder.kt
    VoiceRecordingController.kt

  notifications/
    BackgroundConnectionBootReceiver.kt
    BackgroundConnectionPolicy.kt
    BackgroundConnectionPreferences.kt
    LocalNotificationFormatter.kt
    LocalNotificationPolicy.kt
    LocalNotificationPostDecision.kt
    LocalNotificationPresenter.kt
    MarmotFirebaseMessagingService.kt
    NotificationAction.kt
    NotificationActionReceiver.kt
    NotificationChannelSpec.kt
    NotificationChannels.kt
    NotificationStreamForegroundService.kt
    NotificationTarget.kt
    PushServerConfig.kt
    PushTokenStore.kt

  ui/
    WhiteNoiseApp.kt
    AppNavigation.kt
    AppScaffold.kt

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
      ExpressiveTokens.kt
      Motion.kt
      Shape.kt
      Theme.kt
      Type.kt

    common/
      Avatar.kt
      AccountAvatarButton.kt
      MarkdownRenderer.kt
      LoadingScreen.kt
      FailureScreen.kt
      ConfirmDialog.kt
      SectionCard.kt
      ErrorBanner.kt
      ProfilePublicWarning.kt
      RelativeTimeText.kt
      SwipeDismissibleSnackbar.kt

    onboarding/
      OnboardingScreen.kt
      OnboardingContent.kt
      SignInContent.kt
      PublicIdentifierFieldTrailingAction.kt

    shell/
      MainShell.kt
      AccountSwitcher.kt
      AccountSelectorSheet.kt

    chats/
      ChatsScreen.kt
      ChatListTopBar.kt
      ChatListFilterChips.kt
      ChatRow.kt
      ChatRowMenu.kt
      ChatRowPreview.kt
      ChatListSearch.kt
      ChatListIdentifierResult.kt
      EmptyChats.kt
      QuickActionFabMenu.kt
      NewChatSheet.kt
      RecipientSearchResults.kt
      RecipientSearchResultRow.kt
      RecipientPreviewCard.kt
      MentionBadge.kt
      UnreadCountBadge.kt

    conversation/
      ConversationScreen.kt
      ConversationChrome.kt
      ConversationTopBar.kt
      ConversationSearchBar.kt
      ConversationSearchNavBar.kt
      ConversationTimeline.kt
      ConversationTimelineEffects.kt
      ConversationTimelineItems.kt
      ConversationScrollState.kt
      DaySeparator.kt
      UnreadMessagesDivider.kt
      EmptyGroupConversation.kt
      AutoAcceptedInviteBanner.kt
      RemovedMemberComposerNotice.kt

      messages/
        MessageBubble.kt
        MessageBubbleLayout.kt
        MessageBubbleBody.kt
        MessageBubbleFooter.kt
        MessageBubbleStatus.kt
        MessageBubbleMenu.kt
        MessageFullScreenView.kt
        MessageInfoSheet.kt
        MessageEditHistorySheet.kt
        MessageDebugRow.kt
        MessageSelection.kt

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
        ComposerReplyPill.kt
        ComposerEditPill.kt
        PendingAttachmentTray.kt
        EmojiPickerSheet.kt
        VoiceRecorderButton.kt

      media/
        MediaBubbleSizing.kt
        MediaImageBubble.kt
        MediaImageGridBubble.kt
        MediaVisualGridBubble.kt
        MediaFileBubble.kt
        MediaVideoBubble.kt
        MediaVoiceBubble.kt
        MediaPendingPlaceholder.kt
        PendingFilePill.kt
        PendingGridTile.kt
        PendingStatusOverlay.kt
        MediaCircleAction.kt
        MediaBubbleAction.kt
        MediaPreviewSheet.kt
        LocalImagePreview.kt
        StagingTile.kt
        StagingDocumentTile.kt
        FullScreenImageViewer.kt
        FullscreenVideoPlayer.kt
        VideoViewerPage.kt
        ViewerPage.kt
        VoiceSpeedPill.kt
        VoiceWaveform.kt

      system/
        GroupSystemRow.kt
        StreamDebugEventRow.kt

    groups/
      GroupDetailsScreen.kt
      GroupDetailsHeader.kt
      GroupEditScreen.kt
      GroupMembersSection.kt
      GroupMemberRow.kt
      GroupActions.kt
      GroupActionRow.kt
      GroupMutationErrorBanner.kt
      TransferAdminSheet.kt
      DisappearingMessagesPickerDialog.kt
      DisappearingOptionRow.kt
      DisappearingCustomDialog.kt
      ImageSearchSheet.kt
      GroupImageSearchTile.kt

    settings/
      SettingsScreen.kt
      SettingsHomeScreen.kt
      SettingsRows.kt
      AppearanceScreen.kt
      NotificationsScreen.kt
      SecurityPrivacyScreen.kt
      IdentityScreen.kt
      RelaysScreen.kt
      KeyPackagesScreen.kt
      DeveloperSettingsScreen.kt
      TelemetrySettingsScreen.kt
      AuditLogSettingsScreen.kt

    profile/
      ProfileSheet.kt
      ProfileEditScreen.kt
      ProfileQrSheet.kt
      ProfileLinkSheet.kt

    diagnostics/
      DiagnosticsScreen.kt
      DiagnosticRow.kt
      StreamDebugPanel.kt

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

## Material 3 Expressive Alignment

The refactor should make a later Material 3 Expressive update easier by separating product surfaces from reusable UI primitives.

The important preparation work is:

- Put app bars, buttons, chips, sheets, text fields, cards, menus, empty states, snackbars, and dialogs behind `ui/design` wrappers.
- Keep raw Material components mostly inside `ui/design` and leaf feature files, not scattered through screen orchestration.
- Keep motion, spacing, shapes, and typography as named tokens under `ui/theme`.
- Avoid feature-specific one-off colors. Prefer semantic tokens such as conversation incoming bubble, outgoing bubble, surface container, warning surface, destructive action, and selected chip.
- Keep message bubble layout and composer layout isolated. Those are the surfaces most likely to need Expressive-specific shape, motion, and spacing updates.
- Keep screenshots and visual tests focused on leaf surfaces: chat row, message bubble, composer, group details header, settings row, media bubble, and onboarding.

This means the first refactor should not redesign the app. It should create the seams that make the redesign safe.

## Extraction Order

### Phase 0: Freeze And Guardrails

Do this only after active feature PRs touching conversation, media, and settings have landed. Create a dedicated branch and keep it rebased frequently.

Before moving code:

- Run unit tests and compile.
- Capture screenshots or manual reference screenshots for the main flows.
- Avoid renaming functions unless the rename is needed for package ownership.
- Keep state shape unchanged.
- Do not introduce new navigation architecture in the same PR.

### Phase 1: Shared Leaf Components

Move small, low-risk reusable pieces first:

- snackbar host
- loading and failure screens
- confirm dialog
- section card
- avatar/account avatar pieces
- badge chips
- QR image/scanner surfaces
- relative time text helpers

This phase should create `ui/common`, `ui/design`, and any missing theme token files. It should be mostly mechanical and should not touch timeline behavior.

### Phase 2: Self-Contained Screens

Move screens that have limited coupling:

- onboarding
- diagnostics
- QR scanner
- profile sheets
- key packages
- relays
- identity
- appearance
- notifications
- security/privacy

This reduces file size quickly without entering the hot conversation path.

### Phase 3: Chat List

Move the chat list as a feature package:

- chat list screen
- top bar
- filters
- search result rows
- chat row
- row menu
- unread and mention badges
- new chat sheet
- recipient search rows
- quick-action FAB menu

Any pure search/filter helpers should move to `core/chat` or `state/chats`, not stay as private functions in UI files.

### Phase 4: Group Details

Move the group management surface:

- group details screen
- group edit screen
- image search sheet
- member rows
- admin transfer sheet
- group actions
- disappearing messages picker
- mutation error banner

Keep mutation orchestration in the existing controller for now. The UI move should only pass callbacks/state through clearer parameter objects.

### Phase 5: Conversation Shell

Move the conversation screen without splitting message bubbles yet:

- conversation top bar
- conversation search UI
- timeline list wrapper
- scroll/anchor effects
- day separators
- unread divider
- auto-accepted invite banner
- removed-member composer notice

This phase is risky because it touches navigation, scroll, keyboard, and read-state behavior. Keep the diff focused.

### Phase 6: Message Bubble And Composer

Split the hottest UI path:

- message bubble container
- body/text rendering
- footer/time/status
- reply preview
- reactions
- message action menu
- full-screen message view
- composer bar
- reply/edit composer pills
- pending attachment tray
- emoji picker
- voice recorder entry point

This is where recomposition boundaries matter most. Keep inputs stable and avoid passing the whole controller to every leaf unless the leaf truly needs it.

### Phase 7: Media Bubbles And Viewers

Move media-specific UI:

- image bubble
- album/grid layouts
- file bubble
- video bubble
- voice bubble
- pending media placeholders
- local previews
- media preview sheet
- full-screen image/video viewers

Media UI should depend on `ConversationController` through a narrow interface where possible: download, cache probe, thumbnail read/write, and retry callbacks.

### Phase 8: State And Controller Split

After UI movement is stable, split `Controllers.kt` and `AppState.kt`.

Start with model/data classes and pure stores:

- `ChatListItem`
- `TimelineMessage`
- `MessageStatus`
- `OutgoingMessageIndicator`
- `PendingAttachment`
- `ReactionParticipant`
- `GroupMemberSnapshot`

Then split controllers by responsibility:

- chat list controller
- conversation controller
- timeline updater
- media download coordinator
- media upload coordinator
- reaction coordinator
- read-state coordinator
- group mutation coordinator
- streaming debug coordinator

Finally split `WhiteNoiseAppState` into smaller collaborators owned by the app state:

- account/session coordinator
- profile cache coordinator
- notification coordinator
- push registration coordinator
- preferences/settings coordinator
- media preference/cache coordinator
- disappearing message coordinator

The public surface can still be a single `WhiteNoiseAppState` initially. Internals should become smaller collaborators first; UI call sites can be migrated later.

## Data And UI Boundary

The refactor should move toward this dependency direction:

```text
ui feature package
  depends on: state models, controller interfaces, ui/design, ui/common

ui/design and ui/common
  depends on: theme tokens and stable display models

state controllers
  depend on: core projection/search/formatting logic, media/audio/notifications, Marmot FFI

core
  depends on: Kotlin/JVM utilities and generated FFI models only when needed

media/audio/notifications
  own platform integrations and IO-heavy behavior
```

Avoid:

- FFI calls directly from composables.
- business rules inside UI event lambdas.
- UI components reaching into global `WhiteNoiseAppState` when a small state object/callback will do.
- passing `ConversationController` into every leaf component by habit.

Prefer:

- explicit state holder parameters for screens.
- narrow callback groups for actions.
- pure display models for rows and bubbles.
- stable keys and stable item IDs in timeline lists.

## Suggested Screen Contracts

Use small contracts for larger screens instead of passing every dependency separately.

```kotlin
data class ChatsUiState(
    val visibleItems: List<ChatListItem>,
    val archivedItems: List<ChatListItem>,
    val selectedFilter: ChatListFilter,
    val query: String,
    val isLoading: Boolean,
    val error: String?,
)

data class ChatsActions(
    val onOpenChat: (String) -> Unit,
    val onArchive: (String, Boolean) -> Unit,
    val onMarkRead: (String) -> Unit,
    val onCreateChat: () -> Unit,
)
```

The first extraction does not need to introduce all of these contracts immediately. They are the direction for the later cleanup pass after the mechanical split.

## Validation Plan

For every phase:

- `git diff --check`
- compile the dev debug variant
- run unit tests touched by the moved code
- run ktlint
- smoke test on device or emulator for the changed surface

For conversation phases:

- open a chat with text, replies, reactions, edits, media, voice, video, deleted messages, and system events
- send a message
- retry a failed message
- open keyboard and verify latest bubbles remain visible
- reply to a message and navigate to the original
- open group details and return
- switch accounts and return to the same group

For settings phases:

- navigate forward and back using toolbar and OS back gesture
- toggle settings and verify persistence after restart

For Material 3 Expressive preparation:

- add or update screenshot tests for the reusable leaf components before broad visual changes
- keep visual updates in follow-up PRs after the file split lands

## PR Slicing

Do not do this as one giant PR unless feature work is fully frozen. Recommended slices:

1. Shared UI foundation and common components.
2. Onboarding, diagnostics, QR, profile, and settings.
3. Chat list and new chat flow.
4. Group details and group management UI.
5. Conversation shell and timeline list.
6. Message bubble, reactions, composer, and media bubbles.
7. State/controller internal split.

Each PR should state whether it is intended to be behavior-preserving. If a PR changes behavior, it should not be part of the mechanical refactor chain.

## Definition Of Done

Issue #20 can be considered done when:

- `WhiteNoiseApp.kt` is reduced to app shell/routing responsibilities.
- conversation, chats, groups, settings, profile, diagnostics, QR, media, and common UI live in focused packages.
- no extracted feature package depends on unrelated feature internals.
- message bubble and composer are split into reviewable files.
- shared UI primitives exist for the future Material 3 Expressive pass.
- the state/controller split has at least begun, or a follow-up issue exists with the exact controller split plan.

The refactor is successful if a future design or feature PR touches the package for that feature only, instead of modifying one central app file.
