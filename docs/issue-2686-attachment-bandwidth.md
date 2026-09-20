# Attachment worker non-retention (#2686)

`downloadAttachmentForDurableWork` returns false only after the plaintext source
has completed without throwing and the final cache-availability check is false.
Examples include a body exceeding the encrypted cache's entry limit or eviction
before that final check. This is a completed-but-unretained result, not evidence
of a transient transport failure. It must not be recorded as successful acquisition.

The worker logs `durable_attachment_download_not_retained`, clears interactive
scheduling intent, and immediately returns failure for either automatic or
interactive work. It neither holds a worker slot nor schedules another body
transfer. Genuine transient transport exceptions retain the bounded retry;
a deliberate subsequent request remains possible.

The foreground coordinator publishes sticky `NotRetained` for this outcome,
and the bubble's automatic-download predicate accepts only `Remote`. Thus a
composed row does not automatically re-arm solely because of non-retention.
This differs from successfully retained bytes later being evicted: an
`Available` row can become `Remote` and automatically request those bytes again.

`AttachmentDownloadWorkerClassTest` verifies one completed body and immediate
failure without a retry or artificial wait, for both scheduling priorities.
It also covers exhausted retries, transient failures, cancellation, and explicit
subsequent requests. These are component tests, not full-app bandwidth measurements.

This is a partial worker fix, **not a fix for the cache-eviction loop**.
Coordinator state can retire when observers leave. Later automatic generations,
including notification-driven requests, can still be admitted; process loss
can re-execute unfinished work. No Android-owned acquisition ledger or cooldown
is added. Durable eligibility across navigation, source changes, and process
restoration belongs to the authoritative acquisition owner.

The broader incident and acquisition migration remain tracked in
[#2686](https://github.com/marmot-protocol/whitenoise-android/issues/2686) and
[#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045).
Historical bandwidth attribution remains unconfirmed. No full-app seven-file
after-fix result is claimed.
