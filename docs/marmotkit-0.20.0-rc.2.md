# MarmotKit 0.20.0-rc.2 on Android develop

Android develop pins MDK commit `a3228a2c572f2c91bbc5948160bbe890f20888a2` via the [published 0.20.0-rc.2 Android archive](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.20.0-rc.2). The archive checksum, source and builder commit agree with the [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/45917991806).

The manifest omits the feature list. The [arm64-v8a](https://github.com/marmot-protocol/mdk/actions/runs/45917991806/job/140265131054), [armeabi-v7a](https://github.com/marmot-protocol/mdk/actions/runs/45917991806/job/140265131088), [x86](https://github.com/marmot-protocol/mdk/actions/runs/45917991806/job/140265131000), and [x86_64](https://github.com/marmot-protocol/mdk/actions/runs/45917991806/job/140265131168) build logs record both exporter flags enabled. The [pinned build script](https://github.com/marmot-protocol/mdk/blob/a3228a2c572f2c91bbc5948160bbe890f20888a2/crates/marmot-uniffi/kotlin-bindings.sh) passes them to Cargo as `otlp-export` and `product-analytics-export`.

The binding keeps the agent item and timeline placement APIs from the November 26 nightly. The new inbox-list record and relay check method change the onboarding and relay-health bindings. Android displays the new relay rate-limit finding and supplies the retention fields in reply-preview fixtures.

The [November 26 qualification record](marmotkit-nightly-20261126.md) covers the older artifact. The 0.19.0 upgrade to this pin includes migration 0227, which records expired invitations. Android's populated upgrade and recovery campaign must run again, including the relay behavior tracked in [MDK #3643](https://github.com/marmot-protocol/mdk/issues/3643). The production release requires a final Stable archive.
