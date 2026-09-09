#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 || ! -f "$1" || ! "$2" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'error: expected an AAB path and pinned SHA-256 upload certificate fingerprint' >&2
  exit 1
fi

verification_status=0
jarsigner -verify -strict "$1" >/dev/null 2>&1 || verification_status=$?
# Android upload certificates are self-signed: strict status 4 reports their
# untrusted certificate chain. Permit that status only, with the pinned identity
# check below. Reject unsigned entries (16), invalid signatures, and other errors.
case "$verification_status" in
  0|4) ;;
  *) echo "error: Play App Bundle signature verification failed (status $verification_status)" >&2; exit 1 ;;
esac

fingerprint="$(keytool -J-Duser.language=en -J-Duser.country=US -printcert -jarfile "$1" |
  sed -n 's/^[[:space:]]*SHA256: //p' | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
if [[ ! "$fingerprint" =~ ^[0-9a-f]{64}$ || "$fingerprint" != "$2" ]]; then
  echo 'error: Play App Bundle signer does not match the registered upload key' >&2
  exit 1
fi
printf '%s\n' "$fingerprint"
