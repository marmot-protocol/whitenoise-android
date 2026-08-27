#!/usr/bin/env bash
set -euo pipefail

apk=${1:?staging APK path is required}
max_apk_bytes=52428800

if [[ ! -f "$apk" || -L "$apk" ]]; then
  printf 'Staging APK must be a regular non-symlink file: %s\n' "$apk" >&2
  exit 1
fi
if [[ $(stat -c '%s' "$apk") -gt "$max_apk_bytes" ]]; then
  printf 'Staging APK exceeds the transport-safe ceiling of %d bytes.\n' "$max_apk_bytes" >&2
  exit 1
fi

[[ $(apkanalyzer manifest application-id "$apk") == dev.ipf.whitenoise.android.staging ]]
binary_manifest=$(apkanalyzer manifest print "$apk")
grep -Fq 'android:extractNativeLibs="true"' <<< "$binary_manifest"

python3 - "$apk" <<'PY'
import re
import sys
import zipfile

path = sys.argv[1]
with zipfile.ZipFile(path) as archive:
    corrupt = archive.testzip()
    if corrupt is not None:
        raise SystemExit(f"Staging APK contains a corrupt ZIP entry: {corrupt}")
    native = [
        item
        for item in archive.infolist()
        if re.fullmatch(r"lib/[^/]+/[^/]+\.so", item.filename)
    ]
    abis = {item.filename.split("/", 2)[1] for item in native}
    if not native or abis != {"arm64-v8a"}:
        raise SystemExit("Staging APK must contain only arm64-v8a native libraries.")
    if any(item.compress_type == zipfile.ZIP_STORED for item in native):
        raise SystemExit("Staging APK contains an uncompressed native library.")
PY

printf 'transport-safe staging APK verified: %s\n' "$apk"
