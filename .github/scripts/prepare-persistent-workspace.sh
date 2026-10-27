#!/bin/bash
# wn-build-server runners keep their checkout between jobs. Keep only Gradle's
# incremental state (.gradle, .kotlin and module build directories) so a new
# revision recompiles only what changed, and delete everything else an
# earlier job left behind.
#
# Reports, test results and outputs are removed even inside build directories:
# later steps read and upload them, so their producing tasks must run again
# in this job. Test tasks therefore always execute, as on a fresh runner.
#
# On a fresh GitHub-hosted checkout this finds nothing to delete.
set -euo pipefail

modules=(app benchmark cryptoBenchmark fuzz)

keep=(-e /.gradle/ -e /.kotlin/)
for module in "${modules[@]}"; do
    keep+=(-e "/$module/build/")
done
git clean -ffdxq "${keep[@]}"

for module in "${modules[@]}"; do
    rm -rf "$module/build/reports" "$module/build/test-results" "$module/build/outputs" \
        "$module/build/compose-metrics" "$module/build/compose-reports"
done
