# Still-image worker lifetime candidate

Production installation retains the existing reusable isolated worker. Per-input
retirement is an off-default experimental transport, selected only by the debug
`installForLifetimeQualification` seam or direct qualification fixtures. The
facade preserves that explicit selection across account reset; normal install
always selects the reusable transport. No in-process decoding fallback is added.

Each admitted still-image operation uses one binding generation. The parent
registers its exact Binder death recipient before admitting input and output
descriptors. A terminal reply revokes the reply capability, requests retirement
and waits for that Binder death before reading shared pixels. Another input
remains held until the generation has a death witness or Android definitively
refuses its bind.

Binding, reply and retirement share one eight-second host deadline after the
existing provider-staging phase. The worker retains its six-second watchdog.
Acknowledgements, disconnection callbacks, null bindings and expired deadlines
all retain the generation. Accepted startup without a Binder can hold the image
lane until app-process restart. Cooperative retirement improves recovery; the
admission fence remains effective when a worker ignores it.

The facade keeps one real transport owner across clear, install and account
reset. Each session has its own source access, refusal cache and epoch. Reset
revokes pending replies and retires the captured old worker. Late callbacks
remain bound to that worker. A request admitted before reset may transfer its
descriptors; its result is discarded after session invalidation. Standalone
transport close is terminal and releases its callback resources after observed
death. Animation and platform audio/video retain their existing separate owners.

## Output and threat boundary

The existing extent checks, shared-memory mapping and copy into a parent-owned
bitmap remain the output path. The death barrier precedes that copy. The bitmap
supplies bounded private bytes to downstream code, which treats every pixel
value as hostile.

A Binder death witness identifies the hosting process. Forked descendants and
process-group cleanup remain outside that witness. A compromised descendant
could retain a writable mapping during the copy; pixel atomicity and authenticity
are outside this contract. The copied bitmap remains private after the copy.
Android service isolation, descriptor bounds and permission checks remain
required. This candidate addresses the still-worker lifetime portion of #3373;
the other attachment-security issues retain their separate acceptance gates.

## Validation still required

`MediaBindingRecoveryTest` controls callbacks through the real transport for
reply retirement, reset, terminal close, missing and late connections, duplicate
death callbacks and old-source cleanup races. Those JVM fixtures exercise owner
ordering. Hosted instrumentation supplies the operating-system evidence.

`eachStillInputWaitsForItsExactBinderDeathBeforeReturningPixels` checks the exact
Binder is dead at return, records its independent death callback and verifies
pixels across two binding generations. Sequential Android UIDs may be reused;
the witness follows the exact Binder and generation.

Hosted API 30 and current-API containment campaigns, the corrected 100-operation
corpus and matched-source latency measurements remain pending. Historical reuse
measurements and the failed 84/100 diagnostic dataset remain separate. Default
adoption requires review of those results. The existing MED-031 and MED-032
manual cases retain their image, account-switching and format checks.
