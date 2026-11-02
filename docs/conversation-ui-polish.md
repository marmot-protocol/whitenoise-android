# Conversation identity, destinations and notifications

The composer shows its bound sending account only when multiple signing accounts are signed in. Forward destinations use the same row component as Home with selection-only actions.

Notification switches retain the existing per-account/chat alert preferences. Tapping the category opens its existing Android channel. The on/off switch is deliberately separate from routing inheritance; existing default/custom routes are preserved.

Public JPEG previews are an explicit per-account opt-in, off by default. They require unmetered Wi-Fi and permitted photo auto-downloads. Only direct HTTPS SHA-256 resource paths (extensionless, .jpg or .jpeg) qualify. MDK owns actual dialing, public DNS/address checks, HTTPS redirects and byte-bounded transfer through the existing image downloader. The host verifies the content hash, static JPEG envelope and isolated inspection bounds before isolated decoding. No general page discovery or HTML rendering occurs. Limits: two candidates per message, one active worker, 1 MiB source, 4096-pixel maximum edge, four million source pixels, 1024-pixel preview edge, 15-second host deadline, 16 MiB decoded-pixel LRU and 128 five-minute failure entries. There is no new disk cache or protocol-message store. Ordinary links always remain available.

Cancellation and result acceptance are bound to the current account, runtime, message document, consent and started screen lifetime. Turning previews off revokes admission and clears the rendered decoration. Account and privacy controls do not affect manual link opening or encrypted attachments.
