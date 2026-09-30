#!/usr/bin/env bash
# Per-iteration paging report for one run-performance-benchmarks.sh output directory.
#
# Usage: scripts/run-paging-trace-report.sh benchmark/build/outputs/manual/<UTC timestamp>/
# Writes <dir>/paging-report.jsonl (one row per trace) and prints the table with medians.
# Requires a Python with the `perfetto` package: PAGING_REPORT_PYTHON=~/.venvs/perfetto/bin/python
set -euo pipefail
dir="${1:?usage: $0 <runner output dir>}"
python="${PAGING_REPORT_PYTHON:-python3}"
here="$(cd "$(dirname "$0")" && pwd)"
out="$dir/paging-report.jsonl"
shopt -s nullglob
traces=("$dir"/ConversationPagingBenchmark_*.perfetto-trace)
if (( ${#traces[@]} == 0 )); then
  echo "!! no ConversationPagingBenchmark traces in $dir" >&2
  exit 1
fi
: > "$out"
failed=0
for trace in "${traces[@]}"; do
  if ! "$python" "$here/paging_trace_report.py" "$trace" 2>/dev/null >> "$out"; then
    echo "!! failed: $(basename "$trace")" >&2
    failed=1
  fi
done
"$python" "$here/paging_trace_report.py" --summarize "$out"
# A failed trace was named above; the summary shows what could be read and the exit status says it is partial.
exit "$failed"
