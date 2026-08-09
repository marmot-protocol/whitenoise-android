# Issue #1866: local pre-send photo editor research and design

[GitHub issue #1866](https://github.com/marmot-protocol/whitenoise-android/issues/1866)

- Status: Phase 1 approved; Phase 2 implementation in progress
- White Noise baseline: 5b555b3a6ccddaaceb414d307faad80e4c76e20d (master)
- MDK binding baseline: 64f13c4cb19e62f4be11cf6cf986ee9bfdb4d1e1
- Research completed: 2026-08-09

## Executive decision

Build the editor in the existing Android app and MediaPipeline. Do not adopt a third-party editor as the rendering core.

The recommended design is a Compose editor over a small, immutable edit recipe. It uses one shared coordinate transform for preview and export, a sampled preview bitmap for interaction, and one final render from the best safely retained source. Save prepares a complete MDK draft attachment and then replaces the intended attachment under a per-conversation draft coordinator. Cancel never mutates the draft.

This research found three important mismatches between the issue's assumptions and current master:

1. Selected images are staged as content URIs in Compose state and are converted to upload bytes only when Send is pressed. They are not currently MDK-backed draft attachments.
2. Android text drafts use the app-specific DraftStore. The generated MDK draft APIs exist, but White Noise Android does not call them.
3. The current quality model has Low, Standard, High, and Original profiles. There is no two-value Standard/HD contract in the preview UI. High is the existing 4096 px / JPEG 92 profile that most naturally maps to the requested HD label.

These are architectural prerequisites, not minor editor details. Phase 2 should first establish one MDK-backed draft gateway and stable attachment identities, then add the editor. Bolting an editor onto the URI list would leave stale-save, process recovery, and atomic replacement requirements unsolved.

Recommended product decisions:

- Present the current four profiles where quality can be changed, labeling High as “High (HD)”. Standard and High (HD) are the required v1 paths; Low and Original remain visible and deterministic rather than being silently remapped.
- Treat an edited Original image as a newly rendered image, not original bytes. Apply an explicit edited-output safety ceiling and show the effective dimensions.
- Keep “send as file” separate. An image selected as a document remains raw and non-editable unless the user explicitly chooses “Convert to photo and edit.”
- Disable editing for animations in v1. Offer raw image/file send; never silently flatten a first frame.
- Encrypt retained editable sources at rest with an Android Keystore-protected key. App-private/no-backup placement alone is not a sufficient privacy boundary for plaintext draft media.
- Use the existing Android graphics stack plus focused app-owned code. The surveyed dependencies do not satisfy the combined privacy, single-render, draft-atomicity, and coordinate requirements.

This document was completed before production implementation began. The approved Phase 2 work now lives on the `issue/1866-photo-editor-research` branch.

## Evidence language and method

- **Verified** means the behavior was traced in source at the pinned commit.
- **Inference** means the source establishes the mechanism but not the product intent.
- **Proposal** means the recommended White Noise design.

The audit used current White Noise master and the exact MDK revision recorded by the app. External repositories were read at pinned commits. Source links below target those revisions. No external implementation code is proposed for copying.

## Current White Noise pipeline

### End-to-end audit

| Stage | Verified current behavior | Consequence for #1866 |
|---|---|---|
| Gallery selection | [ConversationScreen.kt](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt#L1100-L1200) uses the system Photo Picker for up to 10 items and stores returned URIs in rememberSaveable state. | There is no stable draft attachment id or revision to edit. A restored URI may no longer be readable. |
| Camera | The same screen creates a private cache file under cacheDir/camera, exposes it through FileProvider, and keeps the URI/file across activity recreation. A cancelled capture deletes the file. | Camera ownership is app-local and suitable for a source lease, but its lifetime must be tied to the draft attachment rather than only the screen. |
| Paste and inbound share | [MediaIo.kt](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaIo.kt) immediately copies pasted content into private composer_paste storage because provider grants can be transient. Inbound shares merge staged URIs into the same screen lists. | The paste copy is the right lifetime pattern, but ownership and cleanup need a stable attachment lease. |
| Documents | OpenMultipleDocuments stages document URIs. [documentPickTreatAsImage](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaIo.kt#L593) returns true for image MIME types selected through Files. | A “send as file” image is currently routed through image compression. This violates the requested raw-file semantics and must be corrected independently of the editor. |
| URI grants | No app call to takePersistableUriPermission was found. Photo Picker, GET_CONTENT, share, and paste grants are treated as session capabilities. The source comments already acknowledge restored “ghost” URIs. | The editor cannot promise reopen, quality changes, or process recovery from a URI alone. |
| Preview | [MediaPreview.kt](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaPreview.kt#L227-L400) shows the selected album, deletion, add-more, caption, and Send. It samples approximately 1280 px for the main image and 256 px for strips and applies EXIF orientation. | This is the correct place for an Edit affordance and effective-quality display. Its sampled decoder should be unified with the editor source/preview layer. |
| Quality | [MediaQuality.kt](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/state/MediaQuality.kt#L27-L73) defines Low 1024/70, Standard 2048/85, High 4096/92, and Original lossless-when-possible. Standard is the default. | The issue's Standard/HD wording does not match current master. The editor must snapshot and display the actual profile, with High labeled as HD. |
| Send-time preparation | [ConversationMediaSender.kt](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationMediaSender.kt#L42-L115) reads the live global quality setting when Send is pressed. Standard profiles call MediaPipeline; Original and animations attempt a metadata-stripped byte-preserving path. | Quality is not an attachment property today. Reopening an edit cannot preserve an attachment-specific choice until the draft model records it. |
| Decode and orientation | [MediaPipeline.readDownscaledJpeg](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/media/MediaPipeline.kt#L762-L837) performs bounds decode, sample selection, exact scale, all EXIF orientation transforms, and catches I/O, permission, decoder, and OOM failures. | Reuse its validated sniffing, limits, orientation, and finalization concepts. Do not invoke the current URI-to-JPEG entry point after the editor has already rendered. |
| Metadata and Original | [readOriginalImageForUpload](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/media/MediaPipeline.kt#L698-L760) strips JPEG APP1/APP13/comments, PNG textual/eXIf/time chunks, and WebP EXIF/XMP. GIF is passed through. Sources requiring orientation pixels fall back to JPEG. | Final edited outputs must use the same or stricter privacy invariant. An edited image cannot preserve original encoded bytes. |
| Alpha | [finishDownscaledJpeg](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/media/MediaPipeline.kt#L839-L880) composites alpha over white before thumbhash and JPEG encoding. | The editor must preview the same effective background and explicitly define when alpha is retained or flattened. |
| Thumbhash | MediaPipeline derives thumbhash from the same final pixels used for the outgoing image. | Keep this ordering; never calculate a placeholder from a differently cropped or differently flattened preview. |
| Pending send | [PendingAttachment and RetainedMediaUpload](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt#L2732-L2785) retain plaintext ByteArrays in memory. A 32 MiB process LRU supports retry. | This is upload retry state, not durable draft state. Process death loses it and a failed media send may require reattachment. |
| Encrypt/upload/send | [ConversationController](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt#L6760-L7010) uploads the prepared plaintext through MDK, caches returned media references for publish retry, and calls sendMediaAttachments. | The editor should hand MDK a complete final draft artifact; protocol, event, encryption, and upload APIs need no change. |
| Cleanup | MediaIo sweeps stale shared, voice, video, and paste caches and clears composer temporaries on screen disposal. Camera cleanup follows separate paths. | Cleanup is directory- and screen-oriented, not revision/reference-oriented. An editor source may be deleted too early or orphaned without a lease registry. |
| Text drafts | [DraftStore.kt](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/whitenoise/android/state/DraftStore.kt#L1-L170) persists text and selection through an Android-specific encrypted persistence layer. | Adding attachments here would duplicate protocol draft state. It should become a Compose-facing facade over the MDK draft gateway after migration. |
| MDK drafts | The generated binding exposes [messageDraft, messageDrafts, saveMessageDraft, and deleteMessageDraft](https://github.com/marmot-protocol/whitenoise-android/blob/5b555b3a6ccddaaceb414d307faad80e4c76e20d/app/src/main/java/dev/ipf/marmotkit/marmot_uniffi.kt#L5351-L5585), plus MessageDraftAttachmentFfi containing id, filename, media type, plaintext bytes, dimensions, thumbhash, duration, and waveform. Android has no callers. | This is the required final source of truth. Stable attachment ids and whole-draft replacement can be implemented without an MDK API change. |
| MDK atomicity | At the pinned MDK revision, [draft save](https://github.com/marmot-protocol/mdk/blob/64f13c4cb19e62f4be11cf6cf986ee9bfdb4d1e1/crates/storage-sqlite/src/message_drafts.rs) runs in an immediate SQLite transaction, upserts the draft, synchronizes attachments, preserves identical rows, and removes absent rows. [The app-facing model](https://github.com/marmot-protocol/mdk/blob/64f13c4cb19e62f4be11cf6cf986ee9bfdb4d1e1/crates/marmot-app/src/drafts.rs) keeps plaintext attachment data inside encrypted SQLCipher storage and omits blobs from summaries. | A single MDK save is durable and all-or-nothing, but it has no compare-and-swap revision parameter. Android must serialize all its draft writers and verify the target attachment immediately before saving. |

### Current failure modes relevant to editing

- A content URI can survive in saved Compose state after its permission has expired.
- An image chosen through the document picker is silently transformed as a photo.
- A global quality change between preview and Send changes output without an attachment-local visible state.
- Source bytes, transformed bytes, and draft identity are not tied together.
- No durable attachment exists for an editor to replace atomically.
- Current retry bytes are plaintext in process memory and disappear on process death.
- Original quality has an effectively unbounded edge parameter. That is acceptable only for losslessly stripped bytes; it is not a safe final-render allocation policy.
- Existing sampling protects common large inputs, but there is no single declared source edge, pixel-count, aspect-ratio, render-time, or drawing-complexity contract.

## External source research

### Source snapshot

| Project | Pinned revision | License observed | Reuse posture |
|---|---|---|---|
| Telegram Android | 45ab8f4308496e1f01026a97fcdb0d58a5274474 | GPL-2.0 | Study behavior only; do not copy into this codebase. |
| Signal Android | 9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67 | AGPL-3.0 | Study architecture only. |
| Signal iOS | 58cc49ec14da01e7afa89d6e603ba1ca79bcf9b4 | AGPL-3.0 | Study architecture only. |
| uCrop | f788b534b48c144edf786c8cddbf0e029e637804 | Apache-2.0 stated by project | License-compatible in principle, but not recommended as the editor core. |
| PhotoEditor | 8f571947ac0473d8ed41be51a18ab92da975430e | MIT | License-compatible, but rendering and state models do not meet requirements. |
| CanHub Android Image Cropper | e10601a1a8386efc7b36de1311259274c1a89956 | Apache-2.0 | License-compatible in principle; use as a behavioral/test reference. |
| TOCropViewController | 9782ecf30b5d16dfc95c89c939180adf457a2e82 | MIT | iOS UX reference only. |
| Fossify Gallery | 1933e40ac69787104b3b91343b643666a49fd601 | GPL-3.0 | Study behavior only. |
| SimpleX Chat | f921bd47bbf7701c5bd91eed0c3b4ab30c6f2a0c | AGPL-3.0 | Study messaging semantics only. |

“Actively maintained” was evaluated from recent commits at research time, not from download counts or marketing claims.

### Competitive matrix

| Implementation | UX and tools | State and coordinates | Rendering and quality | Memory/lifecycle | Privacy, accessibility, and dependency notes |
|---|---|---|---|---|---|
| Telegram Android | Integrated crop, 90° rotation, paint, eraser, and reset. Per-item High Quality state and a separate document-send path. | MediaEditState holds paths, crop state, paint/filter flags, and quality. Crop uses matrices; paint uses a GL painting model. Undo was found; redo was not. | Rebuilds from a retained source/filter path when High Quality changes. Creates temporary edited images and JPEG outputs, so the architecture is not a strict one-encode model. | Explicit temp paths are deleted. Paint undo has no clear bound in the inspected store. | Strong product integration and source re-render behavior. GPL prevents copying. Accessibility details are scattered rather than encoded as a reusable editor contract. |
| Signal Android | Approval screen offers Standard/High. Editor supports crop, rotate, drawing/highlighter/blur, undo/redo, and tool-session cancel. No eraser was found in the inspected editor. | EditorModel is a tree with separate crop/non-crop history. Histories are capped at 50. ElementStack persists only 10 entries to avoid Binder limits. | Preview and final use the same model renderer on a worker. The media transform writes edited JPEG at quality 80 and later quality transforms can run, creating a possible second lossy generation. | Bounded live history and smaller persisted state are exemplary. Parcelable model enables recreation. | AGPL prevents copying. The bounded history and tool-session snapshot behavior should be adapted conceptually. |
| Signal iOS | Attachment approval integrates crop, rotation, pen/highlighter/blur, undo/redo, reset, and Standard/High. No eraser was found. | Immutable ImageEditorContents snapshots. ImageEditorTransform documents unit, canvas, and view spaces. Stroke samples and widths are normalized. | Preview/final share layer construction. Dirty edits render to a lossless normalized image; final quality is applied afterward. Transparency chooses PNG. Output render currently has a main-thread assertion/TODO in inspected code. | Operation history was not visibly bounded in the inspected model. Temporary render paths are deleted during model cleanup. | Best coordinate documentation and lossless-intermediate privacy behavior. AGPL prevents copying. |
| uCrop | Polished crop screen, free crop, common presets, reset, and 90° rotation. No drawing/history. | Mature matrix-based crop calculations and EXIF handling. | Bounds decode, sampled decode, transform, then native crop/output. It is a separate output pipeline. | Retries with larger samples after OOM and exposes max bitmap size. | Copies original EXIF attributes into output, which is the wrong default for a private messenger. Activity/View/JNI integration would duplicate White Noise's pipeline. |
| PhotoEditor | Drawing, color/width, eraser, undo/redo, text/stickers/effects. No integrated crop. | View-coordinate paths and unbounded stacks. | Saves by drawing the current editor View to a bitmap, so export resolution is coupled to UI resolution. | Long paths and history can grow without the required caps. | MIT is compatible, but the architecture conflicts with source-resolution export and hostile-input requirements. |
| CanHub cropper | Robust free/preset crop, rotation, flip, activity and view APIs. No drawing/history. | Crop points plus rotation/flip are persisted; extensive crop math and tests. | Bounds/sample/region decode, OOM sample escalation, and a standalone output encoder. | Handles texture constraints, URI restore, temp outputs, and OOM better than most crop libraries. | Apache-2.0 is compatible. Adopting it would create a second transform/encode path and still leave drawing, drafts, and privacy to the app. |
| TOCropViewController | Compact iOS crop UX, broad portrait/landscape presets, reset, cancel, and 90° rotation. | Returns crop frame and angle against the source image. | One final UIKit crop/rotate operation. | Appropriate to an iOS controller, not Android process recovery. | MIT. Useful for preset and reset interaction design; no drawing, quality, or draft model. |
| Fossify Gallery | Rotate, crop presets, drawing color/width, undo, save. No redo or eraser found. Uses CanHub for crop. | Drawing paths are stored in view coordinates in an unbounded map. | Saves at JPEG 90 and uses a white background for drawing output. | Gallery-oriented activity lifecycle; not an atomic message draft workflow. | Copies broad EXIF metadata, including potentially sensitive fields, to edited output. GPL prevents copying. |
| SimpleX Chat | Explicit Camera, Gallery Image, Gallery Video, and File choices make image/file semantics clear. No comparable pre-send editor found. | Stages picked files and distinguishes media from raw file paths. | Images are repeatedly rescaled/re-encoded until a small byte target; raw files follow a separate bounded path. | Source image decoding can allocate a full bitmap before resizing. Private/encrypted staging is a useful messaging pattern. | AGPL prevents copying. Adopt the explicit image-versus-file UX, not the full-decode or repeated-encode pipeline. |

### Mandatory Telegram/Signal interaction summary

| Question | Telegram Android | Signal Android | Signal iOS |
|---|---|---|---|
| Standard versus HD | **Verified:** per-media highQuality state. rebuildPhoto selects the platform photo-size ceiling for standard/high and encodes JPEG at 87/99. | **Verified:** approval UI exposes Standard/High. Editor render and media-quality transforms are separate. | **Verified:** attachment approval exposes Standard/High and captures the chosen quality at proceed time. |
| Crop/rotate/draw/effects | **Verified:** crop presets, quarter rotation, paint/eraser, filters, and more; Undo found, no Redo in the inspected store. | **Verified:** crop, quarter rotation, draw/highlighter/blur, text/stickers, bounded undo/redo; no eraser found. | **Verified:** crop, rotation, pen/highlighter/blur and other tools, immutable undo/redo; no eraser found. |
| Preview versus export | **Verified:** editing is displayed through PhotoViewer's screen-sized surfaces, while rebuildPhoto reloads retained source/filter/paint paths at the selected send size. No single numeric preview ceiling was isolated in this large viewer. | **Verified:** preview and export share EditorModel. Export calls the model renderer with an optional output size on a worker. | **Verified:** the canvas renders view-sized layers for interaction; renderForOutput uses transform.outputSizePixels derived from source/crop geometry. |
| Quality changed after editing | **Verified:** rebuildPhoto reloads the retained source/filter path, reapplies crop/orientation/paint, and replaces the temporary output for the new quality. | **Inference from transform composition:** the recipe survives, but an edited JPEG can be fed to a later quality transform. | **Verified:** dirty editor state first becomes a lossless NormalizedImage; the selected quality is applied later, so the recipe is not rewritten per quality change. |
| Image versus file | **Verified:** forceDocument is a separate send branch. | **Verified at the media-send boundary:** document and image models are separate; no “convert edited photo to file” control was found in the inspected image editor. | **Verified at attachment approval:** document and image preparation are separate; no edited-image-as-file conversion control was found in the inspected editor. |
| Generational encoding | **Verified:** quality rebuild begins from retained paths, but crop/paint workflows also create intermediate JPEG/temp images; it is not a strict one-lossy-encode architecture. | **Verified:** ImageEditorModelRenderMediaTransform writes JPEG 80. **Inference:** composing subsequent quality work can add generation loss. | **Verified:** the edited intermediate is lossless PNG when normalized, followed by final quality encoding; this best preserves the one-lossy-generation property, although it uses an intermediate artifact. |

### Telegram Android: exact findings

- Editor/navigation and quality live primarily in [PhotoViewer.java](https://github.com/DrKLO/Telegram/blob/45ab8f4308496e1f01026a97fcdb0d58a5274474/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java). The approval UI exposes paint and a 48 dp quality control, and stores a per-entry high-quality choice.
- [MediaController.MediaEditState](https://github.com/DrKLO/Telegram/blob/45ab8f4308496e1f01026a97fcdb0d58a5274474/TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java#L477-L840) owns original/filter/paint paths, crop state, edit flags, and nullable high-quality state. rebuildPhoto reloads an earlier source and reapplies edits for high quality.
- [CropView.java](https://github.com/DrKLO/Telegram/blob/45ab8f4308496e1f01026a97fcdb0d58a5274474/TMessagesProj/src/main/java/org/telegram/ui/Components/Crop/CropView.java) contains the crop transform, reset behavior, and presets including Original, Square, 3:2, 5:3, 4:3, 5:4, 7:5, and 16:9.
- [UndoStore.java](https://github.com/DrKLO/Telegram/blob/45ab8f4308496e1f01026a97fcdb0d58a5274474/TMessagesProj/src/main/java/org/telegram/ui/Components/Paint/UndoStore.java) provides undo actions. [Brush.java](https://github.com/DrKLO/Telegram/blob/45ab8f4308496e1f01026a97fcdb0d58a5274474/TMessagesProj/src/main/java/org/telegram/ui/Components/Paint/Brush.java) includes an eraser; the painting implementation uses GL-backed slices.
- [SendMessagesHelper.java](https://github.com/DrKLO/Telegram/blob/45ab8f4308496e1f01026a97fcdb0d58a5274474/TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java#L10620-L10645) triggers a high-quality rebuild and has a separate force-document branch.

- **Verified lesson:** changing quality can rebuild from retained edit sources rather than a compressed result.
- **Inference:** Telegram accepts more temporary files and intermediate encodes than the stricter one-final-render requirement allows.
- **Proposal adopted:** per-attachment visible quality, source-based re-render, and explicit file-send separation.
- **Proposal rejected:** unbounded undo and path-oriented intermediate JPEGs.

### Signal Android: exact findings

- [QualitySelectorSheetContent.kt](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/feature/media-send/src/main/java/org/signal/mediasend/screens/edit/QualitySelectorSheetContent.kt) offers Standard and High in the media approval experience. [SentMediaQuality.kt](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/feature/media-send/src/main/java/org/signal/mediasend/SentMediaQuality.kt) defines the choice.
- [EditorModel.java](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/lib/image-editor/src/main/java/org/signal/imageeditor/core/model/EditorModel.java) owns crop/non-crop histories, caps both at 50, builds output geometry, rotates by quarter turn, and renders on a worker.
- [ElementStack.java](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/lib/image-editor/src/main/java/org/signal/imageeditor/core/model/ElementStack.java) keeps a bounded live stack and persists only a smaller subset for process recreation.
- [ImageController.kt](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/feature/media-send/src/main/java/org/signal/mediasend/screens/edit/ImageController.kt) snapshots a tool session and restores it on cancel/discard.
- [ImageEditorModelRenderMediaTransform.java](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/app/src/main/java/org/thoughtcrime/securesms/mediasend/ImageEditorModelRenderMediaTransform.java) renders edited media to JPEG. [MediaSelectionRepository.kt](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/app/src/main/java/org/thoughtcrime/securesms/mediasend/v2/MediaSelectionRepository.kt#L252-L280) then composes the edit transform with the High quality transform when both apply.

- **Verified lesson:** bounded histories, small persisted histories, and tool-level cancel are mature lifecycle choices.
- **Inference:** composing an editor JPEG transform with a later quality transform can introduce a second lossy generation.
- **Proposal adopted:** 50-state undo/redo ceiling and bounded recreation metadata.
- **Proposal rejected:** a lossy intermediate before final quality application.

### Signal iOS: exact findings

- [ImageEditorModel.swift](https://github.com/signalapp/Signal-iOS/blob/58cc49ec14da01e7afa89d6e603ba1ca79bcf9b4/SignalUI/ImageEditor/ImageEditorModel.swift) records immutable content snapshots for undo/redo and owns temporary render paths.
- [ImageEditorTransform.swift](https://github.com/signalapp/Signal-iOS/blob/58cc49ec14da01e7afa89d6e603ba1ca79bcf9b4/SignalUI/ImageEditor/ImageEditorTransform.swift) explicitly separates unit, canvas, and view coordinate systems and combines normalized translation, scale, rotation, and flip.
- [ImageEditorStrokeItem.swift](https://github.com/signalapp/Signal-iOS/blob/58cc49ec14da01e7afa89d6e603ba1ca79bcf9b4/SignalUI/ImageEditor/ImageEditorStrokeItem.swift) stores unit-space samples and width. Gestures inverse-map from view space into image unit space.
- [ImageEditorCanvasView.swift](https://github.com/signalapp/Signal-iOS/blob/58cc49ec14da01e7afa89d6e603ba1ca79bcf9b4/SignalUI/ImageEditor/ImageEditorCanvasView.swift) shares layer construction between preview and final render.
- [NormalizedImage.swift](https://github.com/signalapp/Signal-iOS/blob/58cc49ec14da01e7afa89d6e603ba1ca79bcf9b4/SignalUI/Attachments/NormalizedImage.swift) uses a normalized, lossless image representation before final quality work, preserves transparency with PNG, and applies tiered dimension/size constraints.

- **Verified lesson:** normalized source coordinates make preview and final output independent of screen and export resolution.
- **Inference:** the immutable whole-content history can grow materially because no explicit cap was found in the inspected model.
- **Proposal adopted:** explicit coordinate spaces, coalesced in-progress strokes, and shared preview/export transforms.
- **Proposal rejected:** unbounded immutable history and main-thread final rendering.

### Additional source details

#### uCrop

[UCropActivity.java](https://github.com/Yalantis/uCrop/blob/f788b534b48c144edf786c8cddbf0e029e637804/ucrop/src/main/java/com/yalantis/ucrop/UCropActivity.java) defines familiar presets and rotate/reset interactions. [BitmapLoadTask.java](https://github.com/Yalantis/uCrop/blob/f788b534b48c144edf786c8cddbf0e029e637804/ucrop/src/main/java/com/yalantis/ucrop/task/BitmapLoadTask.java) bounds-decodes, samples, retries after OOM, and applies EXIF orientation. [BitmapCropTask.java](https://github.com/Yalantis/uCrop/blob/f788b534b48c144edf786c8cddbf0e029e637804/ucrop/src/main/java/com/yalantis/ucrop/task/BitmapCropTask.java) writes a standalone cropped output and copies source EXIF attributes.

Use its preset vocabulary and adversarial crop tests as references. Do not use its output path because it would duplicate MediaPipeline and copy metadata that White Noise intentionally removes.

#### PhotoEditor

[DrawingView.kt](https://github.com/burhanrashid52/PhotoEditor/blob/8f571947ac0473d8ed41be51a18ab92da975430e/photoeditor/src/main/java/ja/burhanrashid52/photoeditor/DrawingView.kt) implements pen, PorterDuff clear eraser, and undo/redo using unbounded stacks. [PhotoSaverTask.kt](https://github.com/burhanrashid52/PhotoEditor/blob/8f571947ac0473d8ed41be51a18ab92da975430e/photoeditor/src/main/java/ja/burhanrashid52/photoeditor/PhotoSaverTask.kt) captures the editor View at its on-screen dimensions before compression.

The eraser interaction is useful evidence, but view-sized export and unbounded state are disqualifying for the core.

#### CanHub Android Image Cropper

[BitmapUtils.kt](https://github.com/CanHub/Android-Image-Cropper/blob/e10601a1a8386efc7b36de1311259274c1a89956/cropper/src/main/kotlin/com/canhub/cropper/BitmapUtils.kt) handles EXIF variants, bounds sampling, region decode, texture constraints, and OOM sampling escalation. [CropImageView.kt](https://github.com/CanHub/Android-Image-Cropper/blob/e10601a1a8386efc7b36de1311259274c1a89956/cropper/src/main/kotlin/com/canhub/cropper/CropImageView.kt) persists crop points, rotation, flip, and source URI.

It is the strongest Android crop-only dependency inspected, but integration would still require a second output encoder and a bridge between view state, drawing state, and MDK. Build the smaller needed transform in-app and adapt its edge-case tests.

#### TOCropViewController

[TOCropViewControllerAspectRatioPreset.m](https://github.com/TimOliver/TOCropViewController/blob/9782ecf30b5d16dfc95c89c939180adf457a2e82/Objective-C/TOCropViewController/Models/TOCropViewControllerAspectRatioPreset.m) defines broad portrait/landscape presets. [TOCropViewController.m](https://github.com/TimOliver/TOCropViewController/blob/9782ecf30b5d16dfc95c89c939180adf457a2e82/Objective-C/TOCropViewController/TOCropViewController.m) provides reset, cancel, and quarter-turn behavior. [UIImage+CropRotate.m](https://github.com/TimOliver/TOCropViewController/blob/9782ecf30b5d16dfc95c89c939180adf457a2e82/Objective-C/TOCropViewController/Categories/UIImage+CropRotate.m) performs the final canvas transform.

Use it as a UX reference for reset visibility and orientation-aware preset labels, not as Android implementation input.

#### Fossify Gallery

[EditActivity.kt](https://github.com/FossifyOrg/Gallery/blob/1933e40ac69787104b3b91343b643666a49fd601/app/src/main/kotlin/org/fossify/gallery/activities/EditActivity.kt) combines CanHub crop with rotate, common presets, drawing, undo, and JPEG output. [EditorDrawCanvas.kt](https://github.com/FossifyOrg/Gallery/blob/1933e40ac69787104b3b91343b643666a49fd601/app/src/main/kotlin/org/fossify/gallery/views/EditorDrawCanvas.kt) stores view-coordinate paths in an unbounded map. Its EXIF utility copies broad source metadata after editing.

This confirms that assembling crop and draw widgets is easy while privacy, resolution independence, and bounded state remain unsolved.

#### SimpleX Chat

[ChooseAttachmentView.android.kt](https://github.com/simplex-chat/simplex-chat/blob/f921bd47bbf7701c5bd91eed0c3b4ab30c6f2a0c/apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/views/helpers/ChooseAttachmentView.android.kt) exposes distinct Camera, Gallery Image, Gallery Video, and File actions. [ComposeView.kt](https://github.com/simplex-chat/simplex-chat/blob/f921bd47bbf7701c5bd91eed0c3b4ab30c6f2a0c/apps/multiplatform/common/src/commonMain/kotlin/chat/simplex/common/views/chat/ComposeView.kt) keeps raw-file handling separate from image preparation. [Images.android.kt](https://github.com/simplex-chat/simplex-chat/blob/f921bd47bbf7701c5bd91eed0c3b4ab30c6f2a0c/apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/Images.android.kt) repeatedly resizes and re-encodes to meet a small byte target.

Adopt the explicit file/photo choice. Do not adopt full-size decode or iterative lossy re-encoding.

### Accessibility-specific source audit

- Telegram's [PhotoViewer.java](https://github.com/DrKLO/Telegram/blob/45ab8f4308496e1f01026a97fcdb0d58a5274474/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L7550-L7620) assigns content descriptions to crop, rotate, paint, and quality controls and exposes selected-photo count through AccessibilityNodeInfo. This is useful baseline coverage, but the inspected paint/crop model does not define the complete announcement, focus, large-text, or non-color-state contract required here.
- Signal Android's [ImageEditorUndoRedoButtons.kt](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/feature/media-send/src/main/java/org/signal/mediasend/screens/edit/image/ImageEditorUndoRedoButtons.kt) labels Undo and Redo, and [DrawModeColorBar.kt](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/feature/media-send/src/main/java/org/signal/mediasend/screens/edit/image/DrawModeColorBar.kt) labels draw colors. [ImageEditorToolbar.kt](https://github.com/signalapp/Signal-Android/blob/9b2c2ed66d854b7abb8ed1a29e976a516ab2ce67/feature/media-send/src/main/java/org/signal/mediasend/screens/edit/image/ImageEditorToolbar.kt) still contains a null content-description TODO for an inspected tool. The quality buttons carry readable text but do not explicitly expose selected semantics in QualitySelectorSheetContent.
- Signal iOS's [AttachmentApprovalToolbar.swift](https://github.com/signalapp/Signal-iOS/blob/58cc49ec14da01e7afa89d6e603ba1ca79bcf9b4/SignalUI/AttachmentApproval/AttachmentApprovalToolbar.swift#L340-L390) labels pen, crop, and media-quality controls and changes the quality label with state. No comparable complete accessibility contract was found in the inspected ImageEditor canvas/crop files.
- CanHub's core [crop_image_view.xml](https://github.com/CanHub/Android-Image-Cropper/blob/e10601a1a8386efc7b36de1311259274c1a89956/cropper/src/main/res/layout/crop_image_view.xml) suppresses a content-description lint warning on the custom image view. uCrop and PhotoEditor likewise do not supply an app-ready TalkBack interaction model in the inspected editor core.

- **Verified lesson:** mature apps label many toolbar actions, but none of the inspected cores can be adopted as proof of full editor accessibility.
- **Proposal:** accessibility semantics, focus behavior, announcements, and escape paths are White Noise acceptance criteria and tests, not properties delegated to a bitmap/crop dependency.

## Proposed v1 UX

### Entry and attachment preview

- Every editable static photo tile has an Edit action with an icon and text/semantic label.
- The album preview header shows the selected attachment's effective profile. High is displayed as “High (HD)”; a safety cap displays the actual resulting maximum rather than only the label.
- A photo selected through “File” shows a file treatment and no Edit button. A secondary action, “Convert to photo and edit,” explains that conversion removes metadata and produces a rendered image.
- Animated GIF/WebP, video, and non-image documents show no Edit action. The disabled explanation is reachable by accessibility services.
- Editing one item never changes album order or the identity of another item.

### Editor layout

- Top app bar: Cancel, concise filename/item position, and Save.
- Image stage: dark neutral surround, checkerboard only when alpha can be retained, bounded pan/zoom, conservative system-gesture exclusion over the stage only.
- Primary tool row: Crop, Draw, Erase, Undo, Redo, Reset.
- Crop panel: Free, Original, 1:1, 4:3, 3:4, 16:9, and 9:16; rotate-left 90°; reset crop.
- Draw panel: labeled color swatches plus current color text, and discrete widths Small, Medium, Large, Extra large. Discrete widths are easier to reproduce, test, and announce than a continuous slider.
- Erase panel: the same width choices. Erasing is stored as a vector command, not destructively applied to a bitmap.
- Quality control: attachment-local selector showing Low, Standard, High (HD), and Original/Edited where supported. The global setting seeds a newly staged attachment but does not silently override an existing attachment.
- Save shows cancellable progress. Editing gestures are disabled during final render; Cancel render returns to the unchanged committed draft.

### Interaction rules

- Entering a tool starts from the committed editor-session snapshot.
- Back while there are unsaved edits asks whether to discard them. Back with no change is equivalent to Cancel.
- Cancel discards the entire working recipe and does not call the draft save API.
- Reset clears crop, quarter turns, and drawing commands to the original EXIF-oriented image. Reset is itself undoable.
- Undo and redo operate across crop, rotation, draw, erase, and reset in chronological order.
- A new operation after Undo drops the redo branch.
- Rotation preserves the visible subject and recomputes orientation-aware preset geometry.
- Changing quality updates only the displayed effective output plan. It does not render a new JPEG while the user is editing.
- Reopening restores the attachment's committed recipe/source lease when available. It never starts from a previously compressed preview.

### Accessibility

- All controls have at least 48 dp touch targets and expose role, label, selected state, enabled state, and a concise action description.
- Selection is conveyed by shape/icon/text and semantics, never color alone.
- Focus order follows Cancel, Save, image description, tools, active tool controls, and quality.
- TalkBack announces tool selection, crop preset, rotation, undo/redo availability, reset, quality/effective dimensions, save progress, save success, and errors.
- Large text may wrap the tool panel but cannot cover Cancel or Save. The image stage yields vertical space before primary controls disappear.
- RTL mirrors chrome and focus order. Image geometry and a user's drawing are not mirrored.
- Switch access can reach every non-freehand operation. Freehand drawing requires direct touch or stylus in v1; Cancel, Save, Undo, and all crop/quality actions remain accessible without drawing.
- Color swatches have names such as “Blue, selected,” and widths have spoken labels.
- Dark/light/custom themes must maintain control contrast independent of image content by using solid scrims and outlined focus/selection indicators.

## Proposed architecture

### Ownership boundaries

| Component | Owns | Must not own |
|---|---|---|
| MessageDraftRepository | The single Android gateway to MDK draft read/save/delete; per-conversation serialization; observable lightweight snapshots; one-time text-draft migration. | Bitmaps, URI permissions, editor history, upload retry. |
| EditorSourceStore | Encrypted private source leases, copied-source limits, persisted-grant bookkeeping, digests, reference counts, stale recovery. | Draft attachment truth or final message state. |
| EditorSessionStore | Encrypted, bounded adjunct metadata keyed by attachment id and committed digest: recipe, profile, source lease id, and recovery markers. | Attachment bytes, captions, reply state, or any protocol/draft truth already represented by MDK. |
| PhotoEditorSession | One base attachment identity/digest, source lease, immutable recipe, bounded undo/redo, current profile, transient preview viewport. | Protocol state, raw giant bitmaps in saved state, direct MDK calls. |
| PhotoEditorTransform | Pure EXIF/orientation/crop/quarter-turn/output matrices and inverse gesture mapping. | Android lifecycle, files, encoders, UI state. |
| PhotoEditorRenderer | Sampled preview decode and one final pixel render using the shared transform. | Draft replacement or uploading. |
| MediaPipeline finalizer | Output dimension plan, alpha policy, one encode, metadata-free container, thumbhash, MIME/name/dim. | UI gestures or draft revision decisions. |
| Conversation media UI | Navigation, item selection, quality display, progress/error presentation. | Raw attachment bytes or concurrent draft merging. |
| Existing ConversationController | Encrypted upload, send, optimistic message, retry. | Re-decoding or recompressing a committed edited attachment. |

### Draft prerequisite

Create MessageDraftRepository as the only Android writer for message drafts. It wraps the existing generated MDK calls and exposes lightweight Compose state. DraftStore becomes a compatibility facade during migration; it must not persist a second attachment model.

Migration sequence:

1. On account initialization, map the legacy accountIdHex key to the current MDK accountRef through AccountSummaryFfi, then load MDK draft summaries.
2. If MDK has no draft, save the nonblank legacy text to MDK.
3. If MDK has attachments/reply state but blank content, save the legacy text while preserving those MDK fields.
4. If both stores contain identical nonblank text, keep MDK and mark the legacy value migrated.
5. If both contain different nonblank text, MDK wins. Retain the encrypted legacy value for a bounded 30-day recovery window and show an “Older local draft available” action that lets the user insert/copy it deliberately. Never concatenate or overwrite silently.
6. Delete the legacy value only after a verified MDK round trip, or after its conflict-recovery window expires with explicit cleanup policy.
7. Keep TextFieldValue selection as bounded UI/session metadata; only message content, reply id, and attachment artifacts belong in MDK. Use MDK updatedAtMs for draft ordering after migration.
8. Route composer autosave, reply changes, inbound-share merges, attachment stage/delete/reorder/edit, send-clear, and draft delete through the same per-conversation coordinator.

The migration needs its own tests and telemetry-free diagnostic counters because it changes draft ownership even though it does not change MDK or protocol APIs.

The generated MDK attachment type has no field for editor recipe, source lease, or output profile. Store that necessary bounded metadata in EditorSessionStore, authenticated-encrypted and keyed by account/group/attachment id plus the committed attachment digest. MDK remains authoritative for whether an attachment exists and which bytes will send. If adjunct metadata is absent or fails digest validation, the committed MDK attachment remains intact and sendable, but non-destructive reopen is unavailable until the user reselects a source. Do not encode editor state into filenames or protocol-visible fields.

### Draft write economics

The existing MDK save API accepts the whole draft, including attachment ByteArrays. There is no content-only update. To avoid copying up to the 32 MiB album on every keystroke:

- use summaries for the chat list and load a full draft only for the active conversation or an explicit mutation;
- update composer text immediately in repository memory, debounce durable text saves until 750 ms idle with a 2-second maximum latency, and conflate to one in-flight plus one latest snapshot;
- flush on conversation stop, app background, account switch, Send, and explicit draft mutation;
- make attachment add/remove/reorder/edit operations immediate durable mutations rather than debounced operations;
- when an attachment mutation meets dirty text, save the latest in-memory text in the same whole-draft transaction;
- run every FFI read/save on the IO dispatcher, hold no Compose snapshot lock across it, and avoid extra Kotlin ByteArray copies in repository/reducer code;
- keep full-draft plaintext cached only for a bounded set of active mutations and release it after observers have lightweight attachment models.

This is the best available behavior without an MDK partial-update API. Phase 2 performance tests must measure the unavoidable UniFFI copy and SQLCipher write with a full 32 MiB album while typing, backgrounding, and saving an edit.

### Edit recipe and bounded state

The model is conceptually:

    EditorSession
      attachmentId: stable MDK attachment id
      baseAttachmentDigest: SHA-256 of relevant committed fields and plaintext bytes
      sourceLeaseId: private source capability
      sourceFingerprint: bounded source length plus SHA-256
      sourceOrientation: validated EXIF orientation
      recipe: PhotoEditRecipe
      outputProfile: attachment-local MediaQuality snapshot
      history: capped snapshots
      viewport: transient pan/zoom only

    PhotoEditRecipe
      crop: normalized rectangle in oriented-source coordinates
      quarterTurns: 0, 1, 2, or 3 clockwise
      marks: ordered DrawStroke or EraseStroke commands

    Stroke
      id
      mode: draw or erase
      normalized width relative to oriented source short edge
      color in a fixed supported color space
      normalized points with pressure omitted in v1

State rules:

- Use immutable recipes and a reducer. The currently moving crop handle or in-progress stroke is transient; commit one history entry at gesture end.
- Keep at most 50 undo states and 50 redo states, with a combined serialized metadata ceiling of 256 KiB.
- Keep at most 256 marks, 2,048 points per mark, and 100,000 points total.
- Coalesce pointer samples by distance/time during input and run a linear-time polyline simplifier at gesture completion. Never simplify on every added point.
- When a limit is reached, complete the current bounded operation, announce the limit, and disable additional marks until the user undoes or resets. Do not evict the original state silently.
- Do not put preview bitmaps, final bitmaps, attachment plaintext, full history, source URI, or source path in rememberSaveable/SavedStateHandle.
- SavedStateHandle keeps only an opaque editor session id and small UI choices. EditorSessionStore persists the authenticated-encrypted current recipe, attachment id/digest, profile, and source lease id for process recovery. Persist no redo history; a restored session begins with the recovered recipe as its single baseline.

### Coordinate model

Define four explicit spaces:

1. **Encoded source space**: raw decoder dimensions before EXIF.
2. **Oriented source space**: EXIF applied once; this is the canonical edit space.
3. **Edited output space**: normalized crop and quarter-turn transformed to the selected output dimensions.
4. **View space**: fit/pan/zoom used only for interaction.

All crop rectangles and stroke points live in normalized oriented-source coordinates. A normalized point is independent of preview sampling and Standard/HD dimensions.

The transform chain is:

    encoded source
      -> EXIF orientation matrix
      -> oriented source
      -> normalized crop-to-origin matrix
      -> quarter-turn matrix about the cropped extent
      -> scale-to-output matrix
      -> final output pixels

Preview adds a final output-to-view fit/pan/zoom matrix. Pointer input applies the inverse of that view chain and stores the resulting normalized oriented-source point.

One pure transform builder returns forward and inverse matrices, output aspect, and integer output bounds. Preview overlays and final rendering consume that same result. No UI component may independently recreate crop/rotate arithmetic.

Geometry invariants:

- Crop bounds are finite, normalized, ordered, and clamped to the oriented source.
- Minimum crop is the larger of 32 source pixels or 1% of the shorter oriented edge.
- Quarter turns are canonicalized modulo four.
- Output dimensions are calculated with checked Long arithmetic, then validated before conversion to Int.
- Scaling never upscales beyond the edited crop's native pixel extent.
- Standard and High use exactly the same normalized recipe; only their final scale/encoding plan differs.
- Stroke width is defined relative to the oriented source's shorter edge, then transformed with the image. It cannot depend on device density or preview zoom.
- Erase commands remove earlier drawing-layer pixels only. They never erase the photo itself or reveal uninitialized pixels.

### Decode, preview, render, and encode

The required final path is:

    source capability
      -> bounded header sniff and bounds decode
      -> validated EXIF orientation
      -> sampled oriented preview decode
      -> non-destructive edit recipe
      -> final target plan from selected profile
      -> one bounded oriented decode for that target
      -> one Canvas render of crop + rotation + draw/erase layer
      -> explicit alpha policy
      -> one MediaPipeline encode/finalize
      -> thumbhash and dimensions from final pixels
      -> stale-target verification
      -> one MDK whole-draft save replacing the intended attachment
      -> existing MDK encrypted upload and send

Interactive preview:

- Decode at most 1,536 px on the long edge and at most 4 megapixels.
- Apply orientation during preview decode and display through the same transform builder.
- Draw vector overlays at view resolution; do not bake them into the preview bitmap after each gesture.
- Keep one full preview bitmap, one small transition bitmap only while a replacement is ready, and bounded thumbnails. Recycle/release the prior preview immediately after swap.
- Debounce preview source/geometry jobs and use latest-wins cancellation. Do not queue a decode per gesture.

Final render:

- Snapshot the recipe and profile before leaving the UI state dispatcher.
- Compute the final target dimensions before allocating a bitmap.
- Decode close to target dimensions with sampling. Because arbitrary crop can select a small region, use region decode only when the source capability or authenticated store adapter can safely provide it; otherwise decode the smallest sampled whole image that can satisfy output detail. Never create a decrypted seekable temp file merely to enable region decode.
- Apply EXIF and edit geometry in one output Canvas pass. Render the photo first, recycle its decode when safe, then render marks through bounded overlapping tiles. Each tile replays only intersecting draw/erase commands into a transparent scratch bitmap; erase clears that tile's mark pixels, never the photo. Composite the seam-safe center of each tile onto the output. This avoids a second full-size marks bitmap.
- Encode exactly once. The returned encoded bytes are already the finished attachment; ConversationMediaSender must not pass them through readDownscaledJpeg again.
- Generate thumbhash and dim from the exact post-alpha output pixels.
- V1 keeps the bounded encoded result in memory because the generated MDK API accepts a ByteArray. Do not spill final plaintext to disk. If the existing 32 MiB album budget cannot hold the result, reduce only through an explicit effective-profile choice or leave the old attachment intact.
- Release an uncommitted ByteArray on cancellation, stale conflict, or error. There is no final plaintext output file to orphan.

MediaPipeline should be refactored around a shared finalizer that accepts already-rendered pixels plus an OutputPlan. Existing URI/byte entry points can call that finalizer, but the editor must not call a URI-to-JPEG entry point after rendering. Add debug/test-only encode-count instrumentation so double encoding is mechanically detectable.

The requested quality ordering maps exactly as follows:

    source
      -> bounded sniff; read EXIF orientation and discard all other source metadata
      -> oriented decode
      -> immutable edit commands
      -> Standard/High OutputPlan chooses final pixel dimensions
      -> one final render
      -> metadata-isolation policy: copy no source metadata; whitelist no sensitive fields
      -> one clean-container encode using the Standard/High encoder quality
      -> optional lossless container sanitization, never a second pixel encode
      -> final-pixel thumbhash and dimensions
      -> atomic MDK draft replacement
      -> existing encrypted upload and send

Thus profile dimensions are first applied when planning the final render, and profile JPEG quality is applied only at the single encode. Neither choice changes the edit recipe or causes an intermediate JPEG.

### Standard, High/HD, Low, and Original

Verified current profiles:

| Profile | Current image ceiling | Current JPEG quality | Editor behavior |
|---|---:|---:|---|
| Low | 1024 px long edge | 70 | Supported but not part of the issue's required quality bar; render once using the same geometry. |
| Standard | 2048 px long edge | 85 | Required v1 path. |
| High (HD label) | 4096 px long edge | 92 | Required v1 HD path. |
| Original | Byte-preserving when unedited and safely strippable | 100 fallback | Once edited, byte preservation is impossible; use the explicit edited safety plan below. |

Proposed application rules:

- The profile is captured per draft attachment when staged and shown in preview/editor.
- Changing the global preference affects newly staged attachments, not existing draft items.
- Changing a profile in the editor updates only the OutputPlan preview. Save performs a fresh final render from source plus recipe.
- Reopening an editor reads the committed profile from bounded attachment/session metadata.
- Standard and High share crop/stroke geometry and metadata stripping. Their only differences are output dimensions and encoder parameters.
- An unedited attachment may continue to use current Original lossless stripping. An edited Original is labeled “Original (edited, max 4096 px / 12 MP)” and rendered once. This avoids an unbounded full-source bitmap while making the effective cap honest.
- Never upscale. A 1200 px source remains at or below 1200 px under High.
- Animated images continue through the unedited original path at every profile. Editing is unavailable unless a future explicit “extract still frame” feature is approved.

Alpha policy proposal:

- Low, Standard, and High retain the current JPEG contract and composite transparent pixels over white. The stage previews that exact white background.
- Edited Original with alpha uses metadata-free PNG when the planned image is at most 4096 px, 12 MP, and 32 MiB encoded. Otherwise Save explains that the chosen result must be flattened or reduced and lets the user choose; it never silently changes representation.
- Thumbhash is calculated after the same flatten/preserve decision.

If product intends to replace the existing four-profile setting with a strict Standard/HD two-profile contract, that should be a separate, explicit migration decision. The editor must not invent that migration implicitly.

## Atomic MDK draft replacement

### What MDK guarantees

A saveMessageDraft call is transactionally all-or-nothing in the pinned MDK implementation. It writes the whole logical draft and synchronizes attachment rows. It does not accept an expected revision, and updatedAtMs is descriptive rather than a database compare-and-swap precondition.

Therefore, an Android-only design can guarantee stale safety only if every Android draft mutation goes through one coordinator for the account/group. It cannot close a race with an unknown writer that bypasses that coordinator without an MDK API change. No such external Android writer exists today because there are currently no MDK draft callers; Phase 2 must preserve that invariant.

### Replacement identity

Never identify an attachment by UI index alone. An editor base token contains:

- account reference and group id;
- stable attachment id;
- SHA-256 digest over attachment id, MIME, filename, dim, thumbhash, and plaintext bytes;
- source fingerprint and source lease id;
- base draft updatedAtMs for diagnostics;
- an in-process monotonic generation issued by MessageDraftRepository.

### Save algorithm

1. Open the editor from a committed MDK attachment snapshot and capture its base token.
2. Keep all gestures in session state; MDK remains unchanged.
3. On Save, freeze the recipe/profile and render a complete replacement off the main thread. This step holds no draft lock.
4. Acquire the per-account/per-group draft mutex.
5. Re-read the current MDK draft.
6. Find the attachment by stable id and recompute its digest.
7. Abort as stale if the draft is absent, the attachment is absent, its digest changed, its id now identifies different content, the source lease is invalid, or the draft was sent/deleted.
8. Preserve the current draft's text, reply id, attachment ordering, and every other attachment. This deliberately allows caption/text edits that happened while rendering, because they are safe to merge.
9. Replace only the matching attachment object with the complete rendered artifact. Keep the stable id and advance the Android attachment generation.
10. Atomically write an encrypted pending EditorSessionStore record containing the new digest, recipe/profile, and source lease. It is not yet authoritative.
11. Call saveMessageDraft once while still in the coordinator's serialized mutation.
12. Publish the returned lightweight draft snapshot, promote the pending metadata record only if its digest matches MDK, and release superseded leases after readers move to the new generation.
13. On any failure before step 11 completes, the old draft is unchanged and the pending metadata is discarded. On an ambiguous FFI failure, re-read MDK and compare the target digest before promoting or discarding the prepared metadata.

This prevents a stale result from overwriting a newer selection, newer edit, deleted/sent draft, or another item in the same position. A concurrent text edit is preserved rather than causing needless failure.

At start-up, pending metadata is reconciled by digest: promote it if MDK contains the exact new attachment; discard it if MDK still contains the old digest or no attachment. This makes a crash between MDK commit and adjunct-metadata promotion recoverable without weakening MDK's atomic attachment guarantee.

### Cancel, background, and duplicate actions

- Cancel never enters the coordinator and cannot alter the draft.
- A coroutine cancellation during render releases only its uncommitted in-memory result.
- Backgrounding may let a bounded final render continue in the process-lifetime mutation scope, but navigation displays the existing attachment until MDK commit succeeds.
- Save is idempotent per editor session token. A second tap observes the same in-flight job and cannot append or replace twice.
- Account switch cancels render, releases UI references, and refuses commit if the active/account capability no longer matches.
- Sending/deleting obtains the same coordinator. It either observes the old attachment before edit commit or the fully committed replacement after it.

## Source lifetime, temporary files, and process recovery

### Source acquisition

At staging time, establish a source lease before exposing an editable draft item:

1. Validate MIME by bounded sniffing rather than trusting provider metadata.
2. For a URI that supports a persistable read grant and whose picker contract permits it, take only read permission and record the exact URI/grant owner.
3. For transient Photo Picker, share, paste, or provider grants, stream at most the source byte ceiling into an encrypted app-private no-backup source while the grant is live. Use a versioned, independently authenticated chunked AES-256-GCM envelope with a random per-source data key protected by Android Keystore; never persist an unwrapped key or reuse a nonce.
4. Camera sources initially live as plaintext private capture files. Ingest and encrypt them under the same lease registry, then delete the capture file only after the baseline MDK attachment and encrypted source lease have committed.
5. Hash the plaintext stream while encrypting, use a unique encrypted partial file, validate length/authentication metadata, close, then atomically rename.
6. Never expose these paths through MediaStore and never modify the original.

Private no-backup storage is preferred over ordinary cache for a source that must survive process death with a draft. Encryption at rest is mandatory. Preview/final decoders receive a bounded authenticated decrypting stream; no decrypted source file is created. The encrypted source is still temporary application data and must be deleted when its final lease is released.

### Lease and cleanup rules

- A source lease is keyed by an opaque id, not by a raw path in Compose state.
- Reference holders are draft attachment generation, active editor session, and in-flight final render.
- Replacement first commits the new generation, then releases the old generation. Reference count changes happen under the repository/source-store coordinator.
- Draft deletion, successful send, explicit attachment removal, account removal, or conversion to raw file releases the draft's source lease.
- Cancel releases only the editor session's extra reference, not the committed attachment's source.
- Interrupted encrypted source-ingestion partials have no lease and are deleted on start-up unless actively registered.
- A start-up reconciliation compares the bounded session registry with MDK draft attachment ids. It releases sources with no MDK attachment and no recent in-flight marker.
- Run a conservative stale sweep at start-up and periodically: encrypted ingestion partials older than one hour; abandoned editor sessions older than 24 hours; source leases with no MDK owner after reconciliation. Never delete merely because an activity was destroyed.
- Enforce a per-account editor-source budget of 256 MiB and a per-source encoded cap of 32 MiB. Refuse a new staged edit or ask the user to send as file when the budget cannot be reclaimed safely.

Persisted URI grants should be released when the final lease ends. If release fails, record a bounded cleanup retry without logging the URI.

### Recreation and process death

- Rotation/activity recreation restores the opaque session id and small UI state, then loads the attachment id, digest, source lease, current bounded recipe, and selected profile from encrypted session metadata. Preview is decoded again.
- Process death restores only the current recipe, never the full history or any bitmap. If the source lease and MDK base attachment still validate, the editor resumes with that recipe as a new one-entry history.
- If the source was revoked or storage was reclaimed, show the intact committed draft attachment and explain that editing cannot resume. Offer reselect/replace; do not decode the already compressed preview as a hidden source.
- If death occurs during final render, the in-memory result disappears and MDK contains either the old attachment or the committed new one. Start-up reconciliation validates the source/session lease against the MDK digest.

## Threat model and explicit limits

### Assets and adversaries

Assets:

- original photo content and metadata;
- edited plaintext before MDK encryption;
- source URI capabilities;
- draft attachment identity and ordering;
- app availability and memory.

Adversaries/failures:

- malicious or buggy content providers;
- crafted JPEG/PNG/WebP/GIF headers, EXIF, ICC, dimensions, or truncation;
- decompression bombs and extreme aspect ratios;
- decoder/runtime bugs, long-running decode, OOM, cancellation, and disk exhaustion;
- stale UI jobs and concurrent draft edit/send/delete;
- path/URI leakage through logs, backups, analytics, or MediaStore;
- reference-count mistakes deleting a live source or retaining plaintext indefinitely.

### Enforced v1 ceilings

| Resource | Proposed hard limit | Behavior at limit |
|---|---:|---|
| Encoded editable source | 32 MiB | Refuse edit; retain raw file-send option where allowed. |
| Encoded final image and current album total | 32 MiB | Respect the existing album budget; reduce only with explicit effective-profile feedback or fail without replacing draft. |
| Raw source dimension | 32,768 px per edge | Refuse edit before pixel allocation. |
| Raw source pixels | 200 MP from checked bounds | Refuse edit; no full decode. |
| Source aspect ratio | 100:1 | Refuse editor; raw file send remains possible. |
| Preview | 1,536 px long edge and 4 MP | Sample more aggressively. |
| Final edited output | 4,096 px long edge and 12 MP | Show effective cap; never claim uncapped Original/HD. |
| Live final working memory, including encoded buffers, decoded source, output bitmap, and scratch | Lesser of 128 MiB or one third of Runtime maxMemory | Release preview first; reduce the effective target visibly or fail before allocation. |
| Concurrent final renders | 1 per process | Latest user actions wait visibly or cancel their own older job. |
| Marks | 256 | Announce limit; Undo/Reset remains available. |
| Points per mark | 2,048 | Coalesce/simplify; stop accepting extra points if still over. |
| Total points | 100,000 | Announce limit; no silent history eviction. |
| Undo / redo | 50 each; 256 KiB serialized current state | Drop oldest non-baseline undo state; persist current recipe only. |
| Private editor sources | 256 MiB per account | Reconcile/reclaim or refuse staging. |
| Bounds/sniff work | 2 seconds target | Cooperative cancellation and failure; header/byte bounds are the primary defense. |
| Preview decode | 5 seconds target | Cancel latest-wins job and show retry. |
| Final render/encode | 15 seconds target | Cooperative cancellation; keep old draft intact. |

Android's in-process decoders do not provide a reliable hard kill for every malformed-media hang. Duration limits are cooperative. The stronger controls are bounded reads, header validation, dimension/pixel checks, sampled allocation, one render at a time, cancellation checks between stages, and never doing decoder work on the main thread.

### Privacy rules

- Editor operations are entirely local and initiate no network requests.
- No analytics event records editor use, tool choices, colors, image properties, or failures.
- Logs may contain only opaque operation id, stage, coarse dimensions, byte bucket, elapsed time, and error category. Never log URI, path, filename, caption, pixels, hash, EXIF, or provider authority.
- Retained source files are authenticated-encrypted, private, no-backup, non-MediaStore files with app-only permissions. V1 creates no final plaintext output file.
- Metadata is removed for every edited output profile. No source EXIF, GPS, device, XMP, IPTC, comment, or timestamp block is copied.
- Treat malformed ICC as hostile. V1 renders into a known sRGB output and does not preserve arbitrary source ICC blobs.
- Clipboard/paste grants are materialized immediately and released.
- Plaintext lifetimes are minimized: recycle/release bitmaps and byte arrays, delete superseded encrypted source files after safe commit, and preserve only the MDK-encrypted draft artifact plus a justified encrypted source lease.

## Build-versus-dependency recommendation

Build a focused in-app editor.

Reasons:

1. White Noise already has EXIF, sampling, metadata stripping, alpha flattening, quality profiles, thumbhash, and upload integration. A library output would have to be decoded or trusted as a parallel pipeline.
2. No inspected dependency combines crop, quarter turns, draw, eraser, bounded undo/redo, source-resolution rendering, explicit profile application, lifecycle recovery, and atomic MDK replacement.
3. uCrop and CanHub are strong crop components but write their own output and would still require an independent drawing coordinate model.
4. PhotoEditor provides drawing/eraser but exports the UI View and uses unbounded stacks.
5. Pulling separate crop and drawing libraries would introduce two transform models. The core quality requirement is one shared, tested model.
6. App-owned code keeps the privacy surface small and makes one-encode instrumentation possible.

Reuse Android platform Matrix, Canvas, Paint, BitmapFactory/ImageDecoder where bounded, ExifInterface only for orientation parsing, coroutines, and existing White Noise MediaPipeline utilities. Adapt test cases and UX concepts from compatible references, but do not copy GPL/AGPL code.

## File-level implementation plan

No new Gradle module is required for v1. Keep the editor close to conversation media while isolating pure geometry/rendering for JVM and instrumentation tests.

### New production files

| File | Responsibility |
|---|---|
| app/src/main/java/dev/ipf/whitenoise/android/state/MessageDraftRepository.kt | Sole MDK draft gateway, per-conversation mutex, observable summaries, migration, attachment replace preconditions, send/delete coordination. |
| app/src/main/java/dev/ipf/whitenoise/android/state/MessageDraftModels.kt | Lightweight draft/attachment identity, digest, profile/source metadata, mutation results; no bitmap types. |
| app/src/main/java/dev/ipf/whitenoise/android/media/editor/EditorSourceStore.kt | Keystore-protected source encryption, grant/copy lease registry, atomic file creation, budgets, reconciliation, cleanup. |
| app/src/main/java/dev/ipf/whitenoise/android/media/editor/EditorSessionStore.kt | Keystore-protected bounded recipe/profile/base-token persistence, pending/committed digest records, process recovery. |
| app/src/main/java/dev/ipf/whitenoise/android/media/editor/PhotoEditRecipe.kt | Immutable crop, quarter turns, strokes, normalized widths, validation, bounded serialization. |
| app/src/main/java/dev/ipf/whitenoise/android/media/editor/PhotoEditReducer.kt | Gesture completion, undo/redo/reset, history and point ceilings. |
| app/src/main/java/dev/ipf/whitenoise/android/media/editor/PhotoEditTransform.kt | Pure forward/inverse coordinate transforms and target geometry. |
| app/src/main/java/dev/ipf/whitenoise/android/media/editor/PhotoEditorRenderer.kt | Sampled preview and one final render; latest-wins cancellation and allocation accounting. |
| app/src/main/java/dev/ipf/whitenoise/android/media/editor/PhotoEditorCommitter.kt | Render snapshot orchestration and stale-safe MessageDraftRepository replacement. |
| app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/editor/PhotoEditorScreen.kt | Compose editor shell, tool panels, semantics, progress/error UI. |
| app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/editor/PhotoEditorState.kt | Screen/session state and events; SavedState holds only an opaque session id and small UI choices. |
| app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/editor/PhotoEditorCanvas.kt | Viewport, gesture mapping, crop handles, vector mark overlay, system gesture exclusion. |

### Existing production files to modify

| File | Planned change |
|---|---|
| state/DraftStore.kt and state/DraftCodec.kt | Convert to a UI compatibility facade/migration reader; stop owning protocol draft persistence after verified migration. Keep TextFieldValue selection as bounded UI metadata. |
| state/AppState.kt | Construct MessageDraftRepository, EditorSourceStore, and EditorSessionStore; route draft read/write/flush/account cleanup through them. |
| state/Controllers.kt | Send committed MDK draft attachments without recompression; coordinate send/delete with the draft mutex; release leases after definitive send/delete. Keep RetainedMediaUpload only for upload retry. |
| ui/conversation/ConversationScreen.kt | Stage selected photos into MDK drafts by stable id, navigate to editor, remove raw URI list as attachment truth, preserve camera lifecycle through source leases. |
| ui/conversation/media/MediaPreview.kt | Add Edit and effective-quality controls; render draft attachment models rather than URI-only models; make image/file semantics explicit. |
| ui/conversation/ConversationMediaSender.kt | Accept finalized committed attachments; do not re-run image preparation for edited items; keep raw document bytes raw. |
| ui/conversation/media/MediaIo.kt | Delegate source ownership/cleanup to EditorSourceStore; persist eligible read grants narrowly; change document-picker images to raw file semantics. |
| media/MediaPipeline.kt | Extract shared OutputPlan and already-rendered-pixels finalizer; declare limits; preserve one metadata/alpha/thumbhash path; add test-only encode counter. |
| state/MediaQuality.kt | Add editor-facing effective-label/plan mapping without changing the current preference values. High maps to “High (HD).” |
| share/ShareInboundStager.kt | Materialize transient share sources and route text/attachments through MessageDraftRepository. |

### New and extended tests

| Test file | Coverage |
|---|---|
| media/editor/PhotoEditTransformTest.kt | EXIF variants, crop/turn composition, inverse mapping, presets, extreme ratios, checked dimensions. |
| media/editor/PhotoEditReducerTest.kt | Draw/erase/reset, chronological undo/redo, redo truncation, coalescing, history/mark/point limits. |
| media/editor/PhotoEditorRendererTest.kt | Standard/High geometry equality, one encode, alpha policy, metadata-free output, deterministic dimensions. |
| media/editor/EditorSourceStoreTest.kt | Atomic copies, cap enforcement, reference-safe cleanup, revoked grants, recovery reconciliation. |
| media/editor/EditorSessionStoreTest.kt | Encryption/authentication, bounded serialization, pending-record recovery, digest mismatch, expiry, no sensitive SavedState payload. |
| state/MessageDraftRepositoryTest.kt | Migration, whole-draft merge, stable-id replacement, text-safe merge, stale selection/edit/delete/send races, duplicate Save. |
| ui/conversation/media/PhotoEditorSemanticsTest.kt | Labels, roles, selected states, focus order, announcements, touch targets, non-color selection. |
| ui/conversation/media/PhotoEditorLifecycleTest.kt | rotation, background/recreation, process-death state size, unavailable source fallback. |
| ui/screenshot/PhotoEditorScreenshotTest.kt | Crop/draw panels, large text, RTL, dark/light/custom themes, alpha background, progress/error states. |
| Existing MediaPipelineTest.kt and MediaPipelineBoundedReadTest.kt | Output plans, metadata stripping for both required profiles, malformed/truncated/adversarial sources, overflow/OOM paths, encode count. |
| Existing MediaPreviewLogicTest.kt and MediaPreviewContentTest.kt | Edit visibility, raw-file behavior, effective quality, animated/video/document exclusions. |
| Existing InFlightMediaUploadsTest.kt and draft tests | Committed attachment send, retry, account switch, cleanup after send, old DraftStore migration. |

## Test strategy and acceptance evidence

### Pure unit tests

- Test every EXIF orientation from encoded corners into oriented-source corners.
- Compose each crop preset with zero through three quarter turns in portrait and landscape.
- Round-trip representative view points through forward/inverse matrices within a declared epsilon.
- Assert identical normalized crop/stroke placement for Standard and High outputs.
- Test no-upscale and checked arithmetic at zero, negative, maximum, and overflowing bounds.
- Test stroke coalescing and simplification on straight, curved, dense, and adversarial repeated points.
- Test undo/redo across mixed tools, reset undo, history cap, redo invalidation, and process-restored baseline.
- Test the eraser affects only the mark layer and preserves source pixels.
- Test OutputPlan selection, edited Original cap, alpha decisions, and honest effective labels.

### Pipeline and golden tests

- Curate small licensed fixtures for EXIF 1–8, transparent PNG/WebP, wide/tall extremes, truncated headers, oversized dimensions, malformed EXIF/ICC, and animated GIF/WebP.
- Pixel-golden representative crop + rotate + draw + erase results at Standard and High. Compare normalized geometry separately from compression tolerance.
- Assert final dimensions, MIME, filename extension, alpha/white background, thumbhash source, and absence of EXIF/GPS/XMP/IPTC/comment chunks.
- Instrument finalizer calls and assert one lossy encode per edited save, including quality changed before/after drawing and reopening an edit.
- Cancel at bounds read, preview decode, final decode, render, encode, pre-commit verification, and ambiguous MDK return. The old attachment digest must remain intact unless the new complete digest is in MDK.
- Run malformed and resource tests in an isolated process where practical so an OOM or decoder crash cannot corrupt the main test runner.

### Draft and concurrency tests

Use deterministic barriers around render and coordinator stages:

- attachment A edit races with replacement by B at the same UI index;
- edit revision 1 completes after revision 2;
- attachment is deleted while render runs;
- draft is sent/cleared while render runs;
- text/reply changes while render runs and must be preserved;
- another attachment is added/reordered and must be preserved;
- Save is tapped twice;
- account changes before commit;
- MDK save succeeds but caller receives an ambiguous failure;
- process dies with an interrupted encrypted source ingest, a pending adjunct-metadata record, and just after MDK commit.

Assertions must inspect stable ids and digests, never only list indices.

### Compose and accessibility tests

- Enter editor from the intended tile and return to the same album position.
- Select Crop, every preset, rotate, Draw, Erase, Undo, Redo, and Reset.
- Verify disabled/enabled state transitions and spoken state descriptions.
- Verify Standard and High (HD) remain visible and do not change silently.
- Verify raw file and animated inputs cannot enter the editor without explicit conversion behavior.
- Verify Cancel with and without edits, Save progress, cancellation, stale conflict, decode failure, disk full, and success.
- Verify 48 dp targets, large-font layout, TalkBack traversal, switch access escape path, RTL chrome, contrast, and non-color-only state.
- Verify gesture exclusion covers only the active canvas and system Back remains reachable.

### Performance evidence

Benchmark on the project Pixel test device and at least one constrained-memory emulator/profile:

- preview decode and first frame;
- continuous 60-second drawing with maximum accepted sample rate;
- crop/pan/zoom responsiveness;
- Standard and High final render for 12 MP, 48 MP, and highly cropped sources;
- cancellation latency;
- peak Java/native/graphics memory;
- source-store disk reclamation.

Acceptance targets:

- no bitmap decode/render/encode on the main thread;
- responsive gesture frames without a growing work queue;
- preview first useful frame target under 500 ms for a typical 12 MP local photo;
- cancellation observed between stages within 250 ms where the platform decoder is not currently blocked;
- live final working-memory estimate never exceeds the lesser of 128 MiB or one third of the process heap; effective resolution falls visibly on smaller heaps;
- one final render/encode;
- old or new complete MDK attachment after every injected failure, never a partial.

## Clear v1 scope

Included:

- static image attachments staged as photos;
- free crop plus Original, 1:1, 4:3, 3:4, 16:9, and 9:16;
- 90° rotation;
- freehand draw with fixed palette and four widths;
- drawing-layer eraser with four widths;
- chronological undo/redo capped at 50;
- reset and non-destructive cancel;
- Standard and High (HD) single-render output;
- deterministic behavior for existing Low and Original settings;
- effective quality/dimension display;
- stable MDK attachment identity and stale-safe whole-draft replacement;
- private source leases, recreation/process recovery, cleanup;
- explicit raw “send as file” behavior;
- privacy, accessibility, hostile-input, lifecycle, race, and golden coverage.

Excluded:

- animated image editing or silent first-frame flattening;
- video or document editing;
- text, stickers, emoji, shapes, filters, blur, tonal controls, perspective correction, arbitrary-angle rotation, flip, redaction, and AI tools;
- remote assets, fonts, stickers, analytics, or cloud processing;
- protocol, Nostr event, Marmot, or MDK API changes;
- editing the original gallery/camera object;
- an uncapped full-resolution edited Original allocation.

## Suggested roadmap

### v1.1: polish and richer markup

- text with bundled/system-safe fonts and normalized layout;
- arrows, rectangles, and privacy blur/redaction;
- pressure-sensitive stylus width where available;
- color picker with accessible numeric/name fallback;
- keyboard/pointer drawing support;
- lossless WebP output evaluation for alpha.

### v1.2: creative elements

- bundled stickers/emoji with no network fetch;
- per-element selection, transform, z-order, and deletion;
- shape snapping and alignment guides;
- richer crop presets and flip;
- export-size estimate before Save.

### v2: photographic adjustments

- exposure, contrast, saturation, temperature, highlights/shadows;
- vetted GPU/RenderEffect pipeline with CPU fallback;
- deterministic filters represented as recipe parameters;
- arbitrary rotation and perspective correction;
- color-space/HDR policy after privacy and device-compatibility review.

Every future effect must remain a bounded recipe command and flow through the same source-based one-final-render path. Effects must not create a second draft format or lossy intermediate.

## Approval gates before Phase 2

Approval of this design should explicitly settle:

1. **Draft prerequisite:** migrate Android text drafts to one MDK-backed MessageDraftRepository before editor UI work.
2. **Quality naming:** retain Low, Standard, High, Original and label High as “High (HD),” rather than silently replacing the current model with two values.
3. **Edited Original:** accept the visible 4096 px / 12 MP safety cap and PNG alpha policy.
4. **File semantics:** image MIME selected through Files remains a raw file; editing requires explicit conversion to a photo.
5. **Source retention:** allow bounded, authenticated-encrypted private no-backup source copies for the lifetime of an editable draft so quality changes and reopen do not introduce generational loss.
6. **Atomicity boundary:** require every Android MDK draft mutation to use the per-conversation coordinator; no MDK compare-and-swap API change.
7. **Dependency choice:** approve the app-owned editor core with no new crop/drawing dependency.
8. **Whole-draft write cost:** accept debounced/conflated MDK text autosave and require measurement of the unavoidable full-attachment UniFFI/SQLCipher write, since the current API has no content-only update.

After approval, implementation should proceed in vertical slices:

1. MDK draft repository, migration, stable attachment ids, and raw-file semantics.
2. Source leases and photo staging with recovery/cleanup tests.
3. Pure recipe/reducer/transform plus unit and golden fixtures.
4. Crop/rotate editor UI and stale-safe Save.
5. Draw/erase/undo/redo and accessibility.
6. Standard/High finalizer, one-encode enforcement, alpha/metadata policy.
7. Full lifecycle, adversarial, performance, and accessibility verification.

Approval was granted for all eight gates before Phase 2 production work began.

Implementation note: retained source payloads use the app's existing authenticated-encrypted `DiskByteCache` envelope per bounded source entry. This preserves the approved AES-GCM/Keystore/no-backup privacy boundary, but uses a whole-entry envelope instead of introducing the proposed new chunked container format.
