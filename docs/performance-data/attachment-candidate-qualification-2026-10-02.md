# Attachment candidate qualification — 2026-10-02

This report covers the reduced landing scope of PR #3003. The broader #2779 plan remains open. All requests and response-body bytes below come from the controlled server ledger; retained-read checks do not infer HTTP behavior from a UI spinner.

The [review follow-up report](attachment-review-followup-qualification-2026-10-02.md) records the rebased Retry/progress/publication fixes and paired 32 MiB local-read measurements. Those targeted measurements do not close the broader large-file or manual qualification gaps below.

## Sources and method

- Android base: `f73b10968e168dc6bf37728a41753b992b5c8d80`; native fixture candidate: `fb076781d08d866d0a5e74a3cb2443831847d464`.
- Published MDK 0.12.0: `122bd90ffac60bb6311346e228d0f609a18521ee`; packaged arm64 native SHA-256: `073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`.
- Owned API 30 arm64 emulator: genuine Android controller send; 1,024 plaintext bytes / 1,040 ciphertext bytes; persistent local HTTP ledger; unavailable endpoint after full app-process restart; ten retained reads per direction with exact bytes and a different PID.
- Held-body runs send 1,024 of 1,040 ciphertext bytes without EOF, perform ten active joins, cancel, independently measure acknowledgement and socket disconnection, observe 30 seconds without acquisition, perform ten terminal ordinary joins, then one deliberate Retry as the positive control.
- API 36 arm64 emulator: matching isolated app/test APKs, production progress renderer at 200% font scale in RTL, spoken semantics, fixed 48 dp controls, pending Cancel disabled, explicit unconfirmed feedback; plus existing platform voice viewport and user-initiated job tests. These are automated platform checks, not manual TalkBack acceptance.
- Only `.medialatency` identities were installed in place; personal app data was not modified. No production endpoint was probed.
- The foundation [baseline and archive manifest](attachment-evidence-manifest-2026-10-01.md) remain separate. Baseline already passed zero repeat acquisition. No new bandwidth or cold-throughput improvement is claimed.

## Controlled Android results

| Session | Outcome | GET / upload requests | Ciphertext response bytes | Cold / restart read ms | Sampled peak Java / native MiB |
| --- | --- | --- | --- | --- | --- |
| Play-cancel | PASS | 2 / 1 | 2064 | — / — | 15.59 / 86.54 |
| Play-restart | PASS | 1 / 1 | 1040 | 271.441 / 8.080 | 15.39 / 85.10 |
| Zapstore-cancel-diagnostic-2 | PASS | 2 / 1 | 2064 | — / — | 15.69 / 85.80 |
| Zapstore-cancel | FAIL | 0 / 0 | 0 | — / — | 0.00 / 0.00 |
| Zapstore-restart | PASS | 1 / 1 | 1040 | 270.575 / 7.441 | 15.44 / 85.77 |

The successful restart sessions each used one acquisition; every retained read and both directions after process restart added zero requests/bytes. The held runs intentionally counted two acquisitions: interrupted body and deliberate Retry, totaling 2,064 response bytes. Play acknowledgement/disconnect: 24.544/37.054 ms; diagnostic Zapstore repeat: 22.238/32.488 ms. Both retained the 5-second acknowledgement and disconnect limits and 30-second quiet window.

The initial Zapstore cancellation session failed before instrumentation with an ADB command error; it contains no native or HTTP samples. Its missing API/ABI budget failure is a consequence of that setup failure. The exact command failure was not captured. The diagnostic repeat passed, so the original remains FAIL, not a proven flake or a qualification pass.


Final native rerun at `cbe7efff9a197ff7bf0290985902bfe67a454b56` passed all four sessions. The intervening production change only adds coroutine cancellation checks to video recovery. The same unchanged budgets and independent ledger apply:

| Session | Outcome | GET / upload requests | Response bytes | Cold / restart ms | Ack / disconnect ms |
| --- | --- | --- | --- | --- | --- |
| Play-cancel | PASS | 2 / 1 | 2064 | — / — | 21.455 / 43.915 |
| Play-restart | PASS | 1 / 1 | 1040 | 279.198 / 8.021 | — / — |
| Zapstore-cancel | PASS | 2 / 1 | 2064 | — / — | 22.615 / 26.157 |
| Zapstore-restart | PASS | 1 / 1 | 1040 | 274.087 / 6.208 | — / — |

Together, the two emulator native cohorts contain nine attempts: eight PASS, one preserved pre-instrumentation FAIL. The final four-pass rerun does not relabel the original failure.

## Performance budgets and failures

