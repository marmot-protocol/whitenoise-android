#!/system/bin/sh
# Source these functions from an owned on-device fixture worker.

# Return the started collector PID; the caller owns finalization and trace validation.
background_perfetto_start() {
  local config trace log
  [ "$#" -eq 3 ] || return 1
  config="$1"
  trace="$2"
  log="$3"
  [ -r "$config" ] && [ ! -e "$trace" ] && [ ! -L "$trace" ] &&
    [ ! -e "$log" ] && [ ! -L "$log" ] || return 1
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
