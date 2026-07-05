# WhiteNoiseApp.kt decomposition plan (issue #20)

> Working plan — **not posted to the issue.** Supersedes the stale inventory in #20
> (which describes a 3,179-line `dev/ipf/darkmatter/ui/DarkMatterApp.kt`).

## 0. Ground truth (corrected)

| | Issue #20 says | Actual (master `db5ac431`) |
|---|---|---|
| File | `dev/ipf/darkmatter/ui/DarkMatterApp.kt`, 3,179 LOC | `dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt`, **20,257 LOC** |
| Composables | a handful | **198 `@Composable`** in one file |
| Navigation | "shell + nav" | **custom state-driven** (`MainSection`/`SettingsDetail` enums + `MainShell`) — *no* Jetpack Navigation |
| Theme | — | `ui/theme/` exists (`Color.kt`, `Theme.kt`, `Type.kt`; **no `Shape.kt`**), already Material 3 |

The #20 split sketch (~12 files) covers maybe a sixth of what's actually in the file. This plan replaces it.

## 1. Goals / non-goals

**Goals**
- Break the 20k-line monolith into feature-scoped packages, each file small enough to read, review, and `@Preview`.
- Make the codebase a clean seam for the *next* phases: **Material 3 Expressive** adoption and the **android/skills** (`adaptive`, `styles`, `edge-to-edge`, `navigation-3`).
- Zero behaviour change. Pure mechanical relocation + visibility/import adjustments.

