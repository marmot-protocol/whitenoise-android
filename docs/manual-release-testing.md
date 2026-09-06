# White Noise Android manual release testing

This is the release-candidate checklist for every user-visible White Noise Android surface. It is written so a tester who has never used White Noise can follow it without reading the source. Run every applicable point against the exact APK proposed for release. Do not infer a pass from an automated test.

## How to use this guide

1. Record the APK filename, SHA-256, version, build variant, commit SHA, Android version, device model, tester, and date in the run report.
2. Work from a clean install unless a point explicitly requires retained data or an upgrade. Keep one separate populated installation for upgrade and history checks.
3. Tick a point only after performing its **Actions** and observing its **Expected** result. Record `N/A` only when the point's stated prerequisite is unavailable, and write why.
4. Report failures by the stable point ID, for example `MSG-006`, plus account, conversation type, exact actions, actual result, screenshot or screen recording when safe, and relevant time. IDs are permanent: they are never renumbered or reused.
5. When a point says “the other device,” use another real installation signed in to a different test identity. Allow normal relay latency before failing a synchronization point.
6. “Back” means test both the visible back arrow and Android system Back where both are available. A screen passes only if neither path exits unexpectedly, loses a draft, or returns to the wrong account.

## Safety and test-data rules

- Use disposable test identities, test relay data, synthetic names, synthetic phone contacts, harmless files, and a non-sensitive test location. Never paste a real `nsec`, recovery phrase, contact book, private photo, audit log, or precise home/work location into a report.
- Treat account keys, encrypted backups, QR codes, notification content, exported audit logs, and the Android share sheet as sensitive. Blur or omit their values from evidence.
- Use a dedicated temporary directory for downloads and exports. Delete it, clear the clipboard, remove test accounts, and revoke temporary permissions after the run.
- Before destructive points (wipe, leave, remove member, delete for everyone, disappearing messages), confirm that every affected identity and conversation is disposable.
- Test notification text on a locked device only with synthetic messages. Do not expose private content on a shared lock screen.
- Never approve an unexpected Android permission prompt. A requested permission must correspond to the action described in the current point.
- Use only tester-controlled identities and test relays. Never invite, message, or add an account that the tester does not control. Relay-published profile, membership, and message data can be copied permanently even after local deletion, so use only synthetic, non-sensitive content. Prefer a dedicated test/staging relay set; if none is available, clearly mark the run as using public relays and keep all content disposable.

## Test matrix and prerequisites

- One supported Android phone with a secure device credential; a second Android device or emulator; and, when available, one Android 13+ device for notification permission behavior.
- Three disposable identities: Alice (primary tester), Bob (peer), and Carol (group member). Keep Alice signed in on the release candidate and Bob/Carol on independent installations.
- One direct chat with Bob; one three-person group with Alice as admin; one group where Alice is a non-admin; an invite awaiting Alice; populated and empty/archived chats; and messages from each member.
- Safe fixtures: short and long text, emoji, a web URL, `@`-mentionable members, small/large images, video, audio, PDF, plain-text document, unsupported file, synthetic vCard contact, and a coarse test location.
- Test both the Zapstore and Play build behavior when producing those artifacts. Build-specific points may be `N/A` for a release that does not ship that variant.
- For accessibility coverage, enable TalkBack, large display/font settings, an RTL locale, landscape, dark theme, and reduced-animation settings one at a time.

## Smoke pass

Before the exhaustive pass, run these release-blocking paths in order: `INT-001`, `ONB-003`, `CHL-002`, `CON-005`, `MED-003`, `MSG-005`, `GRP-012`, `NTF-009`, `SEC-002`, `SET-025`, and `END-004`. A smoke failure stops the release but does not replace the full checklist after repair.

## Full release checklist

| Prefix | Area |
|---|---|
| `AXY` | Accessibility and layout |
| `ACC` | Accounts and account keys |
| `CHL` | Chat list and chat actions |
| `CON` | Conversation and composer |
| `DIC` | Dictation |
| `END` | Completion and verdict |
| `FIND` | Search |
| `FLD` | Chat folders |
| `GRP` | Groups and invites |
| `INT` | Install, startup, updates, and platform integration |
| `MED` | Attachments and media |
| `MSG` | Message rendering and actions |
| `NAV` | Navigation and account switching |
| `NCH` | Starting chats and groups |
| `NTF` | Notifications and background delivery |
| `ONB` | Onboarding and sign-in |
| `PRO` | Profiles |
| `RDR` | Nostr readers |
| `SEC` | Device privacy and app lock |
| `SET` | Settings and customization |
| `SYS` | Relays and system intents |
| `TTS` | Text to speech |

### Release identity and first launch

1. [ ] **INT-001 — APK identity** — Install the recorded APK and open Android App info. → **Expected:** The app name, icon, package, version, and build variant match the release candidate; a development or staging build is visibly distinguishable from production.
2. [ ] **INT-002 — Clean launch** — Clear app storage, force-stop, then launch from the launcher. → **Expected:** The system splash hands off to White Noise once, startup never shows another account's content, and onboarding appears without a crash or blank frame.
3. [ ] **INT-003 — Startup progress** — Launch on a slow connection. → **Expected:** A meaningful secure-startup/loading state appears, remains responsive, and resolves to onboarding or the signed-in surface.
4. [ ] **INT-004 — Startup retry** — Disable networking during startup, wait for the timeout/error, restore networking, and tap Retry. → **Expected:** The error explains recovery, retained content is not discarded, and retry reaches the usable app without relaunching.
5. [ ] **INT-005 — Warm resume** — Open a conversation, background the app for 30 seconds, and resume it from Recents. → **Expected:** The same account and useful surface return without a second splash, duplicate navigation, or stale overlay.
6. [ ] **INT-006 — Process recreation** — With a conversation open, use the developer option “Don't keep activities” or kill the process, then reopen. → **Expected:** The app restores or safely falls back to the correct account and chats; it never flashes another account's messages.
7. [ ] **INT-007 — Offline shell** — With populated local data, disable all networks and relaunch. → **Expected:** Existing chats/messages remain readable, an offline/retrying state is honest, and network-only actions fail safely without deleting local content.
8. [ ] **INT-008 — Connectivity recovery** — While the offline shell is visible, restore networking and receive/send a synthetic message. → **Expected:** Connection indicators recover without an app restart and the new message appears once.
9. [ ] **INT-009 — Recents privacy** — Open a conversation, enter Android Recents, and inspect the task preview. → **Expected:** The preview is protected according to Device privacy settings and does not expose chat content when screenshots are blocked.
10. [ ] **INT-010 — Upgrade with retained data** — Install the candidate over the previous release containing the prepared accounts/chats. → **Expected:** Accounts, settings, drafts, folders, and message history remain available and migrations do not repeat or reset user choices.

### Onboarding, sign-in, and recovery

