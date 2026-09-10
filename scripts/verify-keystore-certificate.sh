#!/usr/bin/env bash
# Verify a public certificate without putting the keystore password in argv.
set -euo pipefail
if [[ $# != 4 ]]; then
  echo 'Usage: verify-keystore-certificate.sh <keystore> <alias> <password-env-name> <expected-sha256>' >&2
  exit 1
fi
keystore="$1"
alias_name="$2"
password_env="$3"
expected="$4"
[[ "$password_env" =~ ^[A-Za-z_][A-Za-z0-9_]*$ && -n "${!password_env:-}" ]] || {
  echo 'error: missing keystore password environment variable' >&2; exit 1;
}
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || {
  echo 'error: expected certificate must be a lowercase SHA-256 fingerprint' >&2; exit 1;
}
actual="$(keytool -exportcert -keystore "$keystore" -alias "$alias_name" \
  -storepass:env "$password_env" | python3 -c 'import hashlib, sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')"
if [[ "$actual" != "$expected" ]]; then
  printf 'error: keystore certificate mismatch: expected %s, actual %s\n' "$expected" "$actual" >&2
  exit 1
fi
printf 'Verified keystore certificate SHA-256: %s\n' "$actual"