API 30 reference limits remain 1,500 ms cold, 150 ms local, 32 MiB Java and 128 MiB native. Passing emulator runs satisfy these unchanged limits. No p95 or production SLO is inferred from this small sample.

Earlier Pixel API 37 testing comprised 16 sessions: 13 PASS and 3 FAIL. One Play restart failed before instrumentation; one Zapstore cancellation stopped before the cancellation marker; one Zapstore restart preserved exact bytes and zero repeat acquisition but took **912.150 ms**, exceeding the unchanged 150 ms local ceiling. Four subsequent header-wait sessions passed. Those later passes do not erase the outlier or qualify large files, the full fault matrix or physical manual flows. Pixel measurements belong to earlier `41d46ec815266427cfa5a8cd4b6fa5e231746a4d` / `a89f3f19a76634229b58abd155749622023055a8` candidates, not this final review head.

## Host and platform validation

- 280 focused unit/Compose tests per flavor; 39 fixture host tests; native published-pin suites: 10 promotion/budget tests and 21 checkpoint/resume tests. Host and native Rust results are not Android device lifecycle evidence.
- Fast completion gate includes both debug compilations, Android-test compilation, ktlint, detekt, Zapstore lint and focused Play Roborazzi verification. Four progress PNGs and the changed timestamp PNG are tracked.
- Full local Play curated suite: 1,275 tests, 14 screenshot failures in four unrelated classes. The same 14 failed on exact master in the same configuration, with byte-identical actual PNG SHA-256 values. Changed attachment snapshots and the voice retry regression pass. Hosted full-matrix CI remains the authority for the Linux renderer. No baseline was rerecorded to suppress these failures.
- Previous candidate CI failed the same voice retry wait in both flavors and both unit/screenshot jobs. The test now uses the existing bounded Compose-frame/main-looper/idle synchronization helper; no timeout increase or behavior assertion removal.
- Three additional host regressions fence an already-claimed external launch, preserve per-file cancellation isolation and invalidate old gestures across navigation. A real Android opener test delays disk persistence and requires zero platform dispatch; both flavors passed. Both new platform classes are registered for PR device smoke and in the required full-suite case inventory.
- A self-review found asynchronous cancellation cleanup did not fence an already-claimed launch. The fix captures a per-file gesture guard, revokes it immediately, prevents late restoration and prevents unrelated taps from rescuing cancelled intents. The first focused run exposed a legacy source-wiring assertion; it now checks the captured guard while the behavioral handoff assertions remain. Intermediate formatting failures remain preserved.
- Hosted unit suites at `1cce4b3dc05dc1bd382cd3fa59e4c2ea21d9b486` each failed one source-policy test: its inventory still named the removed global `openRequests` guard. The inventory now checks the actual per-file `dispatchLifetimes` and shared `userActions` guards. All four staleness coverage tests are included in focused validation; no production behavior or guard requirement was removed. The complete failed CI logs remain preserved.
- First platform progress assertions failed due to floating-point Dp conversion (47.999994 dp for a 48 dp control). The reusable test now compares rounded physical pixels; both matching-flavor runs pass. The failing runs and diagnostic mismatched-flavor run remain preserved and are excluded from passing qualification.

## Artifact provenance

| Artifact | SHA-256 |
| --- | --- |
| Native fixture Play-app | `1936739a0becff9a8beaeef242e2ad1ad6c43be27132833baa9f795ea87ddcce` |
| Native fixture Play-test | `005f88b23d65d2a77306c58cab7185f1c3e72c3a7207eddd0cec8e6793cd457d` |
| Native fixture Zapstore-app | `40e858957225fac3641ad8a24b47e086422ffc6e3836e344e7d7c379c356a4d4` |
| Native fixture Zapstore-test | `69f1d2a1ebd96b51efc5b8eb4f95785c9667ece56827b18cfa921e2a2d241135` |

Platform renderer test overlay SHA-256: `c7b7cd3f6c919b4eb5f469a2b1103ae1a5f87e9a7753d0133b9b3913ba3fb7e6` over `fb076781d08d866d0a5e74a3cb2443831847d464`; the same file bytes are committed in `bfff6b57f` (test-only synchronization commit). App source is unchanged.

| Platform artifact | SHA-256 |
| --- | --- |
| Play-app | `1936739a0becff9a8beaeef242e2ad1ad6c43be27132833baa9f795ea87ddcce` |
| Play-test | `b4dcf70d196c68e5090f00e360b144b7be88391a6e8b04823b306c7c808de2ac` |
| Zapstore-app | `40e858957225fac3641ad8a24b47e086422ffc6e3836e344e7d7c379c356a4d4` |
| Zapstore-test | `b02a47c86a518d85de1de570048e4c6d599b204c51e0b39b1e783170b3636a5e` |