1. [ ] **ONB-001 — Landing screen** — Open a clean install in portrait and landscape. → **Expected:** White Noise branding, rotating slogan, Log in, Sign up, and any available Amber action remain readable inside safe areas.
2. [ ] **ONB-002 — Offline sign-up** — Disable networking and tap Sign up. → **Expected:** No identity is partially created; an inline offline notice appears with Retry and dismissal behavior.
3. [ ] **ONB-003 — Create identity** — Restore networking, retry Sign up, and wait. → **Expected:** Only the tapped action shows progress, duplicate taps are ignored, and a usable new account reaches Chats.
4. [ ] **ONB-004 — Open login and Back** — From onboarding tap Log in, type synthetic text, then use Back. → **Expected:** The sign-in form opens with its key field and validation; Back returns to landing without starting import.
5. [ ] **ONB-005 — Empty and malformed key** — Submit an empty value, an `npub`, random text, and a checksum-invalid `nsec`. → **Expected:** Each is rejected inline with actionable text; no account is added and the app stays responsive.
6. [ ] **ONB-006 — Valid private-key sign-in** — Paste Alice's disposable valid `nsec` and submit. → **Expected:** Progress is visible, sensitive clipboard content is cleared after success, and Alice's Chats screen opens.
7. [ ] **ONB-007 — Sign-in failure and retry** — Interrupt the network after submitting a valid disposable key, wait for failure, restore it, and retry. → **Expected:** The error distinguishes retry from invalid input and retry succeeds without duplicate accounts.
8. [ ] **ONB-008 — Setup recovery consent** — Sign in with a fixture whose remote setup requires recovery, read the warning, cancel once, then confirm. → **Expected:** Cancel leaves setup unchanged; confirm runs one recovery attempt and reports success or a specific failure without repeatedly prompting.
9. [ ] **ONB-009 — Saved-account continuation** — Sign out without wiping a retained test account, return to onboarding, and tap its saved-account action. → **Expected:** The named account shows a single reactivation progress state and returns to that account.
10. [ ] **ONB-010 — Amber absent** — Use a device without a NIP-55 signer and open onboarding. → **Expected:** Login with Amber is not offered and ordinary key sign-in remains available.
11. [ ] **ONB-011 — Amber present and cancelled** — Install Amber, tap Login with Amber, then cancel in Amber. → **Expected:** White Noise returns to onboarding, clears its busy state, and creates no account.
12. [ ] **ONB-012 — Amber success/error** — Complete disposable Amber login, then repeat with a denied or failed signer response. → **Expected:** Success opens the matching account; denial/failure shows a recoverable result without exposing key material or trapping the UI.

### App shell, accounts, and navigation

1. [ ] **NAV-001 — Chats to Settings** — From Chats open Settings, then use the top back arrow and Android Back in separate visits. → **Expected:** Both return to Chats rather than exiting the app.
2. [ ] **NAV-002 — Settings detail hierarchy** — Open Appearance, then Action color; press Back twice. Repeat About → Developer. → **Expected:** Back returns through the immediate parent (Action color → Appearance → Settings; Developer → About → Help) with no skipped screen.
3. [ ] **NAV-003 — Settings scroll retention** — Scroll Settings near the bottom, open a detail, and return. → **Expected:** The prior Settings position is restored.
4. [ ] **NAV-004 — Account selector** — Open the account selector from Chats and Settings, dismiss by swipe/outside tap, then reopen. → **Expected:** Both entry points show the same accounts and active selection; dismissal changes nothing.
5. [ ] **NAV-005 — Switch account** — Switch Alice → Bob from the selector, then return to Chats. → **Expected:** Profile, chats, unread counts, drafts, folders, settings, and notifications all belong to Bob; no Alice content flashes.
6. [ ] **NAV-006 — Rapid account switching** — Switch Alice → Bob → Alice while lists are loading. → **Expected:** The final UI binds to Alice, stale Bob results do not replace it, and controls do not remain busy.
7. [ ] **NAV-007 — Add account from selector** — Tap Add account, import Carol's disposable account, and finish. → **Expected:** Carol is added once and becomes available in the selector without removing Alice or Bob.
8. [ ] **NAV-008 — Conversation return** — Open a chat, inspect details/profile/media, and unwind each route with Back. → **Expected:** Each route returns to the originating conversation and preserves its transcript position and draft.
9. [ ] **NAV-009 — Rotation and recreation** — Rotate on Chats, Settings detail, a modal sheet, and a conversation with a draft. → **Expected:** Content remains within safe bounds; durable state is retained and transient dialogs either restore safely or dismiss without acting.
10. [ ] **NAV-010 — External return** — Open a browser/store/system-settings action from White Noise and return with Android Back/Recents. → **Expected:** The correct White Noise account and prior screen resume once.

### Chat list, folders, and chat actions

1. [ ] **CHL-001 — Empty chat list** — Open Chats for a new account with no conversations. → **Expected:** A clear empty state and New message action appear; no stale rows from another account are visible.
2. [ ] **CHL-002 — Populated list** — Open a prepared account. → **Expected:** Direct/group rows show the correct title/avatar, last-message preview, time, delivery/unread state, typing/draft indicators when applicable, and stable ordering.
3. [ ] **CHL-003 — Incoming row update** — Have Bob send Alice a message while Alice remains on Chats. → **Expected:** The matching row updates once, moves according to recency, and unread state appears without losing scroll unexpectedly.
4. [ ] **CHL-004 — Draft preview** — Type but do not send in a chat, return to Chats, reopen the chat, then send. → **Expected:** A Draft preview appears, the exact draft restores, and the draft marker clears after send.
5. [ ] **CHL-005 — Open and return position** — Scroll a long chat list, open a row, then Back. → **Expected:** The list returns near the prior viewport and the opened conversation is correct.
6. [ ] **CHL-006 — Pin/unpin** — Long-press a chat, pin it, then unpin it. → **Expected:** Pinned state and ordering update consistently and survive relaunch.
7. [ ] **CHL-007 — Archive/unarchive** — Archive a chat, open the archived-only folder/view, then unarchive it. → **Expected:** The chat leaves the main list, appears in Archived, and returns to the main list after unarchive.
8. [ ] **CHL-008 — Mark read/unread** — Mark a read chat unread, open it, then return. → **Expected:** Unread decoration/count appears, then clears after the conversation is read.
9. [ ] **CHL-009 — Mute shortcut** — Mute a chat from its list action, choose a duration, then unmute. → **Expected:** Mute state is visible, the chosen expiry is represented correctly, and unmute restores notification eligibility.
10. [ ] **CHL-010 — Delete chat confirmation** — Invoke Delete on a disposable chat, cancel, then confirm. → **Expected:** Cancel preserves the chat; confirm removes only that chat and reports failure without hiding it if deletion fails.
11. [ ] **CHL-011 — Multi-selection** — Long-press one row, drag or tap to select multiple visible chats, deselect one, then exit selection with Back. → **Expected:** Count/actions match selected rows and Back clears selection before leaving Chats.
12. [ ] **CHL-012 — Bulk actions and pinned boundary** — Apply each available bulk action to disposable selected chats, including mixed pinned/unpinned rows. → **Expected:** Only selected rows change, disabled combinations are explained, and list ordering remains valid.
13. [ ] **FLD-001 — Folder filter** — Select every configured folder chip and return to All chats. → **Expected:** Only chats matching that folder's rules appear; the selected chip is clear and All restores the complete active list.
14. [ ] **FLD-002 — Folder changes while selected** — Edit a folder's membership, return to Chats, and select it. → **Expected:** Its contents update without duplicated rows or a stale empty state.
15. [ ] **CHL-015 — Partial load failure** — With cached rows present, interrupt relay access and refresh. → **Expected:** Existing rows remain usable and a non-blocking error/retry indication appears; a full-screen error is used only when no useful rows exist.

### Search and starting conversations

