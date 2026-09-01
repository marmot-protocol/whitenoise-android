#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("generate-audit-attestation.py")
BASE_ENV = {
    "GITHUB_ACTIONS": "true",
    "GITHUB_REPOSITORY": "marmot-protocol/whitenoise-android",
    "GITHUB_WORKFLOW": "Android Staging APK",
    "GITHUB_REF": "refs/heads/master",
    "GITHUB_EVENT_NAME": "push",
    "GITHUB_ACTOR": "Datawav",
    "GITHUB_SHA": "1" * 40,
    "WHITENOISE_STAGING_AUDIT_LOG_ENDPOINT": "https://audit.invalid/upload",
    "WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN": "secret-test-token",
}
EXPECTED_KEYS = {
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


class GenerateAuditAttestationTest(unittest.TestCase):
    def run_generator(self, overrides: dict[str, str] | None = None) -> tuple[subprocess.CompletedProcess[str], str]:
        env = os.environ.copy()
        env.update(BASE_ENV)
        env.update(overrides or {})
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "attestation.json"
            result = subprocess.run(
                [str(SCRIPT), str(output)],
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )
            payload = output.read_text(encoding="utf-8") if output.exists() else ""
        return result, payload

    def test_generates_exact_secret_free_schema(self) -> None:
        result, payload = self.run_generator()
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads(payload)
        self.assertEqual(EXPECTED_KEYS, set(value))
        self.assertEqual("dev.ipf.whitenoise.android.staging", value["package_name"])
        self.assertEqual("1" * 40, value["source_revision"])
        self.assertEqual("android-staging-apk", value["workflow"])
        self.assertIs(value["audit_endpoint_configured"], True)
        self.assertIs(value["audit_auth_configured"], True)
        self.assertIs(value["runtime_audit_required"], True)
        self.assertEqual("obfuscated_sensitive_data", value["data_mode"])
        self.assertNotIn(BASE_ENV["WHITENOISE_STAGING_AUDIT_LOG_ENDPOINT"], payload)
        self.assertNotIn(BASE_ENV["WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN"], payload)

    def test_rejects_missing_endpoint(self) -> None:
        result, payload = self.run_generator({"WHITENOISE_STAGING_AUDIT_LOG_ENDPOINT": ""})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", payload)

    def test_rejects_missing_auth(self) -> None:
        result, payload = self.run_generator({"WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN": ""})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", payload)

    def test_rejects_untrusted_workflow_or_ref(self) -> None:
        for overrides in (
            {"GITHUB_WORKFLOW": "Android CI"},
            {"GITHUB_REF": "refs/pull/123/merge"},
            {"GITHUB_SHA": "short"},
            {"GITHUB_ACTIONS": "false"},
        ):
            with self.subTest(overrides=overrides):
                result, payload = self.run_generator(overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", payload)

    def test_manual_branch_dispatch_requires_datawav(self) -> None:
        result, payload = self.run_generator(
            {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/contributor/change",
                "GITHUB_ACTOR": "contributor",
            },
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", payload)

        result, payload = self.run_generator(
            {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/contributor/change",
            },
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", payload)

        result, payload = self.run_generator(
            {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/datawav/trusted-staging-audit",
            },
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(payload)


if __name__ == "__main__":
    unittest.main()
