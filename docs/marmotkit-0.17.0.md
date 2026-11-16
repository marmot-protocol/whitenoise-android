# MarmotKit 0.17.0 on Android

Android consumes the immutable `marmotkit-v0.17.0` bundle built from MDK
`4df811aea99269b437a6023be9446a5141e93cbe`. The Android archive SHA-256 is
`d260ecd9470892dfd692d5f29fd297627ad195cd86c43dcf2ae7d036708b9761`.
The downloaded archive matches the sibling checksum. Its embedded manifest
confirms identical source and builder commits, workspace 0.17.0 and Android
API 26. Inspection verified the native payload and ELF metadata for all four
ABIs. [Published bundle](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.17.0)

The manifest omits its feature list. The successful publishing run shows
`OTLP_EXPORT=1` and `PRODUCT_ANALYTICS_EXPORT=1` in the Android build jobs for
each ABI and Kotlin generation. The workflow and binding script at the
manifest's builder commit pass both settings to Cargo. The generated surface
has 470 public types, 479 UniFFI checksums and five helper declarations. The
previous bundle had 463 types and 477 checksums. The changes include create and
invite results, the audit source setter, commit refusal status and discarded
send results. [Publishing run](https://github.com/marmot-protocol/mdk/actions/runs/43701829344) · [Build workflow](https://github.com/marmot-protocol/mdk/blob/4df811ae/.github/workflows/bindings.yaml) · [Binding build script](https://github.com/marmot-protocol/mdk/blob/4df811ae/crates/marmot-uniffi/kotlin-bindings.sh)

Android uses the group ID or invite summary to tell whether an operation was
accepted. It applies the returned roster and tells the reader which recipients
MDK skipped. An unavailable prepared-image member report reopens the group that
already exists. Audit records get their source from the new setter, and existing
consent still controls v5 delivery. The removed group-read timer is omitted from
the Android diagnostics snapshot. [MDK integration guide](https://github.com/marmot-protocol/mdk/blob/v0.17.0/docs/integration/0.17.0.md)

The 0.17.0 source includes the four-download scheduler and native automatic
queue reprioritization. Android's own admission and visible-image behavior can
be measured against this pin as part of #3819. [MDK #3417](https://github.com/marmot-protocol/mdk/pull/3417) · [Android #3819](https://github.com/marmot-protocol/whitenoise-android/issues/3819)

The disposable hosted upgrade campaign starts from 0.16.0 at `a2e0558a`.
Account schema advances from 201 to 207 and the shared database stays at its
previous version. Downgrade is unsupported. The campaign exercises populated
upgrade, cold reopen, the original pending upload and both recovery relay
modes. See [INT-010](manual-release-testing/cases/INT-010.md) for manual checks.