1. [ ] **FIND-001 — Open/close search** — Tap Search, type text, then press Back. → **Expected:** The field gains focus and keyboard; Back closes any filter sheet first, then search, before it can exit the app.
2. [ ] **FIND-002 — Search titles and profiles** — Search by direct-contact display name, group title, and case/diacritic variations. → **Expected:** Matching sections/rows appear without duplicates and unrelated rows stay hidden.
3. [ ] **FIND-003 — Search message bodies** — Search for a unique word in an older message and tap the result. → **Expected:** The owning chat opens and scrolls/highlights or otherwise identifies the matching message.
4. [ ] **FIND-004 — Search no results** — Enter a unique nonexistent term, then clear it. → **Expected:** An explicit no-results state appears and clearing restores the prior list.
5. [ ] **FIND-005 — Valid `npub` search** — Paste Bob's valid `npub` into search and choose the resolved profile/action. → **Expected:** Bob resolves locally and the correct profile/direct-chat path opens.
6. [ ] **FIND-006 — Invalid `npub` search** — Paste a checksum-invalid `npub`. → **Expected:** A specific invalid-identifier result appears and no conversation starts.
7. [ ] **FIND-007 — NIP-05 search** — Search a controlled valid NIP-05 address, then a nonexistent address. → **Expected:** A bounded resolving state leads to the correct profile for the valid address and an explicit not-found result for the invalid one.
8. [ ] **FIND-008 — Search filters** — Open filters and exercise each displayed type, chat/person, media/content, and date option individually and in a supported combination. → **Expected:** Applied filters are summarized, results satisfy all selected filters, and Clear removes them.
9. [ ] **FIND-009 — Custom date range** — Pick From/To dates, cancel once, then apply a valid range; also attempt an invalid reversed range. → **Expected:** Cancel preserves prior filters, a valid range filters correctly, and an invalid range cannot be applied silently.
10. [ ] **FIND-010 — Voice search** — Tap voice input, grant/cancel the system recognizer, and speak a synthetic query. → **Expected:** Success inserts recognized text once; cancellation leaves the query unchanged; missing recognizer shows a recoverable result.
11. [ ] **NCH-001 — New direct chat** — Tap New message, search/select Bob, and start a conversation. → **Expected:** Existing DM is reused when present; otherwise the profile/start-chat flow creates one correct DM without duplicates.
12. [ ] **NCH-002 — New group** — From New message select multiple contacts, continue, set a name/image or emoji, go Back/forward, and create. → **Expected:** Selections survive navigation, validation blocks an invalid setup, and one group containing exactly the chosen members opens.

### Conversation, composer, and delivery states

1. [ ] **CON-001 — Conversation header** — Open a DM and a group. → **Expected:** Header title/avatar/member context, back action, and details affordance identify the correct conversation.
2. [ ] **CON-002 — Transcript chronology** — Inspect a long transcript with date boundaries and multiple senders. → **Expected:** Messages are chronological, date/unread separators are intelligible, consecutive-message grouping is correct, and latest content is reachable.
3. [ ] **CON-003 — Pagination** — Scroll repeatedly to the oldest prepared messages. → **Expected:** Older pages load without duplicates, jumps, or losing already loaded content; end-of-history is stable.
4. [ ] **CON-004 — Incoming foreground message** — Keep Alice's chat with Bob open while Bob sends. → **Expected:** The message appears once, appropriate haptic/visual feedback occurs, and no redundant notification remains for the foreground chat.
5. [ ] **CON-005 — Plain send** — Type a unique short message and tap Send. → **Expected:** The composer clears once, a pending/sending state becomes sent, Bob receives exactly one matching message, and ordering is correct.
6. [ ] **CON-006 — Send failure/retry** — Disable networking, send a unique message, restore networking, and use Retry. → **Expected:** Failure remains visibly attached to the unsent message; retry sends that message once and removes the failure state.
7. [ ] **CON-007 — Multi-line/Enter behavior** — Test both Appearance → Enter key settings using the same draft. → **Expected:** Send mode sends on Enter and New line mode inserts a newline; the visible send button remains usable in both.
8. [ ] **CON-008 — Draft lifecycle** — Type multiline text, switch chats/accounts, background, and relaunch before returning. → **Expected:** Draft belongs only to its original account/chat and restores exactly; clearing it remains cleared.
9. [ ] **CON-009 — Empty and oversized input** — Try spaces-only input and a message at/over the supported limit. → **Expected:** Empty content cannot send; length behavior is explicit and never truncates or duplicates silently.
10. [ ] **CON-010 — Emoji picker** — Open emoji, browse/search categories if shown, insert several emoji, then dismiss by transcript tap and Back. → **Expected:** Emoji insert at the cursor; dismissal does not erase the draft or leave the keyboard/pane stuck.
11. [ ] **CON-011 — Mentions** — In a group type `@`, filter/select Bob, send, and have Bob open it. → **Expected:** Picker shows eligible members, the mention renders distinctly and links to Bob's profile, and Bob gets mention behavior rather than plain-text behavior.
12. [ ] **CON-012 — Reply composer** — Reply to an older text and an attachment, edit the reply draft, cancel once, then send. → **Expected:** Reply context identifies the source, cancel returns to normal compose without sending, and sent reply navigates back to the source when tapped.
13. [ ] **CON-013 — Composer/IME transitions** — Alternate keyboard, emoji, attachment pane, and transcript taps; rotate once. → **Expected:** Only one input surface owns the space, the composer stays above system insets, and focus/draft remain correct.
14. [ ] **CON-014 — Scroll/new-message control** — Scroll far from latest, receive several messages, then use the jump-to-latest control. → **Expected:** Reading position is not stolen; unread/new count is honest; the control reaches the latest message.
15. [ ] **CON-015 — Conversation load failure** — Open a cached chat while offline and trigger older-message loading. → **Expected:** Cached transcript remains readable, failure is placed without covering controls, and retry works after reconnection.
16. [ ] **CON-016 — Timeline warnings and invalidated content** — Open fixtures containing deleted, invalidated, partially visible, and noncanonical-history messages, then retry older-history loading. → **Expected:** Each condition has distinct explanatory UI, retained valid messages remain usable, and Retry never converts unverified content into an ordinary message.

### Attachments, media, contacts, and location

