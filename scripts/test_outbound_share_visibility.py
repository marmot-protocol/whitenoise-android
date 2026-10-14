"""Regressions for the packaged share-target visibility contract."""

import importlib.util
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("system_labels", Path(__file__).with_name("verify-system-labels.py"))
LABELS = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = LABELS
SPEC.loader.exec_module(LABELS)


class OutboundShareVisibilityTest(unittest.TestCase):
    """Empty package-manager results are trustworthy only with the right action/MIME queries."""

    def manifest(self, queries="", permissions=""):
        """Construct an isolated manifest without unrelated application identity requirements."""
        return ET.fromstring(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            f'{permissions}<queries>{queries}</queries></manifest>'
        )

    def query(self, action, mime="*/*", extra=""):
        """Declare one action and its discovery constraints."""
        return (
            f'<intent><action android:name="android.intent.action.{action}"/>'
            f'<data android:mimeType="{mime}" {extra}/></intent>'
        )

    def test_both_actions_cover_txt_documents_and_mixed_families(self):
        """A complete common-launcher declaration covers single and multiple arbitrary-MIME shares."""
        root = self.manifest(self.query("SEND") + self.query("SEND_MULTIPLE"))
        LABELS.verify_share_target_visibility(root, "fixture")

    def test_missing_or_partial_visibility_fails(self):
        """Declaring just one share action cannot qualify the other action's plaintext preflight."""
        for queries in ("", self.query("SEND"), self.query("SEND_MULTIPLE")):
            with self.subTest(queries=queries), self.assertRaisesRegex(AssertionError, "missing outbound"):
                LABELS.verify_share_target_visibility(self.manifest(queries), "fixture")

    def test_narrow_mime_or_uri_constraints_cannot_qualify_the_common_launcher(self):
        """Image-only or web-only discovery would still hide valid document receivers."""
        for query in (self.query("SEND", "image/*"), self.query("SEND", extra='android:scheme="https"')):
            with self.subTest(query=query), self.assertRaisesRegex(AssertionError, "missing outbound"):
                LABELS.verify_share_target_visibility(self.manifest(query + self.query("SEND_MULTIPLE")), "fixture")

    def test_query_all_packages_is_not_a_substitute(self):
        """The repaired visibility remains scoped to share intents rather than all installed apps."""
        root = self.manifest(
            self.query("SEND") + self.query("SEND_MULTIPLE"),
            '<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>',
        )
        with self.assertRaisesRegex(AssertionError, "intent-scoped"):
            LABELS.verify_share_target_visibility(root, "fixture")


if __name__ == "__main__":
    unittest.main()
