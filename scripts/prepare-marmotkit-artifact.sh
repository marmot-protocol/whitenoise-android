#!/usr/bin/env sh
# Launch artifact preparation with a clear Python dependency diagnostic.

set -eu

python_command="${WHITENOISE_PYTHON:-python3}"
if ! command -v "$python_command" >/dev/null 2>&1; then
  echo "error: Python 3 executable '$python_command' was not found; set WHITENOISE_PYTHON to an executable name or absolute path." >&2
  exit 1
fi

exec "$python_command" "$@"