1. [ ] **MED-001 — Attachment pane** — Tap Attach in a conversation. → **Expected:** Recent media (when permitted), Gallery, Camera, Document, Location, User, and Contact tiles are visible; unavailable tiles are clearly marked Coming soon rather than acting enabled.
2. [ ] **MED-002 — Recent-media permission** — Open attachments before media permission, grant selected/full access as offered, and reopen. → **Expected:** Permission is requested only after the relevant action and the recent strip shows only permitted media.
3. [ ] **MED-003 — Gallery image** — Select a small image, cancel preview once, then select and send. → **Expected:** Cancel sends nothing; preview is accurate; sent image renders on both devices with delivery state.
4. [ ] **MED-004 — Camera permission and photo** — Tap Camera, deny permission once, then grant and capture a photo. → **Expected:** Denial returns safely with guidance; grant launches capture; cancel sends nothing; accepted photo reaches preview/send.
5. [ ] **MED-005 — Photo quality** — For a large image open the quality chooser and send with each offered quality in separate messages. → **Expected:** Selection is visible, each upload completes, dimensions/size reflect the choice, and the preview is not rotated or corrupted.
6. [ ] **MED-006 — Photo editor** — Open an image in the editor, apply every available transform/markup, undo/cancel once, then save/send. → **Expected:** Cancel preserves the original and sends nothing; saved edits match preview and remain readable after download.
7. [ ] **MED-007 — Video** — Select and send a supported video, then play/pause/seek/full-screen on both devices. → **Expected:** Upload/download progress is honest, thumbnail and duration are sensible, playback controls work, and leaving playback releases audio.
8. [ ] **MED-008 — Audio/voice message** — Record or select audio, grant/deny microphone where applicable, send, then play/pause/seek. → **Expected:** Denial is recoverable; recording state/timer are clear; the message is delivered once and playback state is understandable.
9. [ ] **MED-009 — Document** — Pick a PDF and another supported document, cancel once, then send and open/download. → **Expected:** Filename/type/size are correct, cancel sends nothing, progress is visible, and Android opens the downloaded file with a compatible app.
10. [ ] **MED-010 — Text attachment reader** — Open a sent plain-text file, search/select/copy or rename where offered, rotate, then close. → **Expected:** Content and filename render safely, state survives rotation, copy uses selected text, and Back returns to the chat.
11. [ ] **MED-011 — Unsupported/oversized file** — Select an unsupported and an over-limit fixture. → **Expected:** The app rejects it with an actionable reason before or during send and never posts a misleading successful bubble.
12. [ ] **MED-012 — Location share** — Open Location, grant coarse or selected permission, choose a synthetic place, cancel once, then share. → **Expected:** Permission disclosure matches the request, cancel sends nothing, and the sent card opens the intended map coordinates without leaking a prior location.
13. [ ] **MED-013 — User share** — Choose Bob from User and send the profile card; tap it on Carol's device. → **Expected:** The card identifies Bob by current profile/`npub`, opens Bob's profile, and never starts a chat as the wrong account.
14. [ ] **MED-014 — Contact share** — Tap Contact, confirm White Noise does not request contacts permission, select a synthetic contact in Android's contact picker, inspect the preview, cancel once, then repeat and send. → **Expected:** Cancel sends nothing; only the selected contact's displayed name and phone number are previewed/shared; the card is clearly a phone contact rather than a White Noise identity.
15. [ ] **MED-015 — Full-screen image viewer** — Open a multi-image transcript, swipe pages, zoom/pan, rotate, and Back. → **Expected:** Correct media/page indicator persists, gestures do not trigger chat navigation, and Back returns to the source message.
16. [ ] **MED-016 — Download/save/share** — From supported media use Save and Android Share. → **Expected:** Save creates one readable file with correct content; Share opens a chooser with only intended content/URI access and returns safely.
17. [ ] **MED-017 — Missing/expired/restricted media** — Open prepared messages representing pending, failed, malformed, expired, unavailable, and restricted attachments. → **Expected:** Each state has distinct honest copy and only valid recovery actions; forwarding is disabled with the corresponding reason.
18. [ ] **MED-018 — APK attachment installation safety** — Open controlled valid, tampered, unsupported-ABI, and non-APK files named as APKs; test without an installer and with install-unknown-apps permission denied, then grant only for the controlled valid file. → **Expected:** Only a verified compatible APK reaches Android's installer; invalid/unsupported/no-installer/permission states are explicit, and no attachment is silently installed.

### Message rendering and actions

1. [ ] **MSG-001 — Text and links** — Send plain text, punctuation, emoji, multiline text, a URL, and selectable text. → **Expected:** Text is exact, links are visually distinct and open only after deliberate tap, and selection/copy preserves characters.
2. [ ] **MSG-002 — Markdown** — Send supported headings, emphasis, lists, quote, inline/fenced code, and link syntax. → **Expected:** Supported Markdown renders consistently in bubbles and full-screen text; code remains legible and copyable.
3. [ ] **RDR-001 — Nostr identifiers/events** — Send valid `npub`/`nprofile`, `note`/`nevent`/`naddr`/`nrelay`, and malformed Nostr identifiers, then tap each. → **Expected:** Resolved `npub`/`nprofile` values open the in-app profile; unresolved profiles fall back to shortened code-style text; event/address/relay identifiers stay styled but inert; malformed values remain harmless literal text.
4. [ ] **MSG-004 — Long-press menu** — Long-press incoming/outgoing text and each attachment type. → **Expected:** The anchored menu stays on-screen and offers only valid actions for ownership/type/state.
5. [ ] **MSG-005 — Quick/custom reaction** — Add a quick reaction, open the full emoji picker for another, tap again to remove, and inspect reaction details. → **Expected:** Counts/actors synchronize on both devices, duplicate self-reactions do not accumulate, and details identify reactors.
6. [ ] **MSG-006 — Reply navigation** — Tap a reply preview whose source is loaded, then one requiring older history. → **Expected:** The transcript reaches and identifies the exact source or gives a clear unavailable result without jumping elsewhere.
7. [ ] **MSG-007 — Edit message** — Edit Alice's sent text, cancel once, then save a real change and inspect edit history. → **Expected:** Only Alice's editable message offers Edit; cancel preserves it; update marks Edited and history shows correct versions/times.
8. [ ] **MSG-008 — Delete message** — Delete a disposable outgoing message, cancel once, then confirm each offered local/everyone scope. → **Expected:** Dialog explains scope; cancel preserves; confirmed deletion updates only intended devices/content and leaves no false success.
9. [ ] **MSG-009 — Copy/select/share** — Use Copy text, Select text, and Android Share on supported messages. → **Expected:** Clipboard/share payload equals the chosen content, sensitive extras are absent, and chooser cancellation does not mutate the chat.
10. [ ] **MSG-010 — Forward one/many** — Forward a supported message to one chat and then multiple chats using search and selection. → **Expected:** Origin chat is excluded, progress names each target, every target receives one freshly sent payload, and no source encryption/author attribution leaks.
11. [ ] **MSG-011 — Partial forward failure** — Make one selected target unavailable and forward to it plus a valid target. → **Expected:** Per-target progress/result distinguishes success and failure; Retry affects only failed targets and does not duplicate successes.
12. [ ] **MSG-012 — Multi-message selection** — Enter Select, choose mixed incoming/outgoing messages, scroll, deselect, and apply each enabled batch action. → **Expected:** Selection/count survives scrolling, invalid destructive actions are disabled, and only selected messages are affected.
13. [ ] **MSG-013 — Message info** — Open Info for sent, received, pending, failed, and attachment messages. → **Expected:** IDs/timestamps/sender/delivery details match the message, values can be copied where offered, and no unrelated secret is shown.
14. [ ] **TTS-001 — Text to speech** — Choose Speak on a text message, pause/resume, change rate, move next/previous, and stop. → **Expected:** The correct content is highlighted/spoken, transport remains accessible across chat/list navigation, and Stop clears playback.
15. [ ] **MSG-015 — Locale/time rendering** — Change device 12/24-hour and app language settings, then inspect dates, status, and edited/reaction labels. → **Expected:** User text is unchanged while app-owned date/time/copy follows locale without clipping or ambiguous ordering.

### Group, invite, profile, and membership flows

