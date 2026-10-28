#!/usr/bin/env python3
"""Protected ZSP v2 publication: prepare, persist intent, submit once, verify bytes.

Only sanitized receipts survive. CLI output can contain signer credentials and is
never printed or uploaded. A pending/unknown submission is never retried here.
"""
from __future__ import annotations

import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from release_bundle import (ROOT, check_tag, command, properties, require,
                            verify_bundle, verify_source_metadata)

WORKFLOW = "Android Zapstore - PUBLIC Publication"
STATE = ROOT / "build/zapstore-publication"


def write_json(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, indent=2) + "\n")


def timeout(name, default):
    value = os.environ.get(name, str(default))
    require(value.isdecimal() and 1 <= int(value) <= 900, "Invalid ZSP timeout")
    return int(value)


def cli(binary, args, cwd, env, seconds):
    # No raw CLI stdout/stderr reaches Actions logs, even on timeout or errors.
    try:
        result = subprocess.run([binary, "--json", *args], cwd=cwd, env=env,
                                capture_output=True, timeout=seconds)
    except subprocess.TimeoutExpired:
        return 124, {}
    try:
        document = json.loads(result.stdout)
    except (ValueError, UnicodeDecodeError):
        document = {}
    return result.returncode, document if isinstance(document, dict) else {}


def error_code(document):
    code = document.get("error", {}).get("code") if isinstance(document.get("error"), dict) else None
    # A remote free-text field, URL, or unknown error must not leak into logs.
    known = {"proof_required", "proof_unauthorized", "signer_unavailable", "release_already_published",
             "release_downgrade", "no_new_apk", "publish_rejected", "upload_rejected",
             "rate_limited", "temporary_failure", "cancelled", "invalid_apk", "invalid_arguments",
             "apk_not_checked", "apk_selection_required", "source_failed", "no_apk",
             "too_many_candidates", "publish_failed"}
    return code if code in known else "unclassified_failure"


def publisher_npub(public_key):
    """NIP-19 Bech32 representation of the single pinned hexadecimal public key."""
    require(re.fullmatch(r"[0-9a-f]{64}", public_key), "Invalid publisher public key")
    value = int(public_key, 16) << 4  # 256 bits, padded to 52 five-bit values.
    data = [(value >> (5 * shift)) & 31 for shift in reversed(range(52))]
    hrp = "npub"
    checksum = 1
    for part in [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp] + data + [0] * 6:
        top = checksum >> 25
        checksum = ((checksum & 0x1ffffff) << 5) ^ part
        for bit, generator in enumerate((0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)):
            if (top >> bit) & 1:
                checksum ^= generator
    checksum ^= 1
    alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    return hrp + "1" + "".join(alphabet[v] for v in data + [(checksum >> (5 * i)) & 31 for i in reversed(range(6))])


def validate_check(document, manifest, apk, policy):
    require(document.get("ok") is True and document.get("operation") == "check", "ZSP check failed")
    expected = {"app_id": policy["ZAPSTORE_APP_ID"], "version_name": manifest["versionName"],
                "version_code": manifest["versionCode"], "hash": manifest["files"][apk]["sha256"],
                "certificate_hash": policy["APP_SIGNING_SHA256"]}
    require(isinstance(document.get("apk"), dict) and all(document["apk"].get(k) == v for k, v in expected.items()),
            "ZSP APK identity differs from the reviewed candidate")
    npub = publisher_npub(policy["ZAPSTORE_PUBLISHER_PUBKEY"])
    require(document.get("signer") == npub, "ZSP signer differs from the pinned publisher")
    proof = document.get("proof")
    require(isinstance(proof, dict), "C1 proof missing; the Android key holder must create it locally")
    require(document.get("authorized") is True and npub in (proof.get("owner"), proof.get("delegate")),
            "C1 proof does not authorize the pinned publisher")
    try:
        expiry = dt.datetime.fromisoformat(proof["expires_at"].replace("Z", "+00:00"))
        healthy = expiry > dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=30)
    except (KeyError, TypeError, ValueError):
        healthy = False
    require(healthy, "C1 proof needs renewal by the Android key holder before publication")
    next_step = document.get("next_step")
    require(next_step is None or (isinstance(next_step, dict) and not next_step.get("requires_human")),
            "ZSP requires holder action or returned an invalid next step")
    code = document.get("published_version_code")
    require(type(code) is int and 0 <= code <= manifest["versionCode"], "Published version is invalid or newer")
    return code


def previous_intent(version):
    # Artifacts are only a replay barrier, never proof that a release is public.
    # Fail closed if the ledger cannot be read. Reruns always use readback only.
    if int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")) > 1:
        return True
    name = f"zapstore-submission-intent-{version}"
    pages = json.loads(command("gh", "api", "--paginate", "--slurp",
                              f"repos/marmot-protocol/whitenoise-android/actions/artifacts?name={name}&per_page=100"))
    for page in pages:
        for artifact in page["artifacts"]:
            if artifact["name"] != name:
                continue
            run_id = artifact.get("workflow_run", {}).get("id")
            require(run_id is not None, "Cannot establish prior submission provenance")
            run = json.loads(command("gh", "api", f"repos/marmot-protocol/whitenoise-android/actions/runs/{run_id}"))
            if run.get("path") == ".github/workflows/android-zapstore-publish.yml" and run.get("head_branch") == "master":
                return True
    return False


