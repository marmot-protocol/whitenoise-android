# Code structure

Several Kotlin files grew past the point where one change can be reviewed in
context. They are being split into topic files. This guide says where new code
goes, how to split a file safely and how the size ratchet keeps files from
growing back.

## Budgets

The limits match [detekt](../config/detekt/detekt.yml):

| Rule | Limit |
| --- | --- |
| `LargeClass` | 600 lines per class; the ratchet applies the same limit per file |
| `LongMethod` | 60 lines per function |
| `LongParameterList` | 5 function parameters, 6 constructor parameters |
| `TooManyFunctions` | 11 functions per file or class |

Existing violations are listed in the detekt baseline and the size ratchet
baseline. Those lists only shrink.

## Topic files

A topic file holds one concern of a larger owner, in the owner's package. The
owner keeps its state and lifecycle; topic files add behaviour as extension
functions, so call sites do not change.

- `state/AppStateForwarding.kt` adds `fun WhiteNoiseAppState.forwardText(...)`.
  More than 30 `state/AppState<Topic>.kt` files extend `WhiteNoiseAppState` this way.
- `state/ChatsControllerRows.kt` holds chat-list row folding and replacement as
  `internal fun ChatsController.foldChatRow(...)` and its neighbours. The
  [chat-list ownership map](chat-list-controller-ownership.md) says which
  collaborator owns each responsibility.
- `state/ConversationController<Topic>.kt` (`Send`, `Media`, `Reactions`,
  `ReadState`, ...) and `audio/ConversationDictationController<Topic>.kt`
  (`Lifecycle`, `Stop`, `Retry`, ...) follow the same shape. The
  [conversation ownership map](conversation-controller-ownership.md) covers
  that controller's collaborators.
- Screens move sections into sibling files. `ui/settings/ManageStorageSections.kt`
  defines `LazyListScope.storageUsageItems(...)` and friends for
  `ManageStorageScreen.kt`; `ui/conversation/ConversationTopBar.kt` and
  `ConversationBottomBar.kt` sit beside `ConversationScreen.kt`.

## Where new code goes

1. Find the owner and its existing topic files. Add the code to the topic file
   that matches the concern.
2. If no topic fits, create `<Owner><Topic>.kt` in the same package. Name it for
   what the code does, not for the issue number or the date.
3. Never append a new feature to a file over 600 lines. If the change needs to
   touch code that lives in such a file, move that code to a topic file first.
4. New types that are not part of an owner get their own file, named after the
   type.

## Splitting a file

Do a split as a pure move, in its own commit or PR, before any behaviour change.

- Move whole declarations. Do not rename, reorder logic or "tidy up" while moving.
- Keep visibility as narrow as before. A `private` member that a topic file now
  needs becomes `internal`, not public. Helpers used only by one topic file move
  with it and stay file-`private`.
- Keep the package, so imports and call sites stay the same.
- Screenshot goldens must not change. If a Roborazzi golden changes, the move was
  not pure.
- Source-text tests that read the file must read every split part. Use the
  existing helpers: `chatsControllerSource()` in `ChatsControllerSourceFiles.kt`,
  `conversationControllerSource()` in `SourceBlockTestHelpers.kt`, and
  `splitOwnerFiles(...)` in `NotificationOwnerSourceFiles.kt`. When you split a
  file that a source test reads directly, add a helper of the same shape and
  switch the test to it in the same change.
- Update the size ratchet baseline in the same change (below).

[WhiteNoiseApp decomposition](refactoring/whitenoiseapp-decomposition.md) is a
worked plan for a large screen split.

## Size ratchet

`SourceFileSizeRatchetTest` counts lines in every Kotlin file under
`app/src/main/java`. Generated sources are not counted. A trailing newline does
not count, so the number matches `wc -l` for normally terminated files.

- A file not listed in `SourceFileSizeBaseline.kt` must stay within 600 lines.
- A listed file must stay within its recorded limit.
- When a change shrinks a listed file, lower its limit to the new count in the
  same change so the gain sticks. The test does not force this, so parallel
  changes to one file do not conflict over the exact number.
- When a listed file reaches 600 lines or fewer, or is deleted, the test fails
  until its entry is removed.

Never raise a limit and never add an entry. If the test fails because a file
grew, move the new code into a topic file or split the file first. The failure
message names the file and its current count.

Both files live in `app/src/test/java/dev/ipf/whitenoise/android/`.