1. [ ] **GRP-001 — Group details** — Open prepared group details and scroll through all sections. → **Expected:** Name/avatar/emoji, description, members, media, notification/disappearing settings, and actions describe the current group.
2. [ ] **GRP-002 — Edit name/description** — As admin edit name/description, cancel once, then save. → **Expected:** Validation prevents invalid input; cancel preserves old values; save updates header/list/details on every member device.
3. [ ] **GRP-003 — Group image/emoji** — Set, replace, and remove a group image or emoji using available picker/search actions. → **Expected:** Preview is correct, cancellation is non-destructive, and the final avatar synchronizes without stale cached images.
4. [ ] **GRP-004 — Non-admin restrictions** — Open the same group as non-admin Carol. → **Expected:** Admin-only edit/member actions are absent or disabled with explanation; ordinary messaging/details remain available.
5. [ ] **GRP-005 — Add member** — As admin add a disposable fourth identity, cancel once, then confirm. → **Expected:** Search/selection excludes existing members, confirmation changes membership once, and all devices show the new member/system event.
6. [ ] **GRP-006 — Remove member** — Remove the disposable member, cancel once, then confirm. → **Expected:** Scope is explicit; cancel preserves membership; confirm removes exactly that member and updates access/state on all devices.
7. [ ] **GRP-007 — Promote/demote admin** — Promote Bob, verify Bob's controls, then demote Bob where allowed. → **Expected:** Role badges/actions synchronize and the last-admin invariant cannot be violated.
8. [ ] **GRP-008 — Transfer admin** — Transfer Alice's admin role to Bob, cancel once, then confirm. → **Expected:** Warning names the effect, cancel preserves roles, and confirmation atomically changes the available controls.
9. [ ] **GRP-009 — Leave group** — As a non-sole admin leave a disposable group, cancel once, then confirm. → **Expected:** Cancel preserves membership; confirm removes the group or marks it left and prevents further send while preserving honest local history behavior.
10. [ ] **GRP-010 — Sole-admin leave/delete path** — As sole admin attempt to leave, choose a successor or destructive option as offered. → **Expected:** The app blocks orphaning the group, clearly separates transfer from deletion, and applies only the confirmed outcome.
11. [ ] **GRP-011 — Invite preview** — Open a pending invite from Chats/notification. → **Expected:** Inviter, proposed group, known members, and Accept/Decline actions are visible without exposing unrelated account data.
12. [ ] **GRP-012 — Accept invite** — Accept on the intended account while online. → **Expected:** Progress is non-duplicating, one group opens/appears, and membership/message access become available.
13. [ ] **GRP-013 — Decline invite** — Decline a disposable invite, cancel once if confirmed, then complete. → **Expected:** Cancel preserves the invite; confirm removes only that invite and it does not open as joined.
14. [ ] **GRP-014 — Invite failure/retry** — Attempt Accept offline, restore networking, and retry. → **Expected:** The invite remains pending through failure, reports a recoverable error, and retry resolves it once.
15. [ ] **GRP-015 — Mute durations** — Test each preset and a custom date/time, including invalid/past input, then unmute. → **Expected:** Selected expiry is displayed accurately, invalid input is blocked, and notification behavior changes/restores at the expected time.
16. [ ] **GRP-016 — Conversation notification overrides** — Change notify level, vibration pattern, and other displayed per-conversation overrides. → **Expected:** Selection persists, summary reflects it, and a synthetic qualifying/non-qualifying event follows the override.
17. [ ] **GRP-017 — Disappearing messages** — Choose Off, a preset, and custom duration in a disposable group; send messages and wait/advance to expiry. → **Expected:** Current policy is visible, only messages under the active policy expire, and expiration removes content consistently without affecting newer exemptions.
18. [ ] **GRP-018 — Group media** — Open shared media from details, switch each displayed media category, and open a result. → **Expected:** Only that group's media appears, empty/loading/error states are honest, and Back returns to details.
19. [ ] **GRP-019 — Group TTS auto-read** — Change the displayed auto-read choice and receive matching/nonmatching messages. → **Expected:** Summary persists and only messages covered by the selected policy are spoken.
20. [ ] **GRP-020 — Group state while offline** — Open details offline and attempt a membership/settings mutation. → **Expected:** Cached state remains readable, mutation does not claim success, and retry after reconnection produces one update.

### Profiles and account keys

1. [ ] **ACC-001 — Profile sheet** — Open own, known-contact, unknown, and group-member profiles from every available entry point. → **Expected:** The same identity is shown consistently with correct name/avatar/`npub` and relationship/actions.
2. [ ] **ACC-002 — Profile actions** — Copy `npub`, open QR, message/add-to-group where available, and dismiss. → **Expected:** Copied/QR identity decodes to the shown account; actions use the active account and return correctly.
3. [ ] **ACC-003 — QR scanner** — Open scanner, deny/grant camera, scan valid `npub`/supported QR, malformed QR, and cancel. → **Expected:** Permission is contextual; valid data opens the intended profile/action once; malformed data is rejected safely; cancel changes nothing.
4. [ ] **ACC-004 — Full-screen avatar** — Tap local/remote/missing avatars, zoom if supported, rotate, and Back. → **Expected:** Correct image or deterministic placeholder appears, failure does not loop, and Back restores the source.
5. [ ] **PRO-001 — Edit profile text** — Edit display name and every displayed profile field, cancel once, then save. → **Expected:** Validation/counters are clear, cancel preserves old data, and saved values update all local surfaces and Bob's view after propagation.
6. [ ] **PRO-002 — Edit profile picture** — Choose/capture, crop/edit if offered, cancel, save, then remove. → **Expected:** Cancel preserves old avatar; save/removed state propagates without exposing the source file path.
7. [ ] **ACC-007 — Private contact details** — Open any private-details dialog for a controlled contact and edit/clear values if supported. → **Expected:** Private fields are labeled, stay local/private as documented, and never appear in another account's profile.
8. [ ] **ACC-008 — Add profile to groups** — From Bob's profile add him to one eligible group and inspect groups where ineligible. → **Expected:** Eligible choices are accurate, existing membership is not duplicated, and ineligible groups are omitted or explained.
9. [ ] **ACC-009 — Account and keys screen** — Open Settings → Account and keys. → **Expected:** Public/private key and backup actions are clearly distinguished; secret material is hidden until deliberate authentication/reveal.
10. [ ] **ACC-010 — Copy public versus secret key** — Copy the displayed public identity and, on a disposable account, deliberately reveal/copy secret material. → **Expected:** Each confirmation/label matches sensitivity, copied data is correct, and evidence/screenshots do not expose it.
11. [ ] **ACC-011 — Encrypted backup create** — On a disposable account create an encrypted backup with a test passphrase, trying empty and mismatched input before valid matching input; inspect and copy the displayed `ncryptsec` value, wait once for the sixty-second timeout, then create it again and choose Hide. → **Expected:** Invalid input cannot create a backup; valid input reveals one nonempty passphrase-protected `ncryptsec` value in a secure sheet; Copy places that exact value in the sensitive clipboard and Android 13+ does not render it in the clipboard preview; the timeout clears the value while leaving the sheet at passphrase entry, while Hide, dismissal, or leaving the foreground clears it sooner; no action creates or requests an Android file destination.
12. [ ] **ACC-012 — Encrypted backup import limitation** — On the sign-in surface enter the copied `ncryptsec` value from `ACC-011` and attempt import. → **Expected:** The app reports a safe import failure because encrypted-secret-key import is not yet supported, creates no account, requests no restore passphrase, and does not claim that the backup was restored.
13. [ ] **ACC-013 — Sign out** — Sign out Alice without wipe, cancel once, then confirm. → **Expected:** Cancel preserves session; confirm removes Alice from active UI but retains only the explicitly described local account data for continuation.
14. [ ] **ACC-014 — Sign out and wipe** — On a disposable account choose sign out and wipe, cancel once, then confirm and wait through completion. → **Expected:** Progress cannot be double-started, result reports success/failure honestly, and wiped account data/drafts/notifications do not reappear after relaunch.
15. [ ] **ACC-015 — Multi-account isolation after wipe** — Wipe Carol while Alice remains saved, then switch/relaunch. → **Expected:** Alice is intact; Carol is absent; no Carol notification, draft, folder, profile cache, or media remains visible.
16. [ ] **ACC-016 — Key packages** — Open Key packages, refresh, and exercise each displayed publish/delete/recover action on a disposable account. → **Expected:** Current/pending/error state is labeled, mutation progress is singular, and refresh reflects the authoritative outcome.
17. [ ] **ACC-017 — Account-bound settings** — Give Alice and Bob different relay, notification, folder, and privacy-related choices, then switch repeatedly. → **Expected:** Account-scoped values follow the account and device-scoped values remain device-wide as labeled.
18. [ ] **PRO-003 — Follow and unfollow** — Open Bob's profile, follow, verify the relationship on both profile and search surfaces, then unfollow; repeat one mutation offline. → **Expected:** Progress is singular, successful state is consistent across surfaces, and failure retains the prior relationship with a recoverable error.
19. [ ] **PRO-004 — Nickname and private notes** — Add, edit, cancel, save, and clear a synthetic nickname and private note for Bob. → **Expected:** Cancel preserves old values, saved values are visible only to Alice where labeled, clearing removes them, and Bob/Carol never receive the private fields.
20. [ ] **PRO-005 — Contact sharing and conversation shortcuts** — From Bob's profile exercise Message, Invite to White Noise/share chooser, Start new group, and Add to group across eligible, already-member, empty, loading, and failure states. → **Expected:** Existing DM is reused, sharing occurs only after chooser confirmation, new-group selection contains Bob once, and group eligibility/progress/errors are accurate.

