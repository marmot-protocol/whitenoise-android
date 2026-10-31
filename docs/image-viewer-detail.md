# Image viewer detail

Chat attachments and explicitly loaded public event images share the viewport, fullscreen chrome and accessibility actions. Acquisition stays source-specific. Attachment bytes retain their existing authenticated transfer and source-epoch checks. Public images retain explicit consent, safe URL acquisition and the size-keyed raster preview; deep zoom of that public preview enlarges its available pixels. Its resolution stays limited to that preview.

The shared viewport uses a finite 1–1024× scale. Pinch, pan, accessible zoom and actual-size actions use the same clamped geometry. Double tap and Fit image return to the fitted source. Actual size uses inspected original chat dimensions. The existing raw-coordinate EXIF behavior is preserved for chat static base previews and detail regions. Avatar viewers retain their separate 5× default.

## Source and decode paths

| Source | Fitted presentation | Deep detail | Limit or fallback |
| --- | --- | --- | --- |
| JPEG, PNG and still WebP | Existing sampled chat preview | Isolated platform region decoder over original bytes | Platform region support varies; a failed region retains the base or requests one improving whole-source fallback |
| Other platform-admitted raster formats | Existing sampled preview | Same region request when supported | Whole-source fallback only when sharper than the 2560-edge base; at most 4096 edge and 4,194,304 pixels/16 MiB |
| Safe SVG attachment | Worker-rendered full-document preview | Worker rerenders only the visible document rectangle from original vector content | Worker validation keeps parsing and rendering in the isolated worker and resources self-contained |
| Admitted GIF/animated WebP | Existing animation admission, with viewer original-canvas opt-in | Playback keeps its bounded source-sized session and moving frames through gestures | Original animation canvas/pixel/frame/work and worker-output limits remain authoritative |
| Refused animation or malformed source | Existing refusal/failure presentation | Unavailable | Content-derived admission gates every decode path |
| Public event image | Existing explicitly requested bounded raster preview | Shared transforms only | Explicit-load consent and the existing memory-cache policy remain in force |

These are implementation contracts. Format and device qualification remain pending until their actual tests pass. Platform codecs can refuse individual files. Unsupported or failed detail leaves the usable base image; a detail tile is drawn at its decoded source rectangle.

## Scheduling and ownership

Only the current, started chat page retains original bytes, inside the existing 64 MiB presentation input ceiling. A page/controller/source-epoch change replaces its identity; backgrounding or leaving the page drops that retained source. Source preparation follows the source identity and lifecycle. Panning updates source geometry, with outward quantization and an 80 ms settling window. The detail collector cancels and joins its previous operation. A shared mutex acquired before reading bytes serializes source acquisition, interruptible inspection, base decoding and detail work across page transitions. The detail owner holds one latest tile and runs one operation at a time. New source rectangles reuse acquired bytes.

The page retains one current detail bitmap, at most 2048 edge/16 MiB, in addition to the existing base. A completed improving whole-source fallback remains owned at fit and is shown again on rezoom, until that source/lifecycle retires. Replacement can briefly hold old and new bitmaps. Results produced during cancellation are recycled; page/source disposal also releases the latest assigned pixels. A platform region refusal triggers at most one whole-source fallback attempt per detail session. The fallback is displayed only when its pixel budget improves the base.

## Memory and device qualification

Application memory includes every concurrent source, bitmap and worker allocation. Budget the original host byte array (up to 64 MiB), source transfer/staging and worker input, base preview (2560 square is 25 MiB), old/new detail copies (up to 16 MiB each), shared output, worker region intermediates and any retained neighbour-page previews. A whole-source fallback may reserve a 4096-square shared output capacity even though its returned pixels are limited to 16 MiB. Cancellation/page transitions can temporarily overlap an old input with the current source until the decoder retires. Existing avatar/banner caches, UI, animations and the rest of the app are additional consumers.

The conservative additional path envelope can therefore be several hundred MiB. Qualifying the complete path requires the device measurements below. Measure peak Java/native/worker RSS or proportional memory, source/decode counts, descriptor count and pinch/pan latency on the exact signed candidate, including maximum admitted sources, fallback and repeated page/background/account transitions. Keep measured values and source/APK identity with release evidence. Device measurements and visual qualification remain pending.

## Aspect and animation retirement

The decoded base is filled into the original source's fitted rectangle. Fractional fit dimensions are preserved through layout layers, including extremely thin images whose unzoomed height is below one device pixel. Base, vector and raster detail therefore share one source-to-viewport transform even when preview dimensions were rounded during sampling.

A healthy animation retains its session and clock through pause/resume. The existing `WorkerLostUnknown` terminal reason carries system/Binder-confirmed worker death while preserving uncertainty about its cause; it permits one automatic replacement for the same current source owner. The allowance persists across zoom and background/foreground transitions. Subsequent failures expose the existing Retry action. `ProtocolRejected` and `TransportUnavailable` preserve their first reason and require explicit Retry through normal animation admission, which still waits for confirmed physical retirement. These reasons alone supply no automatic recovery authority or death proof.
