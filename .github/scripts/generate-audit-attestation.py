#!/usr/bin/env python3
"""Generate the secret-free audit attestation for trusted staging CI."""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

HEX40 = re.compile(r"[0-9a-f]{40}")
PACKAGE_NAME = "dev.ipf.whitenoise.android.staging"
WORKFLOW_NAME = "Android Staging APK"
WORKFLOW_ID = "android-staging-apk"
DATA_MODE = "obfuscated_sensitive_data"
EXPECTED_REPOSITORY = "marmot-protocol/whitenoise-android"
MASTER_REF = "refs/heads/master"
ALLOWED_EVENTS = {"push", "workflow_dispatch"}
ATTESTATION_KEYS = {
    "schema_version",
    "package_name",
    "build_environment",
    "source_revision",
    "workflow",
    "audit_endpoint_configured",
    "audit_auth_configured",
    "runtime_audit_required",
    "data_mode",
}


def require(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise ValueError(f"{name} is required")
    return value


def build_attestation() -> dict[str, object]:
    if require("GITHUB_ACTIONS") != "true":
        raise ValueError("attestation generation is restricted to GitHub Actions")
    if require("GITHUB_REPOSITORY") != EXPECTED_REPOSITORY:
        raise ValueError("attestation repository is not trusted")
    if require("GITHUB_WORKFLOW") != WORKFLOW_NAME:
        raise ValueError("attestation workflow is not trusted")
    event = require("GITHUB_EVENT_NAME")
    ref = require("GITHUB_REF")
    if event not in ALLOWED_EVENTS:
        raise ValueError("attestation event is not trusted")
    if event == "push" and ref != MASTER_REF:
        raise ValueError("push attestation must be generated from master")
    if event == "workflow_dispatch" and (
        require("GITHUB_ACTOR") != "Datawav" or not ref.startswith("refs/heads/datawav/")
    ):
        raise ValueError("manual attestation requires a Datawav-owned branch dispatch")

    source_revision = require("GITHUB_SHA")
    if not HEX40.fullmatch(source_revision):
        raise ValueError("GITHUB_SHA must be a lowercase 40-character revision")

    endpoint_configured = bool(require("WHITENOISE_STAGING_AUDIT_LOG_ENDPOINT").strip())
    auth_configured = bool(require("WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN").strip())
    if not endpoint_configured or not auth_configured:
        raise ValueError("authenticated staging audit upload must be configured")

    value: dict[str, object] = {
        "schema_version": 1,
        "package_name": PACKAGE_NAME,
        "build_environment": "staging",
        "source_revision": source_revision,
        "workflow": WORKFLOW_ID,
        "audit_endpoint_configured": endpoint_configured,
        "audit_auth_configured": auth_configured,
        "runtime_audit_required": True,
        "data_mode": DATA_MODE,
    }
    if set(value) != ATTESTATION_KEYS:
        raise AssertionError("attestation schema drifted")
    return value


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} OUTPUT", file=sys.stderr)
        return 64
    try:
        value = build_attestation()
        output = Path(sys.argv[1])
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n", encoding="utf-8")
    except (OSError, ValueError) as exc:
        print(f"audit attestation generation failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
