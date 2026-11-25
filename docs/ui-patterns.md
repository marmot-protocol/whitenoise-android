# UI patterns

Shared rules for Android screen chrome. They come from the UI unification
tracker (#4049). Each slice adds its rule here when it lands. For choosing
a component or pattern, see the [illustrated UI guide](android-ui-patterns.md).

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

Out of scope for this rule: the chat list top bar and the collapsing group
details header, which have their own designs. Attachment viewers use the
viewer chrome below.

## Attachment viewer chrome

Every attachment viewer uses `AttachmentViewerChrome`
([`ui/conversation/media/AttachmentViewerChrome.kt`](../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/AttachmentViewerChrome.kt)):
the image and video viewer, PDF and HTML documents, the text reader, the
Nostr event reader, video player and linked image, and the photo editor.

- It leads with Close (an X labelled Close), because a viewer returns to the
  message it opened from.
- The title is one line of `titleMedium` in semibold, with an optional
  `labelMedium` second line such as the sender's time and position.
- Direct actions follow the title, then one overflow slot. The slot takes the
  viewer's existing More menu as it is; how viewer menus look is decided in
  #4098.
- Colours come from the theme. Readers dock the chrome on `surface`. Over
  edge-to-edge media it is the themed background under a heavy scrim, so the
  media still runs to the screen edges and the chrome pads itself clear of the
  status bar and side cutouts.

## Back arrow or X

The leading control says how the user got here.

| Control | Use it for | Examples |
| --- | --- | --- |
| Back arrow, labelled "Back" (`WhiteNoiseTopBarNavigation.Back`) | A page pushed onto the current stack, including a detail page shown as a full-screen dialog | Settings pages, Donate, Share & Connect, the contact picker, selection review, message details, edit history |
| X, labelled "Close" (`WhiteNoiseTopBarNavigation.Close`) | A modal or full-screen flow that returns to where it started when it is cancelled or finished, and viewers opened from a message | Share to, Forward, every attachment viewer (image, video, document, text, Nostr) and the photo editor |

The icon and its accessibility label always match: an arrow is "Back" and an X
is "Close", and Close always closes. Android system Back leaves the screen the
same way, except that it first dismisses a transient state on the screen, such
as a text selection.