def readback(binary, manifest, apk, policy, expected_id=None):
    info = manifest["files"][apk]
    args = [binary, "verify", "-relay", policy["ZAPSTORE_RELAY"], "-publisher", policy["ZAPSTORE_PUBLISHER_PUBKEY"],
            "-app", policy["ZAPSTORE_APP_ID"], "-version", manifest["versionName"], "-apk-sha256", info["sha256"],
            "-version-code", str(manifest["versionCode"]), "-commit", manifest["sourceCommit"],
            "-cert-sha256", policy["APP_SIGNING_SHA256"], "-blossom", policy["ZAPSTORE_BLOSSOM"],
            "-apk-bytes", str(info["bytes"]), "-attempts", "30", "-json"]
    if expected_id:
        args += ["-release-id", expected_id]
    try:
        # This verifier receives public inputs only. Its diagnostics contain
        # relay/CDN facts, never ZSP signer output or credential environment.
        env = {k: os.environ[k] for k in ("PATH", "HOME", "TMPDIR") if k in os.environ}
        result = subprocess.run(args, capture_output=True, env=env,
                                timeout=timeout("ZSP_READBACK_TIMEOUT_SECONDS", 600))
        if result.returncode == 0:
            got = json.loads(result.stdout)
            require(all(re.fullmatch(r"[0-9a-f]{64}", got.get(k, "")) for k in ("App", "Release", "Asset")),
                    "Invalid public verification receipt")
            return got
        lines = result.stderr.decode(errors="replace").strip().splitlines()
        if lines:
            print("Zapstore public readback failed: " + lines[-1][-1000:], file=sys.stderr)
    except subprocess.TimeoutExpired:
        print("Zapstore public readback timed out", file=sys.stderr)
    except ValueError:
        print("Zapstore public readback returned an invalid verification receipt", file=sys.stderr)
    return None


