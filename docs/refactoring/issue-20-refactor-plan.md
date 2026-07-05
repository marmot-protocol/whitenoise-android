# Issue #20 — WhiteNoiseApp.kt UI decomposition (working plan)

> Standalone working plan. Not posted to the issue. Synthesises the two earlier drafts
> (`whitenoiseapp-decomposition.md`, `issue-20-compose-refactor-plan.md`) into one
> grounded, scoped, execution-ready document.

## Reader & goal

For an Android engineer picking this up after the current release work settles. After reading it you can split the Compose app into focused, previewable feature packages **without changing behaviour**, leaving a clean design-system seam so a later **Material 3 Expressive** pass and the **android/skills** work are localized changes rather than another monolith edit.

The goal is not a line-count target. It's clear feature boundaries, smaller recomposition surfaces, reviewable diffs, `@Preview`-able UI, and a shared UI foundation that absorbs design-system changes.

## Ground truth (corrects the stale issue)

Issue #20 describes a 3,179-line `dev/ipf/darkmatter/ui/DarkMatterApp.kt`. Reality on master:

| | #20 says | Actual |
|---|---|---|
| File | `darkmatter/ui/DarkMatterApp.kt`, 3,179 LOC | **`dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt`, 22,099 LOC** (master `6909b80b`, 2026-07-04) |
| Composables | a handful | **216 `@Composable`** in one file (**+18 / +1,842 LOC in ~6 days** — actively growing; delay = more to move) |
| Navigation | "shell + nav" | **custom state-driven** (`MainSection` / `SettingsDetail` enums + `MainShell`); **no Jetpack Navigation** |
| Theme | — | `ui/theme/` = `Color/Theme/Type/AmoledSurface/Dimens` (Material 3); still **no `Shape.kt`/`Motion.kt`/`ExpressiveTokens.kt`** |
| Other `ui/` files | — | `EmojiData.kt`, `RecentEmojiPreferences.kt`, `MarkdownRenderer.kt`, `MediaLibrary.kt`, `IncognitoKeyboard.kt` — **plus already-extracted chat-flow files** `ChatFlowComponents.kt`, `ContactPickerScreen.kt`, `NewChatFlow.kt`, `NewGroupSetupScreen.kt` (chat-management redesign, landed) |

> **Drift caveat (re-run before executing):** the move-map below was inventoried at 198 composables; master is now **216**. Re-run the `@Composable` inventory at execution time so the ~18 newer composables get homed, and diff for any renames/removals since this draft. The **structure and strategy stay valid**; only specific file→composable rows need a refresh.

Update #20's body to these numbers when work begins.

## Scope

