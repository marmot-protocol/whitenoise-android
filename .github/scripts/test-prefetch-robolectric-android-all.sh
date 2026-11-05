#!/usr/bin/env bash
# Exercises cache, integrity, deadlines and rate-limit fallback with local repositories.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_DIR/scripts/prefetch-robolectric-android-all.sh"
FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT

VERSION=16-robolectric-1-i7
JAR=android-all-instrumented-$VERSION.jar
remote="$FIXTURE/remote"
mkdir -p "$remote/org/robolectric/android-all-instrumented/$VERSION"
printf 'android runtime\n' >"$remote/org/robolectric/android-all-instrumented/$VERSION/$JAR"
good_sha="$(sha512sum "$remote/org/robolectric/android-all-instrumented/$VERSION/$JAR" | cut -d' ' -f1)"
printf 'robolectric = "4.17"\n' >"$FIXTURE/libs.versions.toml"

# Runs the prefetch with the given pin file into the given local repository.
run_prefetch() {
  ROBOLECTRIC_ANDROID_ALL_PINS="$1" ROBOLECTRIC_LOCAL_REPOSITORY="$2" \
    ROBOLECTRIC_VERSION_CATALOG="$FIXTURE/libs.versions.toml" \
    ROBOLECTRIC_ANDROID_ALL_REMOTE="file://$remote" bash "$SCRIPT"
}

# Fails the test with a message.
fail() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

printf '# comment\nrobolectric=4.17\n%s %s\n' "$VERSION" "$good_sha" >"$FIXTURE/good.pins"
local_repo="$FIXTURE/m2"
run_prefetch "$FIXTURE/good.pins" "$local_repo" | grep -Fq "$JAR: fetched" || fail 'jar was not fetched'
cmp -s "$remote/org/robolectric/android-all-instrumented/$VERSION/$JAR" \
  "$local_repo/org/robolectric/android-all-instrumented/$VERSION/$JAR" || fail 'fetched jar differs'
run_prefetch "$FIXTURE/good.pins" "$local_repo" | grep -Fq "$JAR: already present" ||
  fail 'a verified jar was downloaded again'

printf 'stale\n' >"$local_repo/org/robolectric/android-all-instrumented/$VERSION/$JAR"
run_prefetch "$FIXTURE/good.pins" "$local_repo" | grep -Fq "$JAR: fetched" || fail 'a corrupt jar was kept'

printf 'robolectric=4.17\n%s %0128d\n' "$VERSION" 0 >"$FIXTURE/bad.pins"
if run_prefetch "$FIXTURE/bad.pins" "$FIXTURE/bad-m2" 2>/dev/null; then
  fail 'a jar with the wrong SHA-512 was accepted'
fi
[[ -z "$(find "$FIXTURE/bad-m2" -type f)" ]] || fail 'a rejected download was left behind'

printf 'robolectric=4.16\n%s %s\n' "$VERSION" "$good_sha" >"$FIXTURE/old.pins"
if run_prefetch "$FIXTURE/old.pins" "$FIXTURE/old-m2" 2>/dev/null; then
  fail 'pins for another Robolectric version were accepted'
fi

printf 'robolectric=4.17\n' >"$FIXTURE/empty.pins"
if run_prefetch "$FIXTURE/empty.pins" "$FIXTURE/empty-m2" 2>/dev/null; then
  fail 'an empty jar list was accepted'
fi


# Reject malformed/overflowing budgets before even reusing a verified cache.
for budget in 1 241 08 18446744073709551618; do
  if ROBOLECTRIC_PREFETCH_BUDGET_SECONDS="$budget" \
      run_prefetch "$FIXTURE/good.pins" "$local_repo" >/dev/null 2>&1; then
    fail 'invalid budget escaped the fixed maximum'
  fi
