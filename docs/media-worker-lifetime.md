# Still-image worker lifetime

Production installation keeps the reusable worker. The debug
`installForLifetimeQualification` seam selects per-input worker generations
(#3373) until their proposed budget is reviewed. The facade keeps that one
process-owned `IsolatedMediaTransport` across clear, install and account reset,
so a held generation survives facade replacement.
Each session has its own source access, refusal cache and epoch. No in-process
decoding fallback exists. [Media containment](media-containment.md#per-input-worker-generations)
states the contract; this page records the owner, deadline and test boundaries.

## Owner and ordering

`MediaWorkerPool` owns generations, `IsolatedMediaTransport` owns requests.
One request is admitted at a time. Its claim waits until the previous
input-bearing generation has a Binder death callback, then takes a clean
connected spare or binds one. The parent registers its exact death recipient
before admitting input and output descriptors. After dispatch it warms the next
spare. A terminal reply revokes the reply capability, the parent sends RETIRE,
and it waits for that Binder death before reading shared pixels. A revoked or
failed reply skips that wait; the next claim still enforces it. A dispatched
request without a valid result quarantines its source bytes for the existing
60-second cooldown, unless the caller was interrupted or the session was reset.

Binding, reply and death share one eight-second host deadline after the
existing provider-staging phase, separate from the eight-second admission wait.
The worker keeps its six-second watchdog. RETIRE acknowledgements,
disconnection callbacks, null bindings, unbind requests and expired deadlines
never reopen admission. A worker alive 250 ms after RETIRE is unbound so
Android stops it, on the pool's own timer when no request is waiting; that call
is an escalation, not a witness. A binding Android
reports dead or null before it connects is released at once: it never held
input. An accepted startup that reports nothing keeps its slot; Android's start
timeout normally reports that binding dead, which releases it, and a late
connection becomes a clean spare. A claim waits at most one second on a
starting spare while a slot is free. Live generations never exceed one plus two
spares.

A session reset captures the generations that predate it, revokes the pending
reply and retires them, clean spares included; a generation started for the new
session is never swept up by a slow old-source close. A request admitted before
reset may transfer its descriptors; its result is discarded. Standalone close is
terminal: it unbinds every generation and releases callback resources after
the last death. Animation and platform audio/video keep their own owners.

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
required.

## Validation

`MediaWorkerGenerationTest` and `MediaBindingRecoveryTest` drive the real
transport and pool through controlled callbacks: per-request retirement,
distinct generations, late and revoked replies, refused and throwing binds,
startup timeout and late connection, death/close and reset races, a worker
that ignores RETIRE, spare bounds and fail-closed replacement. Those JVM
fixtures exercise owner ordering only.

`IsolatedMediaDecoderDeviceTest` supplies the operating-system evidence on a
real device. A debug worker-state canary shows source A's retained digest and
duplicated descriptor are absent from source B's worker, and A's process is
gone (`ps`). Each input's DECODE is sent only after its predecessor's Binder
death, recorded by an independent witness. Debug fixtures that ignore RETIRE,
after a reply or while abandoned mid-request, hold the next input until Android
stops them, and an abandoned one is stopped well before its own watchdog even
when no request follows. Android records those exits as `isolated not needed`,
not as a self-exit.
These cases ran on API 30 and API 36 emulators and run in the hosted API 34
pull-request lane. MED-031 and MED-032 keep their image, account-switching and
format checks.
