#!/usr/bin/env bash
# Install independent visibility fixtures only on the disposable hosted emulator.
set -euo pipefail
[[ "$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo "Share fixtures require an emulator" >&2
  exit 1
}
./scripts/outbound-share-fixture/build.sh
adb -e install -r -t scripts/outbound-share-fixture/build/fixture.apk
adb -e install -r -t scripts/outbound-share-fixture/build/control.apk
