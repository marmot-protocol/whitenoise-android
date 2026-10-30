# Still-image worker lifetime

Production installation uses domain-bound worker generations (#3373), switched
pending approval of their proposed budget. The facade keeps one process-owned
`IsolatedMediaTransport` across clear, install and account reset, so a held
generation survives facade replacement.
Each session has its own source access, refusal ledger and epoch; the ledger
holds its own lock, as every domain lane checks and records refusals at once. No in-process
decoding fallback exists. [Media containment](media-containment.md#domain-bound-worker-generations)
states the contract; this page records the owner, deadline and test boundaries.

## Owner and ordering

`MediaWorkerPool` owns generations, `IsolatedMediaTransport` owns requests.
The transport reads each request's `MediaTrustDomain` from `MediaTrustScope`
on the calling thread, before any thread hop. One request per domain is
admitted at a time; Unique requests share one lane. An admitted request claims
its worker before creating any descriptor, so waiting requests hold none. A claim for a domain takes
that domain's idle generation, waiting for a retired one's Binder death first,
or binds a clean connected spare or a fresh binding to the domain for life,
evicting the least recently used idle domain through its death when every slot
is taken. A Unique claim also waits for the previous Unique generation's death.
The parent registers its exact death recipient before admitting input and
output descriptors. After dispatch it warms a spare. A terminal reply revokes
that request's reply capability. A domain's generation then returns to its
domain and the pixels are copied; a Unique generation is sent RETIRE and the
parent waits for its Binder death before reading shared pixels. A revoked or
failed reply retires the generation; the next claim still enforces its death.
A dispatched request without a valid result quarantines its source bytes for
the existing 60-second cooldown, unless the caller was interrupted or the
session was reset. A domain's generation is replaced after 64 inputs and
retired after 30 seconds idle.

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
starting spare while a slot is free. Live generations never exceed three.

A session reset captures the generations that predate it, revokes the pending
reply and retires them, clean spares included; a generation started for the new
session is never swept up by a slow old-source close. A request admitted before
reset may transfer its descriptors; its result is discarded. Standalone close is
terminal: it unbinds every generation and releases callback resources after
the last death. Animation and platform audio/video keep their own owners.

## Output and threat boundary

The existing extent checks, shared-memory mapping and copy into a parent-owned
bitmap remain the output path. For a Unique input the death barrier precedes
that copy; a domain's own worker may still write its domain's pixels while they
are copied. The bitmap supplies bounded private bytes to downstream code, which
treats every pixel value as hostile.

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
that ignores RETIRE, spare bounds and fail-closed replacement.
`MediaWorkerDomainTest` adds domains: a generation never serves another
domain, even with more concurrent domains than slots; same-domain reuse;
cross-domain admission while another domain's request is in flight, with only
requests that hold a worker holding descriptors however many domains wait;
eviction
only through the victim's death; failure retirement and quarantine per domain;
the input cap and idle timeout; account reset; Unique staying single-use; and
failing requests in four domains at once keeping every quarantine entry, its
bound and no exception. `MediaRefusalLedgerTest` hammers the session's refusal
ledger from eight threads; without its lock it throws. `PictureDomainCoverageTest`
and `PictureDomainMappingTest` check that every production picture names its
domain and that only member-pinned bytes reach a conversation's worker;
`PhotoEditorDomainTest`, `PhotoDraftStagerTest` and `PhotoEditorCommitterTest`
check that every decode of one pick, edited or not, runs in that pick's own
outgoing worker and never another pick's, also under concurrency, and
`ConversationDraftRestorationPlanTest` that each restored draft attachment is its
own pick. `MediaWorkerDomainTest` adds a ten-pick concurrency canary, parallel
eviction for an album of new picks, and the retirement of an idle or busy pick's
domain. Those JVM fixtures exercise owner ordering only.

`IsolatedMediaDecoderDeviceTest` supplies the operating-system evidence on a
real device. A debug worker-state canary shows source A's retained digest and
duplicated descriptor are absent from source B's worker, and A's process is
gone (`ps`). Each input's DECODE is sent only after its predecessor's Binder
death, recorded by an independent witness. Debug fixtures that ignore RETIRE,
after a reply or while abandoned mid-request, hold the next input until Android
stops them, and an abandoned one is stopped well before its own watchdog even
when no request follows. Android records those exits as `isolated not needed`,
not as a self-exit.
`MediaWorkerDomainDeviceTest` uses the same canary across domains: a
conversation's later input lands in the worker holding its earlier input's
state, and another conversation's input lands in a different process holding
none of it. Its per-pick case does the same for two picks of one conversation,
then retires the first pick and shows its next input in a fresh process holding
nothing. These cases ran on API 30 and API 36 emulators and run in the hosted
API 34 pull-request lane. MED-031 and MED-032 keep their image, account-switching and
format checks.
