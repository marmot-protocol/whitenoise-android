"""Exercise the compatibility entry point used by trusted pre-adoption previews."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

from scripts.verify_compose_backport_apk import EXPECTED_VERSION, VERSION_RESOURCE, verify_apk


class OfficialComposeApkTest(unittest.TestCase):
    """Require official version metadata without accepting a leftover custom runtime."""

    def make_apk(self, entries):
        """Write each ZIP member separately so duplicate-name corruption stays observable."""
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / 'candidate.apk'
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(path, 'w') as archive:
                for name, content in entries:
                    archive.writestr(name, content)
        return path

    def test_legacy_cli_accepts_official_release(self):
        """The unchanged trusted master command can qualify the new candidate."""
        apk = self.make_apk([(VERSION_RESOURCE, EXPECTED_VERSION)])
        script = Path(__file__).with_name('verify_compose_backport_apk.py')
        result = subprocess.run([sys.executable, str(script), str(apk)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_missing_duplicate_or_wrong_version(self):
        """A resource must uniquely identify the reviewed official version."""
        for entries in [[], [(VERSION_RESOURCE, b'1.12.1\n')],
                        [(VERSION_RESOURCE, EXPECTED_VERSION)] * 2]:
            with self.subTest(entries=entries), self.assertRaisesRegex(ValueError, 'version metadata'):
                verify_apk(self.make_apk(entries))

    def test_rejects_backport_even_with_official_version(self):
        """An official version string cannot bless a still-customized runtime."""
        apk = self.make_apk([(VERSION_RESOURCE, EXPECTED_VERSION),
                             ('META-INF/whitenoise-compose-rectlist-backport.properties', b'')])
        with self.assertRaisesRegex(ValueError, 'backport is still packaged'):
            verify_apk(apk)


if __name__ == '__main__':
    unittest.main()
