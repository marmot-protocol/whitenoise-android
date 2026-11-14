# Screen motion

Use the existing presentation owner for each surface. Launch geometry belongs to the activated control and to the current account, runtime and presentation. Keep it in transient UI state.

## Full screens

For retained Settings and detail routes, use `SettingsPageMorph` through `SettingsRouteMotion` or the existing `SettingsCardMorph` page mode. Pass the actual control bounds using `rememberMeasuredControlClick` or the existing measured Settings launcher. The route owner retains the origin for Back. A later unmeasured entry must retire the earlier origin.

For native full-screen dialog windows, use `MeasuredPlaybackDialog`. It normalizes screen coordinates in the destination window and keeps the window mounted through user dismissal. Toolbar Back calls the surface `dismiss`; its completion removes the caller's route. Forced source/account removal remains immediate and cancels accepted terminal actions.

Keep an explicit geometry-free fallback for external intents, deep links and unavailable or invalid bounds. Use a source rectangle only when the activated control supplied it. A moved or resized destination window invalidates an established origin.

Keep gesture-owned media viewers under their existing dismissal controller. Their thumbnail, pan, pinch and drag owner controls the presentation lifetime.

Chat/list transitions use `rememberConversationRouteTransition` and `ConversationRouteAnimatedContent`. One visibility clock owns drawing and outgoing-slot disposal; Back reverses its current value. Same-chat payload updates preserve the clock's progress. Key child content and retained origins to the transition owner so account/runtime replacement retires the old body. Keep chat pixels live throughout both directions.

## Popups and terminal actions

Use `PopupSurfaceMotion` through the shared alert or keyboard-safe popup boundary. It owns growth, visibility, input retirement and completion. A terminal action uses `close { ... }`; only the first accepted action runs, after exit completes. A replacement presentation cancels it.

Submission that can fail and leave a dialog open remains with the business owner. Busy dismissals must be rejected before starting exit. Bind destructive or consent callbacks to the exact owner and request accepted by the button; capture its selection before exit starts.

Preserve nonfocusable popup windows and IME ownership. The shared surface clock also controls native window animation.

## Safety and verification

Use `AppMotion` timing and easing tokens. Reduced motion settles without decorative delay. During page movement, reject touches that would land on displaced controls; exiting surfaces must not expose stale accessibility actions.

Exclude chat-list and transcript pixels from reverse page pictures. Live screenshot protection clears retained pictures in every enclosing morph owner. A missing safe reverse frame settles the parent directly.

Keep scroll and header geometry with the retained route owner, above child-route early returns. Verify real entry and Back, interruption, replacement, reduced motion, busy dismissal, large text and RTL. Inspect first, middle and settled frames; a test pass alone does not establish absence of device flicker.
