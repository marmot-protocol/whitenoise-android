# Sonar-Compatible Stickers

## Clarified Problem Statement

**Goal:** Add the complete Sonar sticker lifecycle to White Noise Android: discover packs, open a shared pack link, import a Signal pack, publish its public assets and metadata, install or uninstall packs, pick and send stickers, and safely render stickers received from Sonar or White Noise.

**Constraints:**

- Use the upstream `sonar-stickers` Rust crate as the canonical model, validation, and Nostr tag implementation rather than reimplementing the protocol in Kotlin.
- Preserve exact Sonar interoperability: kind `30031` pack events, kind `10031` installed-pack lists, and encrypted kind-9 chat rumors carrying `["sticker", "<pack-coordinate>", "<shortcode>", "<plaintext-sha256>"]`.
- Make White Noise's native SQLite databases the source of truth for validated pack metadata, installed-pack state, message sticker references, and relay synchronization. Do not add Room, DataStore, SharedPreferences, files, or a singleton Kotlin map as a second protocol-data store.
- Public sticker bytes use HTTPS Blossom storage. Validate pack format, MIME allowlist, dimensions, URL/hash relationship, and downloaded plaintext SHA-256 before rendering.
- Treat downloaded image bytes as disposable, content-addressed media. If cached, use a bounded cache whose authorization comes from SQLite-backed pack metadata and which is invalidated on account wipe; never treat the byte cache as installed-pack authority.
- Keep Signal `pack_key` input inside the native import operation. It must not appear in Nostr events, Blossom URLs, logs, analytics, drafts, or persisted Android state.
- Run relay, Blossom, hashing, decoding, and binding work off the main thread. Keep subscriptions and in-flight imports lifecycle-bound and cancellable.
- Preserve historical message meaning when an addressable pack changes: resolve only an exact coordinate + shortcode + plaintext hash match; otherwise render a missing or untrusted sticker state.
- The primary install entry point is a Sonar web/share link or pasted pack coordinate. Discovery may offer additional packs, but installation still publishes the same kind `10031` list.

**Non-goals:**

- Encrypting ordinary sticker assets with MIP-04; that would no longer be the Sonar sticker protocol.
- Building a general-purpose pack editor from arbitrary local images in the first release. Publishing is initially the Signal-import flow defined by Sonar.
- Modifying the Sonar clients or protocol format.
- Adding a second Android Nostr client, signer, database, or durable sticker registry beside White Noise.
- Implementing equivalent UI in a separate iOS repository as part of this Android task, although the native protocol APIs should remain platform-neutral.

**Success criteria:**

- A sticker sent by Sonar renders in White Noise, and a sticker sent by White Noise renders in Sonar with the same pack, shortcode, and hash.
- White Noise exposes typed native APIs and projections for pack discovery/fetch, installed-pack listing, install/uninstall, Signal import/publish, sticker send, message sticker references, and verified asset resolution.
- Opening or pasting a Sonar pack link previews a strictly validated pack and allows installation; the installed list is synchronized through the user's signed kind `10031` event and survives process restart without Android-owned protocol storage.
- The composer shows installed packs and sends a sticker with optimistic/pending/failed/delivered behavior consistent with text and media sends.
- Timeline, reply preview, chat-list preview, search/export, notifications, deletion, invalidation, and message actions have explicit sticker behavior rather than falling back to a blank generic message.
- Signal import decrypts and authenticates the manifest/assets locally, uploads verified plaintext assets to the configured Blossom server, publishes a valid kind `30031` event, and never leaks `pack_key` material.
- Discovery and pack-link input reject malformed coordinates, signatures, non-HTTPS URLs, unsupported MIME types, invalid dimensions, duplicate codes/hashes, oversized responses, and hash mismatches.
- Editing a pack cannot change an old message: a reference whose hash is no longer authorized renders the defined unavailable/untrusted state.
- Account sign-out or destructive wipe removes native sticker state and invalidates any derived image cache.
- Native tests cover models, persistence/projections, relay replacement semantics, Signal crypto/import, publishing, send/receive interoperability, and wipe behavior; Android tests cover picker state, deep links, optimistic send reconciliation, rendering states, accessibility, and previews.

## Approaches Considered

### Approach A: First-Class White Noise Protocol Feature

