# UI patterns

Shared rules for Android screen chrome. They come from the UI unification
tracker (#4049). Each slice adds its rule here when it lands.

## Page top bar and scaffold

Every ordinary page uses `WhiteNoiseTopBar` inside `WhiteNoiseScaffold`
([`ui/common/WhiteNoiseTopBar.kt`](../app/src/main/java/dev/ipf/whitenoise/android/ui/common/WhiteNoiseTopBar.kt),
[`ui/common/WhiteNoiseScaffold.kt`](../app/src/main/java/dev/ipf/whitenoise/android/ui/common/WhiteNoiseScaffold.kt)).
Settings pages use `SettingsScaffold`, which wraps both. Do not build a page
from a raw `Scaffold`, `TopAppBar` or `CenterAlignedTopAppBar`.

The shared bar gives every page the same:

- start-aligned title in `titleLarge`, with an optional `titleContent` for a
  subtitle such as a selection count;
- leading control (see below), disabled with `navigationEnabled = false`
  while the page commits work;
- bar colour: `containerColor` matches the page canvas and the bar tints to
  `surfaceContainer` when content scrolls under it. Scrolling content needs
  `whiteNoiseVerticalScroll` or a `WhiteNoiseLazyColumn` for the tint to follow;
- banner slot directly below the bar (#3312), placed by the scaffold.

A page whose banner slot must sit above other bar content, such as its own
snackbar host, passes `topNoticeSlot = false` and places `TopNoticeSlot()`
itself.

Out of scope for this rule: the chat list top bar, the collapsing group
details header and the attachment viewers' chrome, which have their own
designs.

## Back arrow or X

The leading control says how the user got here.

| Control | Use it for | Examples |
| --- | --- | --- |
| Back arrow, labelled "Back" (`WhiteNoiseTopBarNavigation.Back`) | A page pushed onto the current stack, including a detail page shown as a full-screen dialog | Settings pages, Donate, Share & Connect, the contact picker, selection review, message details, edit history |
| X, labelled "Close" (`WhiteNoiseTopBarNavigation.Close`) | A modal or full-screen flow that returns to where it started when it is cancelled or finished, and viewers opened from a message | Share to, Forward, the Nostr event reader, image, video and document viewers |

The icon and its accessibility label always match: an arrow is "Back" and an X
is "Close". Android system Back does the same as the leading control in both
cases.
