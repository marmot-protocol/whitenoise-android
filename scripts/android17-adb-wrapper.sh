#!/usr/bin/env bash

set -euo pipefail

real_adb="${ANDROID17_REAL_ADB:?ANDROID17_REAL_ADB must point to the SDK adb binary}"

# android-emulator-runner sends this command immediately after
# sys.boot_completed becomes 1. Android 17 can publish that property before
# its input service is registered, so retry only the runner's unlock command.
if [[ " $* " == *" shell input keyevent 82 "* ]]; then
  for attempt in {1..60}; do
    if output="$("$real_adb" "$@" 2>&1)"; then
      [[ -z "$output" ]] || printf '%s\n' "$output"
      exit 0
    else
      status=$?
    fi

    if [[ "$output" != *"Can't find service: input"* || $attempt -eq 60 ]]; then
      printf '%s\n' "$output" >&2
      exit "$status"
    fi

    if [[ $attempt -eq 1 ]]; then
      printf 'Android input service is not ready; waiting before unlock\n' >&2
    fi
    sleep 2
  done
fi

exec "$real_adb" "$@"