- **Sketch:** Add `sonar-stickers` to the upstream White Noise/Marmot native workspace, then implement sticker event ingestion, SQLite tables/projections, relay synchronization, publishing, asset verification, and typed UniFFI APIs there. Android consumes those projections and concentrates on UI state, image decoding, and user interaction.
- **Affected modules:** upstream `marmot-protocol/mdk` runtime/storage/UniFFI modules; `app/src/main/marmotkit/MARMOT_VERSION`; generated `app/src/main/java/dev/ipf/marmotkit/marmot_uniffi.kt`; `state/AppState.kt`; `state/Controllers.kt`; a new `core/Stickers.kt`; `core/MessageProjector.kt`; `core/TimelineProjector.kt`; new `ui/stickers/` screens; new `ui/conversation/composer/StickerPicker.kt`; `ui/conversation/messages/MessageBubble.kt`; `ui/navigation/AppDestinations.kt`; `ui/navigation/MainShell.kt`.
- **Tradeoffs:** Best ownership, wipe semantics, offline projections, and future cross-platform reuse. It requires coordinated upstream native work and a binding update before the Android feature can be complete.
- **Effort:** L.

### Approach B: Staged Hybrid, Then Move Authority Native

- **Sketch:** Ship receive/render first by parsing the already-exposed raw `sticker` message tag in Kotlin and resolving assets transiently. Add the native SQLite-backed pack/install/publish/send APIs in later slices, then replace the temporary Kotlin parsing path with typed projections.
- **Affected modules:** initially `core/MessageProjector.kt`, a new `core/Stickers.kt`, `ui/conversation/messages/MessageBubble.kt`, and a new sticker renderer; later all upstream and UI modules listed in Approach A.
- **Tradeoffs:** Delivers visible Sonar interop earlier and lets rendering UX mature while native APIs are built. It creates temporary duplicate parsing and two message representations, increases migration/testing work, and makes the early release receive-only despite the requested full scope.
- **Effort:** M for the first slice, L overall.

### Approach C: Embed the Sonar Core as a Sidecar

- **Sketch:** Reuse Sonar's existing `send_sticker`, `fetch_sticker_pack`, installed-list, Signal import, and cache APIs by packaging Sonar FFI beside Marmot, with Kotlin coordinating identities and conversations between the two native cores.
- **Affected modules:** Gradle/native packaging, `WhiteNoiseApplication.kt`, `state/AppState.kt`, a new Sonar adapter layer, account lifecycle/wipe code, and all sticker UI modules.
- **Tradeoffs:** Maximizes direct code reuse and may reach feature parity fastest initially. It duplicates relay clients, signer/session ownership, persistent state, caches, lifecycle, and failure handling; it conflicts with White Noise SQLite being the sole protocol source of truth and is therefore not suitable for production.
- **Effort:** L, with high ongoing maintenance risk.

## Recommendation

Choose **Approach A: First-Class White Noise Protocol Feature**, delivered internally as vertical slices: native contract and persistence; receive/render; install/link flow; picker/send; discovery; then Signal import/publish. This uses the selected SDK at the correct Rust boundary, produces true Sonar interoperability, and keeps Android thin without introducing a competing protocol store.

Approach B is reasonable only if a receive-only public milestone is valuable enough to justify temporary code. Approach C should be rejected because its duplicated native ownership directly conflicts with this repository's source-of-truth rule.

## Open Questions

- Which Blossom server(s) should White Noise use for Signal-import publishing, and should users be able to configure them or only use an environment-provided default?
- Which relay set powers discovery: the account's configured relays, a Sonar-recommended set, or both with visible provenance?
- Should discovery be an unrestricted recent-pack feed, a curated catalog, or search-only to limit abuse and unsafe content exposure?
- Should the composer combine emoji and stickers as two tabs in the existing emoji pane, or give stickers a separate composer button?
- What exact share/deep-link forms must Android claim: the Sonar website URL, `nostr:naddr`, raw `30031:...` coordinates, Signal links, or all four?
- Should animated WebP/APNG/GIF autoplay in the timeline, and what reduced-motion/data-saver behavior is required?

## Grounding Sources

- Sonar protocol: <https://github.com/hedwig-corp/bitchat-to-sonar/blob/main/docs/SONAR-STICKERS.md>
- Canonical Rust crate: <https://github.com/hedwig-corp/bitchat-to-sonar/tree/main/core/sonar-stickers>
- Existing Sonar FFI behavior used as an API reference: <https://github.com/hedwig-corp/bitchat-to-sonar/blob/main/core/sonar-ffi/src/lib.rs>
- Existing White Noise binding pin: `app/src/main/marmotkit/MARMOT_VERSION`