def main():
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GITHUB_REF") == "refs/heads/master"
            and os.environ.get("GITHUB_WORKFLOW") == WORKFLOW, "Use the dedicated protected master workflow")
    version = os.environ.get("EXPECTED_VERSION", "")
    require(re.fullmatch(r"20\d{2}\.\d{1,2}\.\d{1,2}", version) and
            os.environ.get("CONFIRMATION") == "PUBLISH ZAPSTORE " + version, "Explicit public version confirmation required")
    require(len(sys.argv) == 4 and sys.argv[3] in ("prepare", "publish"), "Pass ZSP, readback executable and prepare|publish")
    binary, reader, phase = sys.argv[1:]
    policy = properties()
    bundle = ROOT / "build/production-release"
    manifest = json.loads((bundle / "release-manifest.json").read_text())
    verify_bundle(bundle, os.environ["MANIFEST_SHA256"], version, manifest["sourceCommit"],
                  manifest["buildRunId"], manifest["buildRunAttempt"], policy)
    verify_source_metadata(bundle, manifest)
    check_tag(manifest["sourceCommit"], version)
    apk, = (name for name in manifest["files"] if name.endswith(".apk"))
    identity = dict(app_id=policy["ZAPSTORE_APP_ID"], version_name=version, version_code=manifest["versionCode"],
                    source_commit=manifest["sourceCommit"], apk_sha256=manifest["files"][apk]["sha256"],
                    manifest_sha256=os.environ["MANIFEST_SHA256"], publisher=policy["ZAPSTORE_PUBLISHER_PUBKEY"],
                    zsp_version=policy["ZSP_VERSION"], run_id=os.environ.get("GITHUB_RUN_ID"),
                    run_attempt=os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
    receipt_path = STATE / "receipt.json"
    if phase == "publish":
        state = json.loads((STATE / "intent.json").read_text())
        require(state["candidate"] == identity, "Submission intent does not match candidate/run")
        submit = state["submit"]
        require(not submit or os.environ.get("ZSP_INTENT_RECORDED") == "true", "Persist submission intent before public writes")
    else:
        submit = os.environ.get("VERIFY_ONLY") != "true" and not previous_intent(version)
    receipt = {"candidate": identity, "status": "not_submitted" if submit else "verification_only"}
    write_json(receipt_path, receipt)
    if not submit:
        if phase == "prepare":
            write_json(STATE / "intent.json", {"candidate": identity, "submit": False})
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a") as out:
                    out.write("submit=false\n")
            return
        got = readback(reader, manifest, apk, policy)
        receipt.update(status="verified_public" if got else "unresolved", public=got)
        write_json(receipt_path, receipt)
        require(got, "Prior/pending submission unresolved: retain receipts and use verification only; do not republish")
    else:
        connection, key = os.environ.get("SIGN_WITH", ""), os.environ.get("BUNKER_CLIENT_KEY", "")
        transport = re.fullmatch(r"bunker://([0-9a-fA-F]{64})(?:\?.*)?", connection)
        require(transport and re.fullmatch(r"[0-9a-fA-F]{64}", key), "Valid scoped bunker connection and stable client key required")
        with tempfile.TemporaryDirectory() as temporary:
            temporary = Path(temporary)
            config = temporary / "config"
            key_path = config / "zsp/bunker-keys" / (transport[1].lower() + ".key")
            key_path.parent.mkdir(parents=True)
            key_path.write_text(key + "\n")
            listing = temporary / "listing"
            listing.mkdir()
            with zipfile.ZipFile(bundle / f"store-assets-{version}.zip") as archive:
                for entry in archive.infolist():
                    if entry.is_dir():
                        continue
                    name = Path(entry.filename)
                    require(not name.is_absolute() and ".." not in name.parts, "Unsafe listing path")
                    target = listing / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(entry))
            apk_dir = listing / "build/production-release"
            apk_dir.mkdir(parents=True)
            shutil.copyfile(bundle / apk, apk_dir / apk)
            # No inherited HOTBOX/keystore, credential files, or unrelated service secrets.
            env = {k: os.environ[k] for k in ("PATH", "HOME", "TMPDIR") if k in os.environ}
            env.update(SIGN_WITH=connection, XDG_CONFIG_HOME=str(config), RELAYS=policy["ZAPSTORE_RELAY"],
                       BLOSSOM_URL=policy["ZAPSTORE_BLOSSOM"])
            if phase == "prepare":
                status, document = cli(binary, ["check", "--channel", "main", "zapstore.yaml"], listing, env,
                                       timeout("ZSP_PREFLIGHT_TIMEOUT_SECONDS", 120))
                require(status == 0, "ZSP read-only preflight failed: " + error_code(document))
                published = validate_check(document, manifest, apk, policy)
                if published == manifest["versionCode"]:
                    submit = False  # Independent readback must still prove an exact match.
                else:
                    result = subprocess.run([reader, "absent", "-relay", policy["ZAPSTORE_RELAY"], "-publisher",
                                             policy["ZAPSTORE_PUBLISHER_PUBKEY"], "-app", policy["ZAPSTORE_APP_ID"],
                                             "-version", version, "-apk-sha256", identity["apk_sha256"]],
                                            capture_output=True, timeout=120)
                    require(result.returncode == 0, "Existing/partial release or unknown relay state; verify before any submission")
                write_json(STATE / "intent.json", {"candidate": identity, "submit": submit})
                if os.environ.get("GITHUB_OUTPUT"):
                    with open(os.environ["GITHUB_OUTPUT"], "a") as out:
                        out.write(f"submit={str(submit).lower()}\n")
                print("Read-only ZSP v2 preflight passed; " + ("ready for one submission" if submit else "verify existing release"))
                return
            # PUBLIC SIDE EFFECT: exactly one attempt. Persisted intent already exists.
            receipt["status"] = "submission_started"
            write_json(receipt_path, receipt)
            status, document = cli(binary, ["publish", "--quiet", "--skip-metadata", "--no-compress", "--channel", "main",
                                            "--apk-hash", identity["apk_sha256"], "--commit", identity["source_commit"], "zapstore.yaml"],
                                   listing, env, timeout("ZSP_PUBLISH_TIMEOUT_SECONDS", 480))
            result = document if document.get("ok") is True else document.get("result", {})
            result = result if isinstance(result, dict) else {}
            release_id = result.get("id")
            release_id = release_id if isinstance(release_id, str) and re.fullmatch(r"[0-9a-f]{64}", release_id) else None
            accepted = (status == 0 and document.get("ok") is True and document.get("operation") == "publish"
                        and document.get("app_id") == identity["app_id"] and document.get("version_name") == version
                        and document.get("version_code") == manifest["versionCode"] and release_id
                        and document.get("status") in ("pending", "published"))
            receipt.update(status=document["status"] if accepted else "submission_unknown", release_id=release_id)
            if not accepted:
                receipt["error_code"] = error_code(document)
            write_json(receipt_path, receipt)
            # Even a timeout may have submitted: reconcile by reading, never retry.
            got = readback(reader, manifest, apk, policy, release_id)
            if got:
                receipt.update(status="verified_public", public=got)
                write_json(receipt_path, receipt)
            require(got, "Publication pending, rejected or unknown; keep the receipt and run verification only. No automatic resubmission.")
    print(f"Zapstore {version} verified public: signed events and reviewed CDN bytes match.")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
            out.write(f"Zapstore `{version}` verified public. APK `{identity['apk_sha256']}`. See sanitized publication receipt.\n")


if __name__ == "__main__":
    os.umask(0o077)
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        # Only our own validation strings are safe; subprocess exceptions may contain arguments.
        message = str(error) if isinstance(error, ValueError) else "Publication tooling failed; retain receipts and inspect privately"
        print("error: " + message, file=sys.stderr)
        sys.exit(1)
