# PR 3371 host crash diagnostic

This branch diagnoses the hosted QEMU crash without changing PR 3371's application
candidate. It is not an adoption candidate and must not be merged. The preview
driver still verifies the trusted builder, signer, package, native bytes and every
signed ZIP entry for `e701aee19f969298f977bd9df31ac866676bb501`. The report separately
records this diagnostic workflow source and `diagnostic_only: true`.

Keep emulator 37.1.11/build 15917651, API 34, renderer, resources, both APKs and UI
assertions fixed. A GDB sidecar attaches only after boot to the observed AVD PID,
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
