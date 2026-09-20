# Attachment worker non-retention (#2686)

`downloadAttachmentForDurableWork` returns false only after the plaintext source
has completed without throwing and the final cache-availability check is false.
Examples include a body exceeding the encrypted cache's entry limit or eviction
before that final check. This is a completed-but-unretained result, not evidence
of a transient transport failure. It must not be recorded as successful acquisition.

The worker logs `durable_attachment_download_not_retained` and avoids the former
automatic network retry. It preserves the first attempt's 30-second unique-work
deduplication window by suspending before returning failure: immediate failure
would let `ExistingWorkPolicy.KEEP` accept another generation sooner. An already
exhausted retry has no extra wait, matching the former retry limit. Coroutine
cancellation interrupts the wait normally.

Interactive scheduling intent is cleared before waiting. A deliberate request
that joins during the wait can set it again; that fresh intent retains a durable
retry, while its foreground caller can already fetch. Automatic enqueues cannot
set that flag. No success ledger or artificial cancellation marker is persisted.

`AttachmentDownloadKeepWindowTest` exercises the actual unique WorkManager work
with virtual time and synthetic download callbacks: repeated automatic enqueues
retain one worker for the full window without another body, cancellation remains
effective, and a joining explicit request retains its safety-net download.
`AttachmentDownloadWorkerClassTest` checks terminal outcomes, exhausted retries,
transient failures, and explicit subsequent requests. These are worker tests,
not full-app or device bandwidth measurements.

This is a finite scheduling safeguard, **not a fix for the cache-eviction loop**.
The hold is in-process: interruption/process loss can still cause WorkManager to
re-execute its unfinished request. After completion, another automatic generation
can be admitted. Foreground downloads also run independently of this worker.
Durable eligibility across navigation, source changes, and process restoration
belongs to the authoritative acquisition owner; this patch does not supply it.

The broader incident and acquisition migration remain tracked in
[#2686](https://github.com/marmot-protocol/whitenoise-android/issues/2686) and
[#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045).
Historical bandwidth attribution remains unconfirmed. No full-app seven-file
after-fix result is claimed.
