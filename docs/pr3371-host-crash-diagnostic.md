# PR 3371 host crash diagnostic

This branch diagnoses the hosted QEMU crash without changing PR 3371's application
candidate. It is not an adoption candidate and must not be merged. The preview
driver still verifies the trusted builder, signer, package, native bytes and every
signed ZIP entry for `e701aee19f969298f977bd9df31ac866676bb501`. The report separately
records this diagnostic workflow source and `diagnostic_only: true`.

Keep emulator 37.1.11/build 15917651, API 34, resources, both APKs and UI
assertions fixed. The initial observations used legacy `swiftshader`; the controlled
comparison below changes only renderer selection. A GDB sidecar attaches only after boot to the observed AVD PID,
after verifying `/proc/PID/exe` resolves to the SDK's real QEMU binary. The driver
waits for a resume receipt before application operations. Raw GDB output is
discarded; startup files, auto-loading and debuginfod are disabled. At a fatal-type
signal stop, retain at most 24 explicit faulting-thread function names, PCs and
module basenames. No arguments, locals, register dumps, core files, raw Crashpad
databases or target memory are retained.

Routine SIGUSR1/SIGUSR2 signals pass through without ending observation. Other
nonfatal stops resume until a fatal-type signal or debugger cleanup. Unknown
module ownership stays null; it is never guessed to belong to QEMU. Hosted
controls include SIGUSR1 before SIGSEGV and progress after debugger cleanup.

Run 39627518249 captured SIGSEGV and the parent confirmed actual termination,
but stripped symbols left the fault location unresolved. Each frame now records
only its containing `/proc/PID/maps` mapping's basename, start, file offset and
permissions (or null when unmapped). A PC in the main executable also records
that executable's SHA-256. These fields allow static analysis of the published
SDK binary without collecting a memory dump or guessing that an unknown PC is
inside QEMU. No complete mapping list or host paths are retained.

The original stopped thread receives its pending signal when GDB detaches. A
signal stop is not termination evidence: the unchanged parent process observer's
`Popen.wait()` result remains authoritative. Hosted synthetic child tests prove
both unhandled SIGSEGV -> -11 and handled SIGSEGV -> exactly one delivery and normal
continuation. Debugger attach and cleanup are bounded; no physical device is used.

Relevant primary documentation: [GDB attach](https://sourceware.org/gdb/current/onlinedocs/gdb.html/Attach.html),
[frame fields](https://sourceware.org/gdb/current/onlinedocs/gdb.html/Frames-In-Python.html),
[signal delivery](https://sourceware.org/gdb/current/onlinedocs/gdb.html/Signaling.html).
After a targeted repair, the exact signed APK still needs a full upgrade and cold
restart without a debugger before physical follow-up or review readiness.

The separate `attachment_diagnostic` dispatch runs only the unchanged first two
attachment scenarios after hosted failure-contract tests. It classifies reverse
binding failures with fixed categories and binding roles, retaining no raw ADB
errors or ports. This partial campaign cannot replace complete attachment CI.

## Explicit ANGLE comparison

Run 39628873246 confirmed host SIGSEGV at a PC in an executable mapping with no
retained filesystem module. The remaining unmapped frames are failed unwinding,
not reliable callers. Legacy SwiftShader GLES 4.0.0.1 was active. This supports a
graphics/JIT hypothesis but does not establish its owner or root cause.

One comparison now selects `-gpu swangle` (ANGLE with SwiftShader), retaining
`-feature -Vulkan` and every other setting. Inspect startup renderer/ICD logs to
confirm the requested backend actually ran; fallback does not test this hypothesis.
The previous `software` comparison selected Lavapipe on emulator 37.2.12 and is
not this controlled comparison. See the official [renderer definitions](https://developer.android.com/studio/run/emulator-acceleration#command-gpu).
Bounded mapping metadata distinguishes file, anonymous and special mappings,
retaining only the exact known `swiftshader_jit` anonymous/memfd label and no
arbitrary anonymous labels. Missing labels remain inconclusive. Anonymous mapping offsets are not presented as file offsets. This remains
diagnostic-only even if it passes; normal qualification without GDB is still required.
