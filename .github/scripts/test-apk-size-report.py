#!/usr/bin/env python3
"""Fixture tests for apk-size-report.py."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name("apk-size-report.py")
spec = importlib.util.spec_from_file_location("apk_size_report", SCRIPT)
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ApkSizeReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.apk = self.root / "app.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"m" * 100, compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr("classes.dex", b"d" * 4000, compress_type=zipfile.ZIP_STORED)
            archive.writestr("classes2.dex", b"e" * 3000, compress_type=zipfile.ZIP_STORED)
            archive.writestr("lib/arm64-v8a/libmarmot_uniffi.so", bytes(range(256)) * 40,
                             compress_type=zipfile.ZIP_STORED)
            archive.writestr("lib/arm64-v8a/libsmall.so", b"s" * 500, compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr("resources.arsc", b"r" * 2000, compress_type=zipfile.ZIP_STORED)
            archive.writestr("res/drawable/a.xml", b"x" * 300, compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr("res/layout/b.xml", b"y" * 300, compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr("assets/big.json", b"j" * 900, compress_type=zipfile.ZIP_STORED)
            archive.writestr("assets/small.txt", b"t" * 10, compress_type=zipfile.ZIP_STORED)
            archive.writestr("META-INF/CERT.SF", b"c" * 50, compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr("kotlin/kotlin.kotlin_builtins", b"k" * 40, compress_type=zipfile.ZIP_DEFLATED)

    def tearDown(self):
        self.tmp.cleanup()

    def test_rows_account_for_every_apk_byte(self):
        measured = report.measure(self.apk, top=1)
        rows = {row["component"]: row for row in measured["components"]}
        self.assertEqual(measured["apk_bytes"], self.apk.stat().st_size)
        self.assertEqual(sum(row["apk_bytes"] for row in rows.values()), measured["apk_bytes"])
        self.assertGreater(rows["ZIP overhead"]["apk_bytes"], 0)
        self.assertEqual(rows["lib/arm64-v8a/libmarmot_uniffi.so"]["apk_bytes"], 10240)
        self.assertEqual(rows["lib/arm64-v8a/libmarmot_uniffi.so"]["kind"], "native")
        self.assertEqual(rows["classes.dex"]["kind"], "dex")
        self.assertEqual(rows["res/"]["entries"], 2)
        self.assertEqual(rows["res/"]["uncompressed_bytes"], 600)
        self.assertEqual(rows["other"]["entries"], 1)
        self.assertEqual(measured["components"][0]["component"], "lib/arm64-v8a/libmarmot_uniffi.so")
        self.assertEqual([item["path"] for item in measured["largest_assets"]], ["assets/big.json"])
        self.assertEqual(sum(measured["kinds"].values()), measured["apk_bytes"])

    def test_cli_writes_json_and_markdown_with_provenance(self):
        version = self.root / "MARMOT_VERSION"
        version.write_text("mdk-sha=abc123\nartifact-tag=marmotkit-v0.15.0\nfeatures=otlp-export\nunrelated=x\n")
        out_json, out_md = self.root / "report.json", self.root / "report.md"
        self.assertEqual(report.main([str(self.apk), "--flavor", "staging", "--commit", "deadbeef",
                                      "--marmot-version", str(version), "--json", str(out_json),
                                      "--markdown", str(out_md)]), 0)
        data = json.loads(out_json.read_text())
        self.assertEqual(data["schema"], 1)
        self.assertEqual(data["flavor"], "staging")
        self.assertEqual(data["marmotkit"], {"mdk-sha": "abc123", "artifact-tag": "marmotkit-v0.15.0",
                                             "features": "otlp-export"})
        text = out_md.read_text()
        self.assertIn("### APK size report: staging", text)
        self.assertIn("Commit `deadbeef`.", text)
        self.assertIn("| `lib/arm64-v8a/libmarmot_uniffi.so` (stored) |", text)
        self.assertIn("| `lib/arm64-v8a/libsmall.so` |", text)

    def test_rejects_missing_symlinked_and_invalid_apks(self):
        link = self.root / "link.apk"
        link.symlink_to(self.apk)
        bad = self.root / "bad.apk"
        bad.write_text("not a zip")
        for path in (self.root / "missing.apk", link, bad):
            with self.subTest(path=path.name):
                self.assertEqual(report.main([str(path), "--flavor", "staging"]), 1)


if __name__ == "__main__":
    unittest.main()
