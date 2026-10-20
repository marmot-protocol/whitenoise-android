# Map dependency: containment and replacement boundary

Status: proposed for product/privacy review; implementation is not approved.
Tracks [#3258](https://github.com/marmot-protocol/whitenoise-android/issues/3258)
under the [post-release tracker](https://github.com/marmot-protocol/whitenoise-android/issues/3241).
Research checked on 2026-10-19 against Android
`b2d259b85f4b6f47837b91a30f69ec00700cf7a7`.

## Proposed decision

Retain the verified `osmdroid-android:6.1.20` dependency temporarily, keeping the
existing picker and received-location cards available. Evaluate MapLibre Native
Android as the maintained replacement candidate at the existing picker boundary.
Do not replace the picker with an external-app handoff: opening a map is useful
for viewing a received location, but does not supply this flow's coordinate-pick
and send/cancel interaction.

Containment is the smallest immediate compatibility step, not a claim that an
archived library is supported or free of vulnerabilities. No renderer, tile
provider, endpoint, permission, message format or dependency byte changes in this
proposal. Keep #3258 **Needs design**, omit `agent-ok`, and do not close it on
this document. The owning product/privacy decision and the qualification below
remain required.

## What the app actually owns today

| Boundary | Source evidence | Implication |
| --- | --- | --- |
| Picker | [LocationPickerScreen.kt](../../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/share/LocationPickerScreen.kt) is the only production source importing osmdroid. It creates a `MapView`, uses MAPNIK, sets the package-name User-Agent, allows panning/zooming and sends the final map center. | Keep any replacement local to this Android view and its callbacks; a new app-wide map service or module is unnecessary. |
| Permission and destination | [ConversationScreen.kt](../../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt) requests coarse/fine location before opening the picker, shows the existing denial message, and calls its conversation controller only after `onPick`. | Preserve explicit permission, account/chat ownership and user-confirmed sending. Permission denial currently does not open a manual-only picker. |
| One-shot location | [LocationShare.kt](../../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/share/LocationShare.kt) uses Android `LocationManager` and a cancellation signal; it formats the existing coordinate link. | Reuse the existing lookup and wire representation. A renderer migration does not require a new MDK capability or location-tracking feature. |
| Received preview | [LocationBubble.kt](../../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/share/LocationBubble.kt) renders a local coordinate card with no map requests. Its [production callback](../../app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/BubbleContentBlocks.kt) opens the HTTPS Maps link through Android `ACTION_VIEW` only on tap; a browser or another URL handler may receive it. | Preserve this preview, including surrounding prose. Do not turn receipt or display into remote tile fetching. |
| Lifecycle | The picker forwards resume/pause, removes its map/touch listeners, and calls `onDetach` on disposal. Its initial location lookup runs in the composition scope. | Characterize these transitions before substituting a different SDK; SDK lifetime and accepted send lifetime are separate. |
| Existing network switch | `useDataConnection` defaults to `true`. The production call does not override it. | This parameter is not evidence of a user-facing network-consent setting. Android location permission also does not establish consent for a new tile provider. |

The upstream [osmdroid repository](https://github.com/osmdroid/osmdroid) is archived
and says it will receive no further updates/releases. The retained version is
locked in [the version catalogue](../../gradle/libs.versions.toml) and
[app lockfile](../../app/gradle.lockfile), with exact supplier bytes in
[verification metadata](../../gradle/verification-metadata.xml) and
[the notice catalogue](../../config/licenses/catalog.json).

The local AAR matched the recorded SHA-256
`2cbec2a2a1a671a76849897748080079a3158a7a4c8554b86f2c75860f91046a`.
It is 861,704 archive bytes and contains no `jni/` or `.so` entries. This is an
archive inspection, not a measured installed-APK, heap, GPU or launch-cost delta.

## Options and tradeoffs

| Option | Interaction and network | Licence and runtime cost | Disposition |
| --- | --- | --- | --- |
| Contain osmdroid 6.1.20 | Preserves the current embedded picker, MAPNIK requests, coordinate cards and explicit external opening. Existing cache/network behavior still needs qualification. | Apache-2.0 attribution already recorded; no new SDK/native dependency. Archived upstream leaves maintenance/security risk with the app. | Recommended interim path, subject to owner review. It cannot establish completion of a maintained replacement. |
| MapLibre Native Android behind the picker | Can render raster tiles while preserving center selection. Use a bundled, reviewed raster-only style if the same provider is approved; do not copy a demo style URL, silently enable remote glyph/sprite sources, or start fetching for received cards. | BSD-2-Clause SDK plus its complete bundled/transitive notices. Adds a native renderer and backend/lifecycle obligations; exact APK/ABI/memory cost is unmeasured. | Maintained candidate for a bounded spike after the decision gate, not an adopted version or provider. |
| External map app only | A `geo:`/`ACTION_VIEW` handoff displays a location, depends on an installed handler and delegates requests to that app. The documented contract does not return an arbitrary selected coordinate. | No embedded map SDK if the picker is removed, but the external app's terms/data handling remain outside this app's control. | Retain for explicit viewing. Reject as the picker replacement because it removes existing selection/preview behavior without an equivalent result contract. |

MapLibre's [Android release](https://github.com/maplibre/maplibre-native/releases/tag/android-v13.7.1)
was published on 2026-10-15. Research uses its immutable source revision
`4cf121e515d8d065b57cd555a0b231ea27ff0e5d`, rather than treating a moving version
as approved. Its [raster-source specification](https://maplibre.org/maplibre-style-spec/sources/#raster)
supports an explicit tile URL/template and tile size. Its
[Android licence file](https://github.com/maplibre/maplibre-native/blob/4cf121e515d8d065b57cd555a0b231ea27ff0e5d/platform/android/LICENSE.md)
also contains dependencies' notices; the top-level BSD label alone is insufficient
for this app's notice inventory.

The pinned [artifact guide](https://github.com/maplibre/maplibre-native/blob/4cf121e515d8d065b57cd555a0b231ea27ff0e5d/platform/android/docs/artifacts.md)
distinguishes OpenGL, Vulkan and combined artifacts. Backend selection loads native
code and remains fixed for the process; requesting an unavailable backend can
throw. Do not assume the default artifact works on every supported device, or
select a larger dual backend without measuring it. The
[quickstart](https://github.com/maplibre/maplibre-native/blob/4cf121e515d8d065b57cd555a0b231ea27ff0e5d/platform/android/docs/getting-started.md)
forwards start/resume/pause/stop, destruction, saved state and low-memory signals.
The current osmdroid resume/pause wiring is not a complete MapLibre adapter.

The [documented Android map intent](https://developer.android.com/guide/components/google-maps-intents#display_a_map)
is a display/navigation contract. Treat the lack of a documented coordinate-result
contract as the reason this proposal rejects a generic external picker; it does
not claim that every individual map application lacks a proprietary picker API.

## Tile service and privacy decision

The pinned osmdroid [MAPNIK definition](https://github.com/osmdroid/osmdroid/blob/osmdroid-parent-6.1.20/osmdroid-android/src/main/java/org/osmdroid/tileprovider/tilesource/TileSourceFactory.java#L93-L101)
uses `https://tile.openstreetmap.org/`, with 256-pixel tiles and source-policy flags
against bulk/preventive fetching. The provider sees the requesting IP, application
User-Agent and tile coordinates. Tile requests around a current-location fix can
therefore reveal the viewed area before the user sends anything to a chat. A
different rendering SDK alone does not eliminate that exposure.

The [OSMF tile policy](https://operations.osmfoundation.org/policies/tiles/)
requires HTTPS, visible attribution, an identifying User-Agent and compliant
caching. It prohibits bulk/offline prefetch and offers no availability guarantee.
Normal cached revisits are distinct from an offline-download feature. A replacement
must verify actual traffic and caching, not infer compliance from the SDK name.

Before implementation, the product/privacy owner must settle:

1. Whether the current interactive tile service and its disclosure/consent are
   acceptable for the picker, including automatic centering after a permissioned
   one-shot lookup. No new provider or proxy is selected here.
2. The exact allowed endpoint set and when requests may start or must stop.
   An embedded style must not introduce unreviewed style, glyph, sprite, telemetry
   or fallback endpoints. SDK licensing does not grant tile-service rights.
3. Offline/error presentation and any approval needed to change it. Preserve
   readable local received cards and selected-coordinate/send/cancel affordances;
   do not promise uncached maps with networking disabled.

No runtime scan, network trace, CVE assessment, replacement build or device test
was performed for this document. Retaining the old bytes is not a security sign-off.

## Implementation boundary after approval

1. Characterize the actual picker and its conversation callback before extraction.
   A narrow Android adapter may receive a selected center, network permission and
   lifecycle, and emit user selections. Keep osmdroid/MapLibre types inside that
   implementation. Do not introduce a generic map framework, second coordinate
   store, geocoder, analytics or background/offline downloader.
2. Spike the approved MapLibre artifact with synthetic coordinates and controlled
   tiles. Measure signed release APK/native deltas per supported ABI, cold/warm
   picker startup, memory and renderer failure. Verify 16 KiB native alignment,
   minified startup, supported-device backend behavior, locks, supplier digests and
   complete notices through the existing hosted lanes/artifacts. Do not add a
   second compile/emulator matrix or silently change the MarmotKit pin.
3. Replace only the picker implementation if the measured candidate passes the
   contract. Preserve `SharedLocation`, formatting/parsing, received-card rendering,
   the existing location lookup and conversation-controller send owner. Remove
   osmdroid and its attribution only when the shipped graph and required historical
   notice contract permit it. Revert the adapter/dependency change together if the
   new renderer fails qualification; a provider switch needs separate approval.

Containment ends when the reviewed candidate is qualified. A confirmed security
defect, unavailable tile service or reproducible lifecycle failure requires its own
bounded repair/decision rather than indefinite acceptance of the archived SDK.

## Qualification to add before migration

These are proposed tests at the production boundary, not existing coverage claims.

| Scenario | Required observable result |
| --- | --- |
| Open, pan, zoom, recenter and Send | The final selected center reaches the originating controller once; no send occurs before confirmation. Preserve the existing manual selection after opening. |
| Coarse/fine grant, denial, revocation and no fix | Correct existing permission/denial behavior, no unauthorized lookup, no false real-place fallback and no old result applied after disposal. Network authorization is tested separately. |
| Attach/detach/resume, repeated opening and rotation | Forward each SDK lifecycle correctly, release listeners/native view resources, and prevent retired callbacks from changing the next instance. Characterize current selection restoration before promising a change. |
| Cancel, route/account change, app lock and a held lookup | No selection or send crosses the original owner; cancel/Back sends nothing. Do not retain decrypted UI underneath app lock. Coordinate any shared ownership work with #3354. |
| Network disabled, cold/empty cache, cached revisit, timeout and provider error | No disallowed requests; graceful controls and attribution, no claim of uncached offline maps, and no unsolicited retry after the picker closes. Use a controlled tile server, not a headless crawl of public OSM tiles. |
| Received location and prose-plus-link | Preserve the local preview and explicit URL handoff; zero tile/style requests from receipt/rendering. Characterize a missing URL handler; any new failure presentation needs the owning decision. |
| Light/Dark/AMOLED, RTL, 200% font, TalkBack and short/wide windows | Readable coordinates, Send, Close, locate and attribution; focused picker goldens plus the existing message-card golden remain tracked. |

Keep [ShareFormattingTest](../../app/src/test/java/dev/ipf/whitenoise/android/ui/ShareFormattingTest.kt)
and [LocationMessageBubbleScreenshotTest](../../app/src/test/java/dev/ipf/whitenoise/android/ui/conversation/messages/LocationMessageBubbleScreenshotTest.kt).
They cover coordinate formatting/parsing and the received-card route, not map
lifecycle or provider behavior. Add a picker lifecycle/callback integration suite
and a controlled-network device case rather than a helper-only adapter test.
Extend existing [MED-012](../manual-release-testing/cases/MED-012.md) and
[MED-001](../manual-release-testing/cases/MED-001.md) with those outcomes in the
implementation PR; keep their IDs and unchecked state. This prose-only proposal
does not change the current manual contract or screenshot baselines.

Before any human preview handoff, use the exact signed candidate for the two
separate populated-upgrade and full-stop/cold-restart checks required by
[CI request policy](../ci-request-policy.md). Record source/APK digest, current
master's complete MarmotKit lock, device and retained account/history, then run
the location matrix. Source review, a documentation CI pass or a smaller dependency
count cannot replace that qualification or close #3258.
