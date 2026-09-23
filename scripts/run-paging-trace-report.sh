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
: > "$out"
for trace in "$dir"/ConversationPagingBenchmark_*.perfetto-trace; do
  "$python" "$here/paging_trace_report.py" "$trace" 2>/dev/null >> "$out" || echo "!! failed: $(basename "$trace")" >&2
done
"$python" "$here/paging_trace_report.py" --summarize "$out"