### Settings and customization

1. [ ] **SET-001 — Settings inventory** — Scroll Settings from top to bottom. → **Expected:** Account, Profile, Account and keys, Relays, Key packages, Appearance, Chat folders, Data and storage, Notifications, Text to speech, Dictation, Device privacy, AI agents, Help/support, update (when enabled), version/build information, and Back are present as applicable.
2. [ ] **SET-002 — Theme modes** — Select System, Light, Dark, and AMOLED; relaunch after each. → **Expected:** Selection applies immediately and persists; AMOLED uses true-black principal surfaces without unreadable borders/text.
3. [ ] **SET-003 — System theme** — Choose System and toggle Android light/dark while White Noise is foreground/background. → **Expected:** White Noise follows the system without losing the current route or flashing the wrong theme.
4. [ ] **SET-004 — Action color** — Choose presets and custom color, cancel once, then save and inspect buttons/links/selections. → **Expected:** Preview matches applied color, contrast remains legible, cancel preserves the previous value, and reset restores default.
5. [ ] **SET-005 — Chat bubble colors** — Change outgoing/incoming or displayed bubble colors, inspect preview and a real chat, then reset. → **Expected:** Only intended bubbles change, text/status/reactions remain legible in all themes, and reset is complete.
6. [ ] **SET-006 — Font size** — Select Small, Default, Large, and Extra large, visiting Chats/conversation/settings each time. → **Expected:** Scale applies live and persists; text does not clip, overlap, or hide primary actions.
7. [ ] **SET-007 — App font** — Select every displayed font including System. → **Expected:** Picker previews and app text use the selected family, glyphs/emoji remain present, and choice persists.
8. [ ] **SET-008 — App language** — Select System, English, and at least one non-English locale, relaunch, then return to System. → **Expected:** App-owned text changes consistently, user content/identifiers do not, and selection persists.
9. [ ] **SET-009 — Chat folders CRUD** — Create, name, configure, save, edit, reorder if offered, cancel, and delete a folder. → **Expected:** Validation is explicit, cancel is non-destructive, chips/order/rules update, and deletion does not delete chats.
10. [ ] **SET-010 — Data/storage inventory** — Open Data and storage and exercise every displayed network/media auto-download toggle. → **Expected:** Summaries match selections and choices persist after relaunch/account switch according to scope.
11. [ ] **SET-011 — Auto-download behavior** — Send each media type while recipient is on allowed then disallowed network conditions. → **Expected:** Allowed types download automatically; disallowed types remain manual with size/type information; changing policy affects future work.
12. [ ] **SET-012 — Stop automatic downloads** — Queue multiple safe downloads, use Stop automatic downloads, confirm/cancel, then resume manually. → **Expected:** Cancel keeps queue; confirm stops only automatic work, communicates backlog state, and manual retrieval remains possible.
13. [ ] **TTS-002 — Text-to-speech settings** — Select engine/voice/language/rate options displayed, preview when offered, relaunch, and speak a message. → **Expected:** Unsupported choices are labeled, preview uses selection, and playback uses persisted settings.
14. [ ] **DIC-001 — Dictation disclosure** — Start dictation for the first time, cancel disclosure, then accept and dictate synthetic text. → **Expected:** No audio leaves the device before consent, cancel starts nothing, and accepted result is reviewable before insertion/send.
15. [ ] **DIC-002 — Dictation settings/results and background controls** — Exercise each displayed dictation mode/provider/language option plus finish/cancel/error paths. During capture, background White Noise and use each notification action (Cancel, Paste, and Send), then reopen the origin conversation. → **Expected:** Selection persists, busy state is visible, each action changes only the immutable origin draft/chat, any unrelated current conversation remains unchanged, reopening the origin shows the intended result, Cancel preserves the prior draft, and failures provide recovery without sending text.
16. [ ] **SET-016 — AI agent connectors** — Open AI agents, expand every connector prompt, copy one, collapse it, and open connector docs. → **Expected:** Prompt includes Alice's current `npub`, copy confirmation appears, disclosure explains clipboard use, and docs open externally then return safely.
17. [ ] **SET-017 — AI agents without account** — Reach the surface without an active account if possible. → **Expected:** Copy/expand identity-dependent controls are disabled with a “no active account” explanation, not stale data.
18. [ ] **SET-018 — Help and support** — Open Help, invoke every displayed documentation/support action, and open Chat with support with/without an existing support DM. → **Expected:** External links are correct; existing DM is reused; otherwise the canonical support profile/start-chat flow appears.
19. [ ] **SET-019 — About/build info** — Open Help → About and compare version/MarmotKit/build information with the APK record. → **Expected:** Values match the installed artifact, links/licenses shown open correctly, and no secret build path is exposed.
20. [ ] **SET-020 — Developer/diagnostics entry** — Open About → Developer → Diagnostics using the documented gesture/row. → **Expected:** Entry is available only as intended for the build, Back hierarchy is correct, and diagnostics belong to the active account/runtime.
21. [ ] **SET-021 — Diagnostics refresh/copy** — Refresh every diagnostics section and use displayed copy/share actions. → **Expected:** Progress/errors are bounded, output updates, and exported data contains no private message text, key, prompt, or unrelated account data.
22. [ ] **SET-022 — Donate/external links** — Open Donate or other optional external links when displayed, then return. → **Expected:** The labeled trusted destination opens only after a tap; cancellation/return preserves Settings.
23. [ ] **SET-023 — Settings mutation failure** — Disable networking before changing a server-backed setting, then restore and retry. → **Expected:** Busy state ends, old value is retained on failure, error is honest, and one retry produces one mutation.
24. [ ] **SET-024 — Back from modal pickers** — Open each settings sheet/dialog and dismiss with Back/outside tap without selecting. → **Expected:** The prior value remains and focus/scroll return to the invoking row.
25. [ ] **SET-025 — Persistence matrix** — Change one value on every settings detail, force-stop/relaunch, upgrade the APK, and switch accounts. → **Expected:** Every value persists at its documented device/account/conversation scope and does not leak across scopes.
26. [ ] **TTS-003 — Unknown-engine trust warning** — Select an installed TTS engine that White Noise classifies as unknown, cancel the first warning, select it again and proceed, then leave and reselect the same engine. → **Expected:** Cancel keeps the previous engine; Proceed selects the unknown engine only after the warning; the acknowledgment is remembered for that exact engine so the warning does not repeat, while another unknown engine still warns.

### Notifications, background behavior, and app lock