**In scope (#20):** decompose `WhiteNoiseApp.kt` into feature packages + a design-system/theme seam. Pure mechanical relocation; no behaviour change.

**Out of scope (separate follow-on — see §10):** splitting `Controllers.kt` (~4.5k) and `AppState.kt` (~2.7k). That's tracked by #553/#559 and must not be conflated with #20, or #20 never ships. State holders (`WhiteNoiseAppState`, `ChatsController`, `ConversationController`) stay as-is; screens keep depending on them.

**Also out of scope:** any visual/Material redesign (that's the M3 Expressive phase) and any navigation-library migration (§8).

## Principles

1. Package-by-feature, not one flat `components` dump.
2. Stateful → stateless split: `XxxScreen(controller)` reads state + wires callbacks; private `XxxContent(uiState, actions)` is pure and `@Preview`-able.
3. Keep formatting / projection / search / reconciliation logic out of composables (it already lives in `core/` — keep it there).
4. Extract leaves first, then screens, then the conversation surface.
5. Most composables stay `private`/`internal`; minimal public surface.
6. Prepare for M3 Expressive by introducing stable UI primitives **before** changing visual language.
7. `WhiteNoiseApp.kt` becomes app-shell only: phase routing, snackbar host, top-level nav state — no feature implementation (~150 LOC).

## Target `ui/` structure (grounded move-map)

Legend: **`←`** = existing composable(s)/types relocated here (verified inventory). **`⊳`** = *intra-split target* — a finer split of the composable on the line directly above it; **no 1:1 function exists yet**, so confirm the boundary against the code when you extract it (these are how we tame the few giant composables — `ConversationScreen` ~2,250 LOC, `MessageBubble` ~1,230, `ComposerBar`). `design/` and the new `theme/` token files are the M3-Expressive seam, seeded during the split.

```
ui/
  WhiteNoiseApp.kt          ← root only: bootstrap, Loading/Failure gates, Theme wrap, hand to shell
  navigation/
    AppDestinations.kt      ← MainSection, SettingsDetail
    MainShell.kt            ← MainShell (shell scaffold + section switching)
  design/                   ← design-system layer (the M3 seam); raw Material lives HERE + leaf files, not in screens
    AppBars.kt AppButtons.kt AppCards.kt AppChips.kt AppDialogs.kt AppDividers.kt
    AppEmptyStates.kt AppMenus.kt AppSheets.kt AppSnackbars.kt AppTextFields.kt
    AppBackground.kt AppIcons.kt AppTooltips.kt
    AppShapes.kt AppMotion.kt AppSpacing.kt   ← app-facing semantic API over the raw theme/ tokens (see note ↓)
  theme/                    ← extend existing
    Color.kt Type.kt Theme.kt AmoledSurface.kt Dimens.kt (already exist)  +  Shape.kt(new) Motion.kt(new) ExpressiveTokens.kt(new)
  common/                   ← shared leaf composables (no business logic)
    Avatar.kt               ← Avatar
    SectionCard.kt          ← SectionCard, SectionCardWithAction
    Snackbar.kt             ← WhiteNoiseSnackbarHost, SwipeDismissibleSnackbar
    StateScreens.kt         ← LoadingScreen, FailureScreen, ErrorContent
    ConfirmDialog.kt        ← ConfirmDialog
    ProfilePublicWarning.kt ← ProfilePublicWarning
    WindowSecureFlag.kt     ← WindowSecureFlag (+ Context.activity())
  onboarding/
    OnboardingScreen.kt     ← OnboardingScreen, OnboardingContent
    SignInScreen.kt         ← SignInContent, PublicIdentifierFieldTrailingAction
  account/
    AccountSwitcher.kt      ← AccountAvatarButton, OtherAccountAvatarsRow, OtherAccountAvatar, OverflowAccountChip
    AccountSelectorSheet.kt ← AccountSelectorSheet, SettingsAccountHeader
  chats/
    ChatsScreen.kt          ← ChatsScreen (+ ChatsContent)
    ChatRow.kt              ← ChatRow, ChatRowWithMenu, MentionBadge, UnreadCountBadge
    ChatListTopBar.kt       ← ChatListTopBar, ConversationSearchTopBar, ConversationSearchNavBar, ChatListFilterChips, ChatListFilterChip
    ChatListEmptyStates.kt  ← EmptyChats, ChatListNoResults
    QuickActionFabMenu.kt   ← QuickActionFabMenu
    newchat/                ← ALREADY EXTRACTED on master (chat-management redesign) — RELOCATE these existing files here,
                              do NOT re-extract; NewChatSheet/RecipientSearchResults no longer exist in WhiteNoiseApp.kt:
      NewChatFlow.kt          ← NewChatFlowHost, NewGroupFlow, NewMessageScreen, StartChatErrorCard
      ContactPickerScreen.kt  ← ContactPickerScreen
      NewGroupSetupScreen.kt  ← NewGroupSetupScreen
      ChatFlowComponents.kt   ← 12 shared flow leaves (FlowSearchField, ContactRow, SelectedMemberRail, SelectionIndicator,
                                ResolvingContactRow, SectionHeader, QuickActionButton, FlowQuickActionRow, ComingSoonBadge …);
                                generic ones (SettingsActionRow, DangerActionRow, SectionHeader) may move to common/ or design/
  conversation/
    ConversationScreen.kt     ← ConversationScreen — stateful shell only, once the splits below land (~2,250 LOC today)
    ConversationChrome.kt     ⊳ in-conversation top bar lifted out of ConversationScreen
    ConversationTimeline.kt   ⊳ the timeline LazyColumn wrapper + item dispatch
    ConversationTimelineEffects.kt ⊳ scroll / anchor / keyboard / read-state effects (highest-risk; keep strictly mechanical)
    ConversationRows.kt       ← DaySeparator, UnreadMessagesDivider, GroupSystemRow, AutoAcceptedInviteBanner, EmptyGroupConversation
    StreamDebug.kt            ← MessageDebugRow, StreamDebugEventRow
    replies/ReplyPreviewCard.kt ← ReplyPreviewCard
    messages/
      MessageBubble.kt        ← MessageBubble — container only, once the splits below land (~1,230 LOC today)
      MessageBubbleLayout.kt  ⊳ bubble shell / alignment / shape lifted out of MessageBubble
      MessageBubbleBody.kt    ⊳ text + markdown body rendering
      MessageBubbleStatus.kt  ← OutgoingMessageStatusIcon (+ send-status chrome)
      MessageBubbleFooter.kt  ← MessageInlineFooter, BubbleFooterLayout, MediaScrimFooter, MessageActionButton
      MessageFullScreen.kt    ← MessageFullScreenView, MessageInfoSheet, MessageInfoRow
      MessageActions.kt       ← MessageActionMenu, ForwardMessageSheet
      EditHistory.kt          ← EditHistorySheet, EditHistoryVersionRow + EditHistoryRow
    reactions/Reactions.kt    ← ReactionSummaryChip, ReactionDetailsSheet, ReactionParticipantRow
    composer/
      ComposerBar.kt          ← ComposerBar — container only, once the splits below land; + RemovedMemberComposerNotice, KeyboardPreservingDropdownMenu
      ComposerTextField.kt    ⊳ input field + text area lifted out of ComposerBar
      ComposerActions.kt      ⊳ attach / camera / mic / send action cluster
      ComposerPills.kt        ← ComposerPill (reply / edit pills)
      MentionPicker.kt        ← MentionPicker
      VoiceRecorder.kt        ← MicHoldButton, RecordingStripLeading, LockHintAbove
      EmojiPicker.kt          ← EmojiPickerSheet, EmojiSearchResultsGrid, EmojiSearchResultCell, EmojiCategoryTab,
                                EmojiSectionHeader, EmojiActionButton
    media/
      MediaImageBubbles.kt  ← MediaImageBubble, MediaImageGridBubble, MediaVisualGridBubble, MasonryImageLayout, MediaImageGridTile
      MediaFileBubble.kt    ← MediaFileBubble, PendingFilePill
      MediaVideo.kt         ← MediaVideoBubble, MediaVideoGridTile, FullscreenVideoPlayer, VideoViewerPage
      MediaVoice.kt         ← MediaVoiceBubble, VoiceSpeedPill, VoiceWaveform
      MediaViewer.kt        ← FullScreenImageViewer, FullScreenMediaViewer, ViewerPage + MediaViewerPage
      MediaPreview.kt       ← MediaPreviewSheet, LocalImagePreview, StagingTile, StagingDocumentTile
      MediaPending.kt       ← MediaPendingPlaceholder, PendingStatusOverlay, PendingGridTile
      MediaControls.kt      ← MediaCircleAction, MediaBubbleAction + OpenAttachmentResult, ImageAttachmentReadOutcome, GroupImageAction
  group/
    GroupDetailsScreen.kt   ← GroupDetailsScreen, GroupDetailsHeader, GroupActionRow, GroupMutationErrorBanner
                              + DetailsConfirm, GroupMutationAction, ActiveGroupMutation
    GroupEditScreen.kt      ← GroupEditScreen
    GroupMembers.kt         ← GroupMemberRow, TransferAdminSheet + GroupMemberMenuAction
    DisappearingMessages.kt ← DisappearingMessagesPickerDialog, DisappearingOptionRow, DisappearingCustomDialog + DisappearingUnit
    GroupImageSearch.kt     ← ImageSearchSheet, GroupImageSearchTile
  settings/
    SettingsScreen.kt       ← SettingsScreen, SettingsHomeScreen, SettingsTopBar, SettingsRow, SettingsSwitchRow,
                              SelectableSettingsRow, SelectableSettingsRowWithSubtitle
    AppearanceScreen.kt     ← AppearanceScreen + LanguageOption
    NotificationsScreen.kt  ← NotificationsScreen
    SecurityPrivacyScreen.kt← SecurityPrivacyScreen   (telemetry + audit-log toggles live inline here — NOT separate screens)
    AutoDownloadScreen.kt   ← AutoDownloadDataScreen, MediaQualitySettingsCard
    IdentityScreen.kt       ← IdentityScreen, EncryptedBackupSheet, EncryptedBackupPassphraseFields,
                              EncryptedBackupStrengthMeter, SignOutSheet, SignOutAndWipeSheet, WipeBullet, CopyableValueRow
    RelaysScreen.kt         ← RelaysScreen, PublishedRelayLists, RelayListRow
    KeyPackagesScreen.kt    ← KeyPackagesScreen, KeyPackageCard
    DiagnosticsScreen.kt    ← DiagnosticsScreen, DiagnosticRow + DiagnosticLogEntry
  profile/
    ProfileSheet.kt         ← ProfileSheet, ProfileSheetAdminActions, ProfileSharedGroupRow, ProfilePictureDialog + ProfilePictureImageState
    ProfileEditScreen.kt    ← ProfileEditScreen
    ProfileQrSheet.kt       ← ProfileQrSheet
    AddIdentitySheet.kt     ← AddIdentitySheet
  qr/
    QrCodeImage.kt          ← QrCodeImage
    QrScannerSheet.kt       ← QrScannerSheet, CameraQrScanner, bindQrScannerCamera
  medialibrary/
    MediaLibrary.kt         ← existing file (relocate; split into tabs/grid/filters only if it grows)
```

Stays put: `EmojiData.kt`, `RecentEmojiPreferences.kt`, `MarkdownRenderer.kt`, `IncognitoKeyboard.kt` (move under `common/` or a feature package as natural).

> Note: every `←` file maps to a real, named composable. `⊳` files are intra-split targets (see legend) — verify the cut against the code, don't assume a 1:1 function. Don't invent screens (e.g. there is no standalone Telemetry/AuditLog/Developer settings screen today — those are sections inside `SecurityPrivacyScreen`/`DiagnosticsScreen`).
>
> `design/App{Shapes,Motion,Spacing}` are thin **app-facing accessors/semantics** over the raw `theme/` token values: the Material token primitives (shapes, motion scheme, dimens) live in `theme/`; `design/` exposes the named app usages plus the component wrappers. Keep one source of truth — `design/` references `theme/`, never re-declares the values.

## Per-file conventions & screen contracts

Use a small `UiState` + `Actions` pair for larger screens instead of threading many params or passing a whole controller into every leaf:

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

The first (mechanical) pass need not introduce every contract — it's the direction for the cleanup pass once files are split. Other rules: one screen per file; extract any sub-component > ~150 LOC; target ≤ ~400 LOC/file; flip shared `private` composables to `internal` when crossing files; run `:app:ktlintMainSourceSetFormat` after each move (import-order churn is heavy).

## Data / UI boundary

Target dependency direction:

```
ui feature package → state models + controller interfaces + ui/design + ui/common
ui/design + ui/common → theme tokens + stable display models
state controllers → core (projection/search/formatting) + media/audio/notifications + Marmot FFI
core → Kotlin/JVM + generated FFI models
media / audio / notifications → platform integrations + IO
```

Avoid: FFI calls from composables; business rules in UI lambdas; leaves reaching into global `WhiteNoiseAppState` when a small state object/callback suffices; passing `ConversationController` into every leaf by habit. Prefer: explicit state-holder params per screen, narrow callback groups, pure display models for rows/bubbles, stable keys/IDs in timeline lists (recomposition correctness).

## M3 Expressive readiness + android/skills mapping

The decomposition is what turns the redesign into a `design/` + `theme/` change instead of a 20k-file edit. After the split:

- **Material 3 Expressive:** keep raw Material components inside `ui/design` wrappers + leaf files; switch `theme/Theme.kt` to the Expressive `MaterialTheme` with `Shape.kt`/`Motion.kt`/`ExpressiveTokens.kt`. Flesh out `Color.kt` to the full brand scheme with `dynamicColor = false` (today only the primary family is overridden, leaking the device palette into chips/badges/tertiary). Use **semantic tokens** (incoming/outgoing bubble, surface container, warning, destructive, selected chip), not feature one-off colors. Isolate message-bubble + composer layout — the surfaces most likely to need Expressive shape/motion/spacing.
- **`styles` skill:** migrate `ui/design` component params to the Compose Styles API (`Modifier.styleable`, interaction states) so theming is unified.
- **`adaptive` skill:** with `chats/` and `conversation/` separated, add window-size-class list-detail / multi-pane (chats ↔ conversation) for tablets/foldables/desktop + adaptive nav (rail/bar) — a `navigation/` + `MainShell` change.
- **`edge-to-edge` skill:** re-verify insets per feature post-split (composer/IME, viewers, sheets are the risk spots).
- **Visual tests:** add Roborazzi screenshot tests on the leaf surfaces (chat row, message bubble, composer, group-details header, settings row, media bubble, onboarding) **before** the visual change, to lock behaviour.

These are later phases; the split lands first, behaviour-identical.

## Navigation

Today: `MainShell` switches on `MainSection`/`SettingsDetail` + `remember`-ed flags — custom, no nav library. This refactor only **relocates** that into `navigation/` unchanged. Do **not** introduce a nav library mid-split. Follow-on (with the **`navigation-3`** skill): formalize a typed destination model and migrate to Jetpack Navigation 3 (multiple back stacks, profile deep links already exist, Scenes for list-detail). Decomposing first makes this a `navigation/`-only change.

## Execution: one atomic PR, per-package commits

**Why single-PR.** Shipping a half-decomposed tree between releases invites a steady stream of "split this file" issues against whatever hasn't moved yet. The decomposition must therefore be **atomic to a release** — no release ever ships a partially-refactored `WhiteNoiseApp.kt`. The practical form is **one PR**, structured so it's still reviewable and bisectable:

- **One commit per feature package** — the order below is the *commit* sequence, not separate PRs. Each commit **compiles and passes tests on its own**, so `git bisect` works inside the PR and reviewers can review commit-by-commit even though it merges atomically.
- **Strictly mechanical, zero behaviour change.** Pure move + visibility (`private`→`internal`) + imports + the `⊳` intra-splits. No "while I'm here" logic edits, no renames beyond package ownership, no Material/visual changes. Spot a real bug mid-move? File it; do **not** fix it in this PR (a behaviour change buried in a 20k-line move is invisible to review).
- **Reviewed by commit + by guarantee, not by line.** A 20k-line relocation is un-eyeballable; reviewers check each per-package commit and lean on the behaviour-preserving contract + the green gates below.

Commit sequence (leaf-first; same dependency order as before):

0. **Freeze/guardrails** — open only after in-flight PRs touching `WhiteNoiseApp.kt` land; dedicated branch, rebased often; **no other PR edits `WhiteNoiseApp.kt` while this is open**; land fast.
1. **Shared foundation** — `common/`, `design/` skeleton, `theme/` tokens (`Shape`/`Motion`/`Dimens`/`ExpressiveTokens`).
2. **Self-contained screens** — onboarding, diagnostics, qr, profile, settings (appearance/notifications/security/identity/relays/keypackages/autodownload), account switcher.
3. **Chat list** — `chats/` (+ move residual pure search/filter helpers to `core/`/`state/`, not UI).
4. **Group management** — `group/`.
5. **Conversation shell** — `ConversationScreen` + chrome/timeline/effects + `ConversationRows`. Riskiest (scroll/keyboard/read-state) — keep strictly mechanical.
6. **Message bubble + composer + reactions** — the hot path; mind recomposition boundaries, keep inputs stable.
7. **Media bubbles + viewers** — `conversation/media/`; depend on `ConversationController` through a narrow interface (download / cache probe / thumbnail / retry).
8. **Slim the shell** — `navigation/` + `WhiteNoiseApp.kt` reduced to ~150 LOC.

**Mandatory pre-merge gates** (single PR = no incremental safety net across releases, so these are non-negotiable):
- full `:app:testDevDebugUnitTest`, `ktlint`, `compileDevDebugKotlin` green.
- **Roborazzi/screenshot baseline captured *before* the move and diffed after** — the cheapest proof that pure relocation changed zero pixels. Add leaf-surface coverage (chat row, message bubble, composer, group-details header, settings row, media bubble, onboarding) in commit 1.
- **Device smoke matrix** on the Pixel — the conversation + settings checklists in the next section.

### Keep the state/controller split OUT of this PR
Do **not** fold `Controllers.kt`/`AppState.kt` (#553/#559) into this diff even under "single PR." That split changes ownership and is **not** purely mechanical — bundling it makes the whole PR impossible to verify as behaviour-preserving. Accepted trade-off: until #553/#559 lands, issues may still be filed against those two files. If that exposure is also unacceptable, do the state split as a **second atomic PR in the *same release cycle*** — never in the same diff as the UI move.

### Nuance: the real requirement is "atomic to a release," not literally one PR
If your review process can't stomach a single 20k-line PR, the equivalent is to land all per-package commits as back-to-back PRs **within one release cycle behind the freeze** — i.e. never cut a release mid-sequence. Same outcome (no half-refactored release), more reviewer-friendly. The one-PR/per-package-commit form just guarantees atomicity, reviewability, and bisect in a single vehicle.

## Validation plan (per phase)

- `git diff --check`; compile dev-debug; run unit tests touched by the move; ktlint; **device/emulator smoke of the changed surface**.
- **Conversation phases:** open a chat with text/replies/reactions/edits/media/voice/video/deleted/system events; send; retry a failed send; open keyboard and verify latest bubbles stay visible; reply → navigate to original; open group details and back; switch accounts and return to the same group.
- **Settings phases:** forward/back via toolbar and OS back gesture; toggle a setting and verify persistence after restart.

## Out of scope for #20: state / controller split (follow-on)

Tracked by #553/#559 — **not** #20. After the UI split is stable: extract model/data classes + pure stores from `Controllers.kt`/`AppState.kt`, then split controllers by responsibility (chat-list, conversation, timeline updater, media download/upload, reactions, read-state, group mutation, streaming) and `WhiteNoiseAppState` into collaborators (account/session, profile cache, notifications, push, settings, media prefs, disappearing). The public `WhiteNoiseAppState` surface can stay a facade initially; migrate call sites later. Keep this in its own issue/PR chain so #20 ships independently.

## Definition of Done (for #20)

- `WhiteNoiseApp.kt` reduced to app-shell/routing.
- conversation, chats, groups, settings, profile, diagnostics, qr, media, common UI in focused packages.
- no extracted feature package depends on another feature's internals.
- message bubble + composer split into reviewable files.
- `ui/design` + `ui/theme` tokens exist as the M3 Expressive seam.
- a follow-up issue (or #553/#559) holds the exact state/controller split.

Success test: a future design/feature PR touches *one feature package*, not one central app file.

## Risks / gotchas

- Visibility churn (`private` → `internal` across files; compiler catches misses).
- Shared private helpers/consts/extension funcs need a home (`common/` or feature-internal) — don't duplicate.
- No UI unit tests today → each step needs a device smoke; add Roborazzi as features are extracted.
- ktlint import-order churn → `ktlintMainSourceSetFormat` every step.
- Coordinate with in-flight `WhiteNoiseApp.kt` PRs (land first).
- Don't do it as one mega-PR.