done
# A fake consumer transport simulates Retry-After/backoff without contacting any server.
mkdir -p "$FIXTURE/bin"
cat >"$FIXTURE/bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$PREFETCH_CALLS"
output=""; url=""
while (($#)); do
  if [[ "$1" == --output ]]; then output="$2"; shift 2; else url="$1"; shift; fi
done
printf 'partial must never be admitted\n' >"$output"
[[ "$url" == "$PREFETCH_EXPECTED_REMOTE/"* ]] || exit 97
if [[ "$PREFETCH_MODE" == cumulative && "$url" == "$PREFETCH_GOOD_URL" ]]; then
  sleep 1
  cp -- "$PREFETCH_GOOD_JAR" "$output"
  printf 200
  exit 0
fi
if [[ "$PREFETCH_MODE" == stall || "$PREFETCH_MODE" == cumulative ]]; then
  # Model a retry sleep/transfer that ignores graceful cancellation.
  trap '' TERM
  sleep 30 &
  child=$!
  printf '%s\n%s\n' "$$" "$child" >>"$PREFETCH_PIDS"
  printf 'HTTP429 Retry-After3600 simulated backoff\n' >&2
  wait "$child"
else
  sleep 1
  exit 22
fi
FAKE_CURL
chmod +x "$FIXTURE/bin/curl"
export PREFETCH_CALLS="$FIXTURE/calls" PREFETCH_EXPECTED_REMOTE="https://custom.invalid/only"
export PREFETCH_PIDS="$FIXTURE/pids"
assert_transport_stopped() {
  while read -r pid; do
    if kill -0 "$pid" 2>/dev/null; then
      state="$(ps -o stat= -p "$pid")"
      [[ "$state" == Z* ]] || fail 'timed transport or its descendant is still running'
    fi
  done <"$PREFETCH_PIDS"
}
run_fake() {
  PATH="$FIXTURE/bin:$PATH" ROBOLECTRIC_PREFETCH_BUDGET_SECONDS=3 \
    ROBOLECTRIC_ANDROID_ALL_PINS="$1" ROBOLECTRIC_LOCAL_REPOSITORY="$2" \
    ROBOLECTRIC_VERSION_CATALOG="$FIXTURE/libs.versions.toml" \
    ROBOLECTRIC_ANDROID_ALL_REMOTE="$PREFETCH_EXPECTED_REMOTE" bash "$SCRIPT"
}
# A verified jar must not invoke transport, even when that transport would stall.
export PREFETCH_MODE=stall
: >"$PREFETCH_CALLS"
run_fake "$FIXTURE/good.pins" "$local_repo" >/dev/null
[[ ! -s "$PREFETCH_CALLS" ]] || fail 'verified cache invoked transport'
started=$SECONDS
if run_fake "$FIXTURE/good.pins" "$FIXTURE/stall-m2" >/dev/null 2>&1; then
  fail 'stalled429 retry consumer succeeded'
fi
((SECONDS - started <= 5)) || fail 'hard cumulative budget was exceeded'
[[ "$(wc -l <"$PREFETCH_CALLS")" -eq 1 ]] || fail 'custom remote was retried outside the timed curl'
[[ -z "$(find "$FIXTURE/stall-m2" -type f)" ]] || fail 'stalled partial was retained/admitted'
[[ -z "$(find "$FIXTURE/stall-m2" -name '.prefetch.*')" ]] || fail 'owned partial directory was retained'
assert_transport_stopped
# The first successful jar and the second stalled429 share exactly one deadline.
export PREFETCH_MODE=cumulative
export PREFETCH_GOOD_JAR="$remote/org/robolectric/android-all-instrumented/$VERSION/$JAR"
export PREFETCH_GOOD_URL="$PREFETCH_EXPECTED_REMOTE/org/robolectric/android-all-instrumented/$VERSION/$JAR"
printf 'robolectric=4.17\n%s %s\nother-version %s\nthird-version %s\n' \
  "$VERSION" "$good_sha" "$good_sha" "$good_sha" >"$FIXTURE/three.pins"
: >"$PREFETCH_CALLS"
: >"$PREFETCH_PIDS"
started=$SECONDS
if run_fake "$FIXTURE/three.pins" "$FIXTURE/cumulative-m2" >/dev/null 2>&1; then
  fail 'cumulative stalled transport succeeded'
fi
((SECONDS - started <= 5)) || fail 'downloads reset the cumulative deadline'
[[ "$(wc -l <"$PREFETCH_CALLS")" -eq 2 ]] || fail 'deadline admitted a third request'
cmp -s "$PREFETCH_GOOD_JAR" \
  "$FIXTURE/cumulative-m2/org/robolectric/android-all-instrumented/$VERSION/$JAR" ||
  fail 'already verified artifact was not preserved'
[[ "$(find "$FIXTURE/cumulative-m2" -type f | wc -l)" -eq 1 ]] || fail 'partial admitted after cumulative timeout'
assert_transport_stopped
# A failed first artifact prevents starting later artifacts; no endpoint fallback.
export PREFETCH_MODE=fail
printf 'robolectric=4.17\n%s %s\nother-version %s\n' "$VERSION" "$good_sha" "$good_sha" >"$FIXTURE/two.pins"
: >"$PREFETCH_CALLS"
if run_fake "$FIXTURE/two.pins" "$FIXTURE/fail-m2" >/dev/null 2>&1; then
  fail 'failed custom source was accepted'
fi
[[ "$(wc -l <"$PREFETCH_CALLS")" -eq 1 ]] || fail 'failure escaped the exclusive source or advanced jars'
[[ -z "$(find "$FIXTURE/fail-m2" -type f)" ]] || fail 'failed partial was retained'

# Real curl receives HTTP 429 and a long Retry-After from a loopback server.
python3 - "$SCRIPT" "$FIXTURE" <<'HTTP_REGRESSION'
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

script = Path(sys.argv[1]).resolve()
artifact = b"synthetic Android runtime fixture\n"
digest = hashlib.sha512(artifact).hexdigest()
events = []
mode = ["rate_limit"]


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        route = self.path.split("/")[1]
        events.append(route)
        if route == "primary" and mode[0] != "corrupt_primary":
            self.send_response(429)
            self.send_header("Retry-After", "3600")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if route not in ("primary", "fallback"):
            self.send_error(400)
            return
        body = artifact
        if mode[0] == "corrupt_primary" or mode[0] == "corrupt_fallback":
            body = b"not the pinned artifact\n"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
results = []
try:
    with tempfile.TemporaryDirectory(prefix="prefetch-http-", dir=sys.argv[2]) as tmp:
        root = Path(tmp)
        binary = root / "bin"
        binary.mkdir()
        real_curl = shutil.which("curl")
        assert real_curl
        wrapper = binary / "curl"
        wrapper.write_text(
            "#!" + sys.executable + "\n"
            "import os,sys\n"
            "args=sys.argv[1:]\n"
            "url=args[-1]\n"
            "routes={'https://repo.maven.apache.org/maven2/':'fallback/',"
            "'https://maven-central.storage-download.googleapis.com/maven2/':'primary/'}\n"
            "for prefix,route in routes.items():\n"
            " if url.startswith(prefix):\n"
            "  args[-1]='http://127.0.0.1:' + os.environ['PREFETCH_HTTP_PORT'] + '/' + route + url[len(prefix):]\n"
            "  break\n"
            "else: raise SystemExit('unexpected endpoint; external requests forbidden')\n"
            "real=os.environ['PREFETCH_REAL_CURL']\n"
            "os.execv(real,[real,'--disable','--noproxy','*']+args)\n"
        )
        wrapper.chmod(0o755)
        catalog = root / "libs.versions.toml"
        catalog.write_text('robolectric = "4.17"\n')
        pins = root / "runtime.pins"
        versions = ("fixture-one", "fixture-two")
        pins.write_text("robolectric=4.17\n" + "".join(v + " " + digest + "\n" for v in versions))

        def run(name, custom=False):
            events.clear()
            cache = root / name
            env = dict(os.environ)
            env.pop("ROBOLECTRIC_ANDROID_ALL_REMOTE", None)
            env.update(
                PATH=str(binary) + os.pathsep + env["PATH"],
                ROBOLECTRIC_ANDROID_ALL_PINS=str(pins),
                ROBOLECTRIC_VERSION_CATALOG=str(catalog),
                ROBOLECTRIC_LOCAL_REPOSITORY=str(cache),
                ROBOLECTRIC_PREFETCH_BUDGET_SECONDS="4",
                PREFETCH_REAL_CURL=real_curl,
                PREFETCH_HTTP_PORT=str(server.server_port),
            )
            if custom:
                env["ROBOLECTRIC_ANDROID_ALL_REMOTE"] = "https://maven-central.storage-download.googleapis.com/maven2"
            started = time.monotonic()
            process = subprocess.Popen(
                ["bash", str(script)], env=env, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, start_new_session=True,
            )
            try:
                stdout, stderr = process.communicate(timeout=8)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                raise AssertionError("consumer escaped the cumulative deadline")
            elapsed = time.monotonic() - started
            result = {
                "case": name, "returncode": process.returncode,
                "elapsed_seconds": elapsed, "routes": list(events),
                "stderr": stderr.decode(),
            }
            results.append(result)
            assert elapsed < 6, result
            assert not list(cache.rglob(".prefetch.*")), result
            return process.returncode, cache

        code, cache = run("default-rate-limit")
        assert code == 0, results[-1]
        assert events == ["primary", "fallback", "fallback"], results[-1]
        for version in versions:
            jar = cache / "org/robolectric/android-all-instrumented" / version / (
                "android-all-instrumented-" + version + ".jar"
            )
            assert jar.read_bytes() == artifact

        code, cache = run("explicit-primary", custom=True)
        assert code != 0 and events == ["primary"], results[-1]
        assert not [p for p in cache.rglob("*") if p.is_file()]

        mode[0] = "corrupt_primary"
        code, cache = run("corrupt-primary")
        assert code != 0 and events == ["primary"], results[-1]
        assert not [p for p in cache.rglob("*") if p.is_file()]

        mode[0] = "corrupt_fallback"
        code, cache = run("corrupt-fallback")
        assert code != 0 and events == ["primary", "fallback"], results[-1]
        assert not [p for p in cache.rglob("*") if p.is_file()]
finally:
    server.shutdown()
    server.server_close()
    thread.join()
    print(json.dumps({"script": str(script), "results": results}, indent=2))
HTTP_REGRESSION

printf 'test-prefetch-robolectric-android-all.sh passed\n'