## Landing scope and deferred outcomes

| Changed boundary | Qualification |
| --- | --- |
| Join / deliberate Retry / worker observation | Host boundary regressions, published-pin promotion and retry-budget/backlog tests; packaged-native active and terminal joins. No reset-capable call from ordinary joining. |
| Native phase / byte presentation | Reducer/throttle/subscription-owner tests, both-flavor committed snapshots and API 36 production-renderer semantics/control checks. |
| Cancel ordering and delivery fencing | Host ordering/open/APK race regressions plus packaged-native acknowledgement, independent held socket disconnection and 30-second quiet window. |
| Genuine outgoing retention / local access | Genuine controller send, native leases, exact bytes after process restart, unavailable endpoint, ten reads per direction and zero repeat acquisition. Small-file scope only. |

Full manual MED-009/MED-017, TalkBack, seven-file Android UI admission traces, Android worker interruption with Range, account lifecycle invalidation, large received APK, >64 MiB assets, representative throughput/memory qualification and the full fault matrix remain unqualified. Native checkpoint tests do not qualify Android JobScheduler behavior. Agent sender remains dependent on Danny’s #2106; no agent-specific implementation or completion is claimed. Share import, forwarding, native allocation/streaming redesign and real-endpoint probing remain separate. These gaps prohibit full-plan or issue-closure claims.

All prior failures, raw ledgers, logs and APK provenance are retained privately in the workspace evidence directories. This concise report does not claim that those new private sessions have been published in the foundation archive. No raw session JSON was removed from Git by this PR.

Final-code native APK provenance:

| Artifact | SHA-256 |
| --- | --- |
| Play-app | `1624f6508110d101abe0654adaa2c8b0f7c85527cadccd4ffc8940bf5c549667` |
| Play-test | `b4dcf70d196c68e5090f00e360b144b7be88391a6e8b04823b306c7c808de2ac` |
| Zapstore-app | `c502b8b7094ec2b872143de42e7a825dc82ecaad8d305f84b81eef4fdc1f20cb` |
| Zapstore-test | `b02a47c86a518d85de1de570048e4c6d599b204c51e0b39b1e783170b3636a5e` |

Final registered platform tests passed **2 tests per flavor**. These APKs were built over `cbe7efff9a197ff7bf0290985902bfe67a454b56` with the exact source overlays below; all overlay file hashes were verified before committing. Documentation and required-case registration do not change runtime behavior.

| Platform artifact | SHA-256 |
| --- | --- |
| Play-app | `8d921ddfa999a490ca867c61031c796afe56f66dde027f41f54f74d0a335a66f` |
| Play-test | `b8951667b09d2a746ab7f325245e80f6bf0126153040c4ad11488cbb2539e246` |
| Zapstore-app | `160552a5916478656ea5b8d45195b6d8a1489203d3a3b216e066622fc8f9df1a` |
| Zapstore-test | `7ae6add085b981a194d98c638c127b912764ac0e925eb0d18a740e5de3e45523` |

| Tested source overlay | SHA-256 |
| --- | --- |
| `app/src/androidTest/java/dev/ipf/whitenoise/android/media/AttachmentProgressDeviceTest.kt` | `6d5e4d6bdf901bc9c0a16b2e8f03fc0a1bf9802d977959b2b24f54129149c16b` |
| `app/src/main/java/dev/ipf/whitenoise/android/state/AttachmentOpenCoordinator.kt` | `f9ae1386dfe12ebe1dd886d31a15ad1aa83b6de83535c8f79750d42ae9c4b06f` |
| `app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaFileBubble.kt` | `109a2c266d7264359a3e41fc93b54f6aa31b0b3353845c96f5c7f650c4d99fe5` |
| `app/src/test/java/dev/ipf/whitenoise/android/state/AttachmentOpenCancellationTest.kt` | `e1e24b6c52ba4aac6747c8f241cfa97e588afd2de154efc1392859a61ca67156` |
| `app/src/test/java/dev/ipf/whitenoise/android/ui/conversation/media/ReceivedApkAttachmentOpenIntegrationTest.kt` | `2bedcfdd8336efb1b9cb1484fe3133f8dd5689129443d52bda926b9f25e59ef4` |
| `app/src/androidTest/java/dev/ipf/whitenoise/android/media/AttachmentOpenDispatchDeviceTest.kt` | `219a0e509bc9975451e0c605a67eb6a4a33a8916f22f6fa08eb2e9d30b8bc2c9` |
