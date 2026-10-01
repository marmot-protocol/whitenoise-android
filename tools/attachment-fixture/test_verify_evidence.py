"""Archive integrity covers every original file and rejects corruption or unsafe extraction."""

import base64
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_evidence import extract_verified, verify_archive


class EvidenceIntegrityTest(unittest.TestCase):
    """Use independent ZIP bytes and expected digests to test the verification boundary."""

    def archive(self, name="sessions/failed.json"):
        """Keep indentation and trailing newline so fidelity is checked byte for byte."""
        data = b'{\n  "qualified": false\n}\n'
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(name, data)
        blob = buffer.getvalue()
        return base64.encodebytes(blob), hashlib.sha256(blob).hexdigest(), {name: hashlib.sha256(data).hexdigest()}, data

    def test_original_failed_report_bytes_are_preserved(self):
        """Failure evidence is as recoverable as successful evidence."""
        encoded, digest, members, data = self.archive()
        self.assertEqual({"sessions/failed.json": data}, verify_archive(encoded, digest, members))

    def test_archive_and_member_checksum_and_coverage_fail_closed(self):
        """Wrong ZIP hash, wrong member hash and omitted manifest entries each fail."""
        encoded, digest, members, _ = self.archive()
        for expected_digest, expected_members in (("0" * 64, members), (digest, {next(iter(members)): "0" * 64}), (digest, {})):
            with self.assertRaises(ValueError):
                verify_archive(encoded, expected_digest, expected_members)

    def test_unsafe_path_is_rejected_even_with_matching_checksums(self):
        """A manifest cannot authorize writing outside the extraction directory."""
        encoded, digest, members, _ = self.archive("../failed.json")
        with self.assertRaises(ValueError):
            verify_archive(encoded, digest, members)

    def test_existing_root_with_symlink_parent_cannot_redirect_extraction(self):
        """Reject a previously prepared root before any verified member can escape through symlinks."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "extracted"
            outside = Path(directory) / "outside"
            root.mkdir()
            outside.mkdir()
            (root / "docs").symlink_to(outside, target_is_directory=True)
            with self.assertRaises(FileExistsError):
                extract_verified({"docs/failed.json": b"original"}, root)
            self.assertFalse((outside / "failed.json").exists())
            fresh = Path(directory) / "fresh"
            extract_verified({"docs/failed.json": b"original"}, fresh)
            self.assertEqual(b"original", (fresh / "docs/failed.json").read_bytes())
            self.assertEqual(0o700, fresh.stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