**Non-goals (explicitly out of scope for this refactor)**
- No state-management rewrite. `WhiteNoiseAppState` + `ChatsController` + `ConversationController` stay as the state holders (they already act as ViewModels). Screens keep depending on them.
- No navigation-library migration *yet* (that's a sequenced follow-up — see §5).
- No visual/Material changes (that's the M3 Expressive phase — see §6).

## 2. Why now / sequencing constraint

This must land **after** any in-flight PR that edits `WhiteNoiseApp.kt`, or every such PR becomes an unmergeable rebase across ~20 new files. Confirm `gh pr list` shows nothing touching `ui/WhiteNoiseApp.kt` before starting each step. Conversely, once the split begins it should move quickly to avoid a long-lived divergence window.

## 3. Target package structure (`ui/`, package-by-feature)

Modern Compose guidance is **package-by-feature**, one screen per file, with a **stateful → stateless** split so the inner content is previewable and testable. Proposed tree (every leaf is a new file; the right column lists the current composables/types that move there):

```
ui/
  WhiteNoiseApp.kt            ← root only: bootstrap state, Loading/Failure gates, Theme wrap, hand to shell (~150 LOC)
  navigation/
    AppDestinations.kt        ← MainSection, SettingsDetail (formalised as a Destination model)
    MainShell.kt              ← MainShell (shell scaffold + section switching only)
  onboarding/
    OnboardingScreen.kt       ← OnboardingScreen, OnboardingContent
    SignInScreen.kt           ← SignInContent, PublicIdentifierFieldTrailingAction
  account/
    AccountSwitcher.kt        ← AccountAvatarButton, OtherAccountAvatarsRow, OtherAccountAvatar, OverflowAccountChip, AccountSelectorSheet
  chats/
    ChatsScreen.kt            ← ChatsScreen (stateful) + ChatsContent (stateless)
    ChatRow.kt                ← ChatRow, ChatRowWithMenu, MentionBadge, UnreadCountBadge
    ChatListTopBar.kt         ← ChatListTopBar, ChatListFilterChips/Chip, ConversationSearchTopBar/NavBar
    ChatListEmptyStates.kt    ← EmptyChats, ChatListNoResults
    NewChatSheet.kt           ← NewChatSheet, RecipientPreviewCard/SearchResults/Row, ChatListIdentifierResult,
                                IdentifierResolution, RecipientPreviewState, RecipientResolution, ComposerGate
    QuickActionFabMenu.kt     ← QuickActionFabMenu
  conversation/
    ConversationScreen.kt     ← ConversationScreen (stateful shell; currently ~2,250 LOC — split aggressively)
    MessageBubble.kt          ← MessageBubble (~1,230 LOC), MessageInlineFooter, BubbleFooterLayout, OutgoingMessageStatusIcon
    MessageFullScreen.kt      ← MessageFullScreenView, MessageInfoSheet/Row
    Composer.kt               ← ComposerBar, ComposerPill, MentionPicker, KeyboardPreservingDropdownMenu, RemovedMemberComposerNotice
    VoiceRecording.kt         ← MicHoldButton, RecordingStripLeading, LockHintAbove
    Reactions.kt              ← ReactionSummaryChip, ReactionDetailsSheet, ReactionParticipantRow
    EditHistory.kt            ← EditHistorySheet, EditHistoryVersionRow, EditHistoryRow
    MessageActions.kt         ← MessageActionMenu, MessageActionButton, ForwardMessageSheet, MessageActionButton
    EmojiPicker.kt            ← EmojiPickerSheet, EmojiSearchResultsGrid/Cell, EmojiCategoryTab, EmojiSectionHeader, EmojiActionButton
    ConversationRows.kt       ← DaySeparator, UnreadMessagesDivider, GroupSystemRow, AutoAcceptedInviteBanner, EmptyGroupConversation
    StreamDebug.kt            ← MessageDebugRow, StreamDebugEventRow
  media/                      ← (the single biggest cluster — ~30 composables)
    MediaBubbles.kt           ← MediaImageBubble, MediaImageGridBubble, MediaVisualGridBubble, MasonryImageLayout, MediaFileBubble
    MediaVideo.kt             ← MediaVideoBubble, MediaVideoGridTile, FullscreenVideoPlayer, VideoViewerPage
    MediaVoice.kt             ← MediaVoiceBubble, VoiceSpeedPill, VoiceWaveform
    MediaViewer.kt            ← FullScreenImageViewer, FullScreenMediaViewer, ViewerPage, MediaViewerPage
    MediaPreview.kt           ← MediaPreviewSheet, LocalImagePreview, StagingTile, StagingDocumentTile
    MediaPending.kt           ← MediaPendingPlaceholder, PendingFilePill, PendingStatusOverlay, PendingGridTile
    MediaControls.kt          ← MediaCircleAction, MediaBubbleAction, MediaImageGridTile, MediaScrimFooter, OpenAttachmentResult
  group/
    GroupDetailsScreen.kt     ← GroupDetailsScreen, GroupDetailsHeader, GroupActionRow, GroupMutationErrorBanner, ProfilePublicWarning, DetailsConfirm, ActiveGroupMutation, GroupMutationAction
    GroupEditScreen.kt        ← GroupEditScreen
    GroupMembers.kt           ← GroupMemberRow, TransferAdminSheet, GroupMemberMenuAction
    DisappearingMessages.kt   ← DisappearingMessagesPickerDialog, DisappearingOptionRow, DisappearingCustomDialog, DisappearingUnit
    GroupImageSearch.kt       ← ImageSearchSheet, GroupImageSearchTile, GroupImageAction
  settings/
    SettingsScreen.kt         ← SettingsScreen, SettingsHomeScreen, SettingsTopBar, SettingsAccountHeader, SettingsRow, SettingsSwitchRow, SelectableSettingsRow(+Subtitle)
    AppearanceScreen.kt       ← AppearanceScreen, LanguageOption
    NotificationsScreen.kt    ← NotificationsScreen
    SecurityPrivacyScreen.kt  ← SecurityPrivacyScreen
    AutoDownloadScreen.kt     ← AutoDownloadDataScreen, MediaQualitySettingsCard
    DiagnosticsScreen.kt      ← DiagnosticsScreen, DiagnosticRow, DiagnosticLogEntry
    RelaysScreen.kt           ← RelaysScreen, PublishedRelayLists, RelayListRow
    KeyPackagesScreen.kt      ← KeyPackagesScreen, KeyPackageCard
    IdentityScreen.kt         ← IdentityScreen, EncryptedBackupSheet/PassphraseFields/StrengthMeter, SignOutSheet, SignOutAndWipeSheet, WipeBullet, CopyableValueRow
  profile/
    ProfileSheet.kt           ← ProfileSheet, ProfileSheetAdminActions, ProfileSharedGroupRow, ProfilePictureDialog, ProfilePictureImageState
    ProfileEditScreen.kt      ← ProfileEditScreen
    ProfileQrSheet.kt         ← ProfileQrSheet
    AddIdentitySheet.kt       ← AddIdentitySheet
  qr/
    QrCodeImage.kt            ← QrCodeImage
    QrScannerSheet.kt         ← QrScannerSheet, CameraQrScanner, bindQrScannerCamera
  components/                 ← cross-feature, no business logic
    Avatar.kt                 ← Avatar
    SectionCard.kt            ← SectionCard, SectionCardWithAction
    Snackbar.kt               ← WhiteNoiseSnackbarHost, SwipeDismissibleSnackbar
    Dialogs.kt                ← ConfirmDialog
    StateScreens.kt           ← LoadingScreen, FailureScreen, ErrorContent
    WindowSecureFlag.kt       ← WindowSecureFlag (+ the Context.activity() helper)
  theme/                      ← already exists; extended in the M3 Expressive phase (§6)
    Color.kt  Type.kt  Shape.kt(new)  Motion.kt(new)  Theme.kt
```

Files that already live in `ui/` and stay (or get a better home later): `EmojiData.kt`, `RecentEmojiPreferences.kt`, `MarkdownRenderer.kt`, `MediaLibrary.kt`, `IncognitoKeyboard.kt`.

## 4. Per-file conventions (latest Compose practice)

1. **Stateful → stateless split.** Each screen file exposes `XxxScreen(appState/controller, …)` that reads state + wires callbacks, delegating to a private `XxxContent(uiState, onAction…)` that takes only data + lambdas. The content composable is what carries `@Preview` — impossible today at 20k lines, trivial once split.
2. **One screen per file**; extract any sub-component over ~150 LOC into its own file (notably `ConversationScreen`, `MessageBubble`, `GroupDetailsScreen`, `ComposerBar`).
3. **Visibility:** composables shared across the new files become `internal`; truly file-local helpers stay `private`. Expect churn here — many current `private fun` composables are used across clusters.
4. **Feature-local types travel with their feature** (`DetailsConfirm`, `ComposerGate`, `MediaViewerPage`, …). The two **navigation** enums (`MainSection`, `SettingsDetail`) move to `navigation/AppDestinations.kt`.
5. **Target ≤ ~400 LOC/file.** Add `@Preview`s as files are created (cheap, high-value once content is stateless).
6. **Imports:** ktlint ordering will churn heavily — run `:app:ktlintMainSourceSetFormat` after each move.

## 5. Navigation (today custom; migrate later, not now)

Today: `MainShell` switches on the `MainSection`/`SettingsDetail` enums + assorted `remember`-ed flags. **Step 1 of this refactor only relocates that** into `navigation/` unchanged. **Do not** introduce a nav library mid-split.

Follow-up (separate phase, with the **`navigation-3`** skill): formalise a typed destination model and migrate the shell to Jetpack **Navigation 3** — multiple back stacks, deep links (we already have profile deep links), and Scenes for list-detail. Decomposing first makes this a `navigation/`-only change instead of a 20k-file change.

## 6. Material 3 Expressive readiness (the reason for the seam)

The decomposition is what makes M3 Expressive a *theme + component* change rather than a whole-file rewrite:

- **`theme/`** is the single seam. Add `Shape.kt` (expressive shape scale) and `Motion.kt` (expressive motion scheme), flesh out `Color.kt`, and switch `Theme.kt` to the Expressive `MaterialTheme`/`MaterialExpressiveTheme` color/type/shape/motion. Note: brand palette is locked (`dynamicColor = false`) — see the brand-palette note; only the primary family is currently overridden, which leaks the device palette into chips/badges/tertiary; flesh out the full scheme here.
- **`components/`** centralises the recurring chrome (cards, rows, badges, chips, snackbar). Once these are a handful of shared components, adopting Expressive variants (button groups, FAB menus, loading indicators, expressive nav bars) is localised to `components/` + `theme/`.
- **`styles`** skill: migrate shared component params to the Compose **Styles API** so component theming is unified and `Modifier.styleable` carries interaction states.
- **`adaptive`** skill: with `chats/` and `conversation/` as separate features, add window-size-class–aware **list-detail / multi-pane** (chats ↔ conversation) for tablets/foldables/desktop, plus adaptive nav (rail/bar) — a `navigation/` + `MainShell` change, not per-screen.
- **`edge-to-edge`** skill: verify insets per feature after the split (composer/IME, viewers, sheets are the risk spots).

Keep these as **later phases** — the decomposition lands first, behaviour-identical, then the Expressive/skills work proceeds feature-by-feature against the clean structure.

## 7. Migration strategy (incremental, behaviour-preserving)

Mechanical, leaf-first, one package per PR, compile + test + ktlint after each:

1. **`components/` + `theme/Shape.kt`** — extract the shared leaves first (Avatar, SectionCard, Snackbar, dialogs, state screens, WindowSecureFlag). Lowest risk; everything else depends on them.
2. **`media/`** — the largest self-contained cluster; big LOC win, few external deps.
3. **`settings/`, `profile/`, `qr/`, `group/`, `onboarding/`, `account/`** — mostly independent screens.
4. **`chats/`** then **`conversation/`** — the heaviest and most interconnected; split `ConversationScreen`/`MessageBubble`/`ComposerBar` internally as they move.
5. **`navigation/` + slim `WhiteNoiseApp.kt`** — last: shell + root reduced to ~150 LOC.

Per step: cut composables/types to the new file, flip `private`→`internal` where crossed, `ktlintMainSourceSetFormat`, `:app:compileDevDebugKotlin`, `:app:testDevDebugUnitTest`, and a device smoke of the touched feature. Each step is independently revertible.

## 8. Risks / gotchas

- **Visibility churn:** many shared `private fun` composables → `internal`; easy to miss one and break compile (compiler will catch it).
- **Shared private helpers/consts/extension funcs** (not just composables) need a home — default to `components/` or a feature-`internal` util; don't recreate duplicates.
- **`@Preview` debt:** adding previews is the payoff but also the bulk of the "new lines" — do it as content composables are extracted, not as a separate pass.
- **No behaviour change is the contract** — there are essentially no UI unit tests for these composables, so each step needs a **device smoke** of its feature. Roborazzi screenshot tests are worth adding *as* features are extracted (locks behaviour for the M3 Expressive phase).
- **Coordinate with in-flight PRs** touching `WhiteNoiseApp.kt` — land them first (§2).
- **Don't do it as one mega-PR.** Sequence per §7.

## 9. Issue #20 housekeeping (when ready)

When work starts, update #20 itself: correct the path/name/LOC (`DarkMatterApp.kt` 3,179 → `WhiteNoiseApp.kt` 20,257), replace the split sketch with §3, and note the M3 Expressive / android-skills follow-on phases. (Not done here — this is the working plan only.)