1. [ ] **NTF-001 — Notification permission grant** — On Android 13+ deny initial permission, enable Local notifications in Settings, then grant the contextual prompt. → **Expected:** Denial leaves the toggle off with feedback; grant enables it and Android App info reflects permission.
2. [ ] **NTF-002 — Local notifications off/on** — Receive a DM with Local notifications off, then on while app is backgrounded. → **Expected:** Off produces no White Noise notification; on produces one notification in the Direct messages channel.
3. [ ] **NTF-003 — Keep connected** — Enable Keep connected, background/lock device, receive messages, then disable it and repeat. → **Expected:** Required foreground/service indication and delivery behavior match the setting; disabling removes ongoing connection behavior without breaking foreground use.
4. [ ] **NTF-004 — Native push availability** — Inspect/toggle Native push on available and unavailable builds/devices. → **Expected:** Available state can request permission and persist; unavailable state is disabled with explanatory copy and never pretends to enable.
5. [ ] **NTF-005 — Notification channels** — Open each category row: Direct messages, Group messages, Mentions, Reactions, Invites, Group membership, Agent activity, and App updates. → **Expected:** Android opens the matching channel settings, not the wrong channel or whole app page.
6. [ ] **NTF-006 — DM/group routing** — Background Alice and send one DM and one ordinary group message. → **Expected:** Each produces one notification in the correct channel with synthetic sender/group context.
7. [ ] **NTF-007 — Mention/reaction routing** — Mention Alice and react to Alice's message while backgrounded. → **Expected:** Events use Mentions/Reactions channels and do not also create duplicate generic notifications.
8. [ ] **NTF-008 — Invite/membership routing** — Send an invite and perform a membership change affecting Alice. → **Expected:** Distinct notifications use the correct channels and open the matching account/group state.
9. [ ] **NTF-009 — Notification tap** — With Alice active and then Bob active, tap an Alice notification. → **Expected:** The app switches/binds to Alice before showing the exact conversation/message; Bob content never flashes and the notification clears appropriately.
10. [ ] **NTF-010 — Notification actions/dismissal** — Exercise every displayed notification action and swipe-dismiss, including while app is foreground. → **Expected:** Each action affects the intended account/event once; dismissal has no chat-side effect; foreground conversation suppresses redundant alerts.
11. [ ] **NTF-011 — Locked-screen privacy** — Receive synthetic events with the device locked under each Android content-visibility setting. → **Expected:** White Noise honors OS/channel privacy; no secret key, full private content, or wrong-account data is revealed.
12. [ ] **NTF-012 — Per-chat override precedence** — Combine a global channel setting with mute/notify overrides and send ordinary, mention, and reaction events. → **Expected:** Per-chat choices override defaults as labeled and mute expiry restores expected delivery.
13. [ ] **NTF-013 — App update notification** — On a build/feed with a newer controlled version, allow the scheduled/foreground check. → **Expected:** One App updates channel notification appears and opens the update action for the installed variant.
14. [ ] **NTF-014 — Reboot persistence** — Enable required background notification settings, reboot, unlock, and send a synthetic message. → **Expected:** Settings persist and supported background delivery resumes without opening White Noise first, or the limitation is stated honestly.
15. [ ] **NTF-015 — Stale notification cleanup** — Sign out/wipe an account with outstanding notifications and tap any stale system entry. → **Expected:** Entries are removed or open a safe fallback; wiped account content cannot rehydrate.
16. [ ] **NTF-016 — Inline reply** — Reply with synthetic text from a DM notification while the app is backgrounded, first offline and then online. → **Expected:** Android shows sending/retry state without exposing another account; one reply is delivered when connectivity permits and appears once in the intended conversation.
17. [ ] **NTF-017 — Quick reaction choices** — Use every quick-reaction chip exposed by a message notification, repeat one chip rapidly, then open the conversation. → **Expected:** Exactly the selected reaction is applied once to the intended message/account and notification state updates without a duplicate generic alert.
18. [ ] **NTF-018 — Mark read from notification** — Use Mark read on DM and group notifications, including when another account is foregrounded. → **Expected:** Only the originating account/chat is marked read, its notification and unread badge clear, and foreground account state remains unchanged.

### Device privacy and audit controls

1. [ ] **SEC-001 — App-lock prerequisite** — On a device without secure credential inspect Require app unlock, then configure a PIN/biometric and return. → **Expected:** Toggle is initially disabled with guidance and becomes available after credential refresh.
2. [ ] **SEC-002 — Enable/disable app lock** — Enable Require app unlock, background beyond its delay, resume and authenticate; then disable. → **Expected:** Chat UI is covered before it can be read, success restores the prior route, cancellation keeps it locked, and disabling prevents future lock screens.
3. [ ] **SEC-003 — App-lock delays** — Test every displayed delay with boundary-short and boundary-long background intervals. → **Expected:** Lock appears only at/after the selected threshold and cannot be bypassed through rotation, notification tap, Recents, or account switch.
4. [ ] **SEC-004 — App-lock failure** — Fail/cancel system authentication repeatedly, then authenticate successfully. → **Expected:** No content flashes, attempts remain in the system credential flow, and successful unlock does not duplicate the destination.
5. [ ] **SEC-005 — Incognito keyboard** — Toggle Force incognito keyboard and inspect supported keyboard behavior in composer/search/profile fields. → **Expected:** Incognito request applies to sensitive text entry when enabled and normal behavior returns when disabled; unsupported keyboards do not produce a false claim.
6. [ ] **SEC-006 — Chat screenshots** — Toggle the screenshot protection setting and attempt screenshot/screen recording in Chats, a conversation, keys, and non-chat settings. → **Expected:** Protected surfaces are blocked exactly as labeled, allowed surfaces work when enabled, and setting persists.
7. [ ] **SEC-007 — Telemetry opt-in/out** — Toggle Telemetry on and off, relaunch, and inspect diagnostics. → **Expected:** Busy state resolves, selection persists, disabling stops export, and no message text, identity secret, prompt, or contact data is included.
8. [ ] **SEC-008 — Audit logs toggle** — Enable audit logs, perform synthetic actions, disable, and perform more. → **Expected:** State persists and only activity within enabled periods is represented according to the disclosure.
9. [ ] **SEC-009 — Export audit logs** — Tap Export, cancel consent, then confirm and inspect the Android share chooser using a safe local destination. → **Expected:** Cancel creates/shares nothing; consent explains sensitivity; exported files are readable, bounded, and omit secrets/message content.
10. [ ] **SEC-010 — Delete audit logs** — Tap Delete, cancel, export to prove preservation, then confirm delete and export again. → **Expected:** Cancel preserves logs; confirm removes existing logs; deletion does not disable future logging or delete app data.

### Relays, updates, and system intents

1. [ ] **SYS-001 — Relay list states** — Open Relays for a complete, incomplete, and loading/unavailable fixture. → **Expected:** NIP-65 and Inbox tabs plus complete/missing/no-projection states are distinct and accurate.
2. [ ] **SYS-002 — Relay URL validation** — Enter empty, malformed, `http://`, duplicate, unsupported-host, and valid `wss://` values. → **Expected:** Only acceptable nonduplicate URLs enable Add; errors identify the format/host rule.
3. [ ] **SYS-003 — Add/remove relay** — Add a disposable valid relay, cancel/remove where applicable, then remove it. → **Expected:** Singular progress appears, published lists refresh, minimum-required relays cannot be removed, and failure retains authoritative state.
4. [ ] **SYS-004 — Share into White Noise** — From another app share plain text, one image, multiple media/files, and unsupported content to White Noise. → **Expected:** The full-screen chat picker previews the payload accurately, lets the tester choose the sending account/chat, and does not send before confirmation.
5. [ ] **SYS-005 — Share picker navigation** — Search/select a target, switch sending account, press predictive/system Back, close, and repeat send. → **Expected:** Back/close cancels without sending; account switch refreshes eligible chats; confirmed share appears once in the selected chat.
6. [ ] **SYS-006 — Deep profile links** — Open a supported flavor-specific White Noise profile URI and `marmot://profile/<npub>` from another app in cold and warm states, then try a malformed, event, and invite URI. → **Expected:** Supported profile URIs open the correct in-app profile once; malformed and unsupported event/invite forms do not route to an unrelated screen or expose stale account data.
7. [ ] **SYS-007 — Clipboard lifecycle** — Copy ordinary text, `npub`, and a disposable secret-key value through their intended controls; background/finish flows. → **Expected:** Copy confirmations identify what was copied, sensitive clipboard content is cleared where promised, and another account is never substituted.
8. [ ] **SYS-008 — Zapstore self-update** — On a Zapstore build with a controlled newer release, check for update, cancel, download, interrupt/resume, verify, and launch installer. → **Expected:** Resolving/downloading/verifying/install-ready/errors are distinct, bytes/progress are honest, hash/signature policy blocks tampering, and Android installer receives the expected APK.
9. [ ] **SYS-009 — Play update action** — On a Play build invoke the displayed update action. → **Expected:** It opens the correct Play listing/update flow and never exposes Zapstore download/install UI.
10. [ ] **SYS-010 — Up-to-date/update failure** — Check when current, then with network/server failure. → **Expected:** Current state does not nag; failure is recoverable and does not claim an update installed or erase the last known version.
11. [ ] **SYS-011 — Android Direct Share shortcut** — From another app choose a White Noise conversation shortcut, first while its account is active and then while a different account is active; also invoke a stale shortcut. → **Expected:** A valid shortcut stages the payload for the exact account/chat and still requires the displayed send confirmation; a stale or wrong-account shortcut falls back to safe target selection without leaking chat details.

