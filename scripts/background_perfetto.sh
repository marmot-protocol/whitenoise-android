#!/system/bin/sh
# Source these functions from an owned on-device fixture worker.

# Resolve a fresh output's parent without creating or modifying its destination.
background_perfetto_output_path() (
  directory="$(dirname "$1")" || exit 1
  name="$(basename "$1")" || exit 1
  cd -P "$directory" || exit 1
  printf '%s/%s\n' "$(pwd -P)" "$name"
)

# Return the started collector PID; the caller owns finalization and trace validation.
background_perfetto_start() {
  local config trace log trace_path log_path
  [ "$#" -eq 3 ] || return 1
  config="$1"
  trace="$2"
  log="$3"
  [ -r "$config" ] && [ ! -e "$trace" ] && [ ! -L "$trace" ] &&
    [ ! -e "$log" ] && [ ! -L "$log" ] || return 1
  trace_path="$(background_perfetto_output_path "$trace")" || return 1
  log_path="$(background_perfetto_output_path "$log")" || return 1
  [ "$trace_path" != "$log_path" ] || return 1
  # Perfetto's SELinux domain cannot read/write inherited shell-data file FDs.
  # Keep all three descriptors as pipes; shell-domain cat/tee own file access.
  (
    set -o pipefail
    cat "$config" |
      perfetto --txt -c - -o "$trace" --background-wait 2>&1 |
      tee "$log" >/dev/null
  ) || return 1
  awk '/^[1-9][0-9]*$/ { pid=$0; count++ } END { if (count != 1) exit 1; print pid }' "$log"
}