### Accessibility, layout, and resilience sweep

1. [ ] **AXY-001 — TalkBack traversal** — With TalkBack, traverse onboarding, Chats, conversation, attachment pane, profile, Settings, and all dialogs touched above. → **Expected:** Interactive controls have unique meaningful names/roles/states in visual order; decorative icons are not announced.
2. [ ] **AXY-002 — TalkBack actions** — Complete sign-in field validation, send, react, select, toggle, dismiss, and Back using TalkBack only. → **Expected:** State changes are announced, custom actions are operable, focus returns sensibly, and no gesture-only control blocks completion.
3. [ ] **AXY-003 — Large text/display** — Set Android font/display to largest supported values and White Noise font to Extra large; repeat core onboarding/send/settings flows. → **Expected:** Text wraps, lists scroll, primary/destructive actions remain reachable, and no content overlaps system bars.
4. [ ] **AXY-004 — Landscape/small height** — Rotate every major screen, open keyboard and modal on a short-height window. → **Expected:** Content scrolls or resizes, dialogs stay actionable, and composer/buttons are not hidden.
5. [ ] **AXY-005 — Tablet/foldable/resizable** — Resize across narrow/wide postures where available. → **Expected:** Content uses readable width, navigation does not duplicate, and state/selection/draft survive posture changes.
6. [ ] **AXY-006 — RTL** — Select an RTL system locale, relaunch, and traverse Chats/conversation/settings/media viewer. → **Expected:** Layout/directional icons mirror appropriately, text alignment is readable, and Back/media gestures remain correct.
7. [ ] **AXY-007 — Color/contrast** — Inspect all themes, custom colors, error/success/disabled states, and Android high-contrast/color-correction settings. → **Expected:** Meaning is not color-only and text/icons meet practical readability without disappearing.
8. [ ] **AXY-008 — Reduced animation** — Disable Android animations and repeat navigation, sheets, image viewer, account switch, and startup. → **Expected:** Final states appear without timing dependence, invisible controls, or stuck transition layers.
9. [ ] **AXY-009 — Touch targets and gestures** — Use one-handed taps near screen edges and system gesture navigation for top bars, FABs, reactions, menus, and composer. → **Expected:** Controls have reliable targets and do not conflict with Back/home gestures.
10. [ ] **AXY-010 — Keyboard-only/hardware keyboard** — Navigate fields/dialogs with Tab/Shift-Tab/Enter/Escape and test configured Enter behavior. → **Expected:** Focus is visible and ordered, Escape/Back dismisses the top layer, and Enter follows the selected setting.
11. [ ] **AXY-011 — Rapid repeat protection** — Rapidly tap create/send/retry/delete/invite/update/settings mutation actions. → **Expected:** At most one operation is active, controls show busy/disable appropriately, and no duplicate message/group/account/file is created.
12. [ ] **AXY-012 — Low storage/interrupted process** — With a safe low-storage condition or forced process death, attempt media save/export/update and reopen. → **Expected:** Failure is explicit, partial files are not presented as complete, and the app recovers without data corruption.

### Completion and reporting

1. [ ] **END-001 — Untested-point audit** — Search this file/run record for unchecked points and `N/A`. → **Expected:** Every point is Pass, Fail, or justified `N/A`; every failure names its stable ID.
2. [ ] **END-002 — Cross-device consistency** — Compare Alice/Bob/Carol membership, latest messages, edits, deletions, reactions, notification settings, and disappearing state after final synchronization. → **Expected:** All devices converge or every divergence is reported with point ID and timing.
3. [ ] **END-003 — Cleanup** — Remove exported files, revoke temporary permissions, clear sensitive clipboard, delete synthetic contacts/media, and wipe disposable identities as authorized. → **Expected:** Test artifacts no longer appear in storage, notifications, Recents, clipboard, or account selector.
4. [ ] **END-004 — Release verdict** — Attach the completed checklist and summarize failures by severity and point ID against the recorded APK SHA. → **Expected:** The report states one unambiguous PASS or FAIL for that exact artifact and never transfers results to a different build.

## Report a failure

For each failed ID, record the exact APK SHA-256 and commit, device/Android/build variant, active account and conversation type, numbered reproduction actions, actual result, expected result, occurrence time, reproducibility, severity, and privacy-safe evidence. Never include an `nsec`, recovery secret, private message text, audit-log contents, precise personal location, or real contact.

## Maintainer coverage map

This checklist was derived from the current navigation, UI, resources, manifest declarations, tests, and repository documentation, including:

- `app/src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt`, `ui/navigation/`, `ui/onboarding/`, `ui/chats/`, `ui/conversation/`, `ui/group/`, `ui/profile/`, `ui/qr/`, `ui/search/`, `ui/settings/`, and `ui/share/`
- `app/src/main/java/dev/ipf/whitenoise/android/state/`, `notifications/`, `updates/`, and `MainActivity.kt`
- `app/src/main/AndroidManifest.xml` and `app/src/main/res/values/strings.xml` plus locale resources
- `app/src/test/`, `app/src/androidTest/`, and build-flavor tests under `app/src/testPlay/` and `app/src/testZapstore/`
- `README.md`, `docs/`, `.github/workflows/android-ci.yml`, and release/verification scripts

A feature is in scope when a release build can present it to a user, including loading, empty, disabled, permission-denied, offline, error, retry, destructive-confirmation, account-switch, background, and restored states. A visible Coming soon control is tested as a disabled/explicit state; a source-only feature with no release-build user path is not represented as working UI.

### Maintenance contract

- Any pull request that adds, removes, renames, or changes user-visible behavior or state must update the affected point(s) in this guide in the same pull request. This includes permissions, intents, navigation, settings, error/retry copy, build-flavor differences, background work, privacy behavior, and accessibility behavior.
- Keep existing IDs forever. Edit a point in place when its feature changes; append a new ID within the owning prefix for a new independently reportable behavior; mark removed behavior as retired in the pull request rather than reassigning its ID.
- Every checklist point must remain an ordered Markdown checklist item with exactly one stable `<AREA>-<NNN>` ID, an action instruction a novice can execute, and one observable `**Expected:**` result after the `→` separator.
- Before opening or updating a pull request, run `python3 scripts/check_manual_test_guide.py`, `python3 -m unittest scripts/test_check_manual_test_guide.py`, and the repository's required Hermes fast gate. Review the scope map whenever navigation, resources, manifest components/permissions, tests, or release documentation changes.

## Retired IDs

Never delete an old ID. Move it here when its user-visible behavior is removed.

| ID | Retired in | Reason | Superseded by |
|---|---|---|---|
