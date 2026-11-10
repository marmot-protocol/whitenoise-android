import io
import os
import sys
import tarfile
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import kover_class_outputs  # noqa: E402


class KoverClassOutputsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def write(self, relative, content=''):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        return path

    def test_reads_only_the_output_section(self):
        self.write('app/build/classes/A.class')
        artifact = 'app/src/main/kotlin\n\napp/build/classes\napp/build/missing\n\napp/build/kover/bin-reports/test.ic\n'
        self.assertEqual(
            kover_class_outputs.output_directories(artifact, self.root),
            [Path('app/build/classes')],
        )

    def test_rejects_directories_outside_the_root(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            kover_class_outputs.output_directories('src\n\n../elsewhere\n\n', self.root)

    def test_rejects_an_artifact_without_output_section(self):
        with self.assertRaisesRegex(ValueError, 'no output-directory section'):
            kover_class_outputs.output_directories('src\n', self.root)

    def test_round_trip_restores_relative_paths(self):
        self.write('app/build/kotlin/dev/A.class', 'kotlin')
        self.write('app/build/javac/dev/B.class', 'java')
        artifact = self.write('app/build/kover/devZapstoreDebug.artifact', 'src\n\napp/build/kotlin\napp/build/javac\n\n')
        archive = self.root / 'classes.tar'
        with redirect_stdout(io.StringIO()):
            kover_class_outputs.pack(artifact, archive, self.root)
        target = self.root / 'restored'
        target.mkdir()
        with redirect_stdout(io.StringIO()):
            kover_class_outputs.unpack(archive, target)
        self.assertEqual((target / 'app/build/kotlin/dev/A.class').read_text(encoding='utf-8'), 'kotlin')
        self.assertEqual((target / 'app/build/javac/dev/B.class').read_text(encoding='utf-8'), 'java')

    def test_pack_fails_when_no_class_directory_exists(self):
        artifact = self.write('a.artifact', 'src\n\napp/build/none\n\n')
        with self.assertRaisesRegex(ValueError, 'no existing class directories'):
            kover_class_outputs.pack(artifact, self.root / 'x.tar', self.root)

    def test_unpack_rejects_escaping_members(self):
        archive = self.root / 'bad.tar'
        with tarfile.open(archive, 'w') as tar:
            data = b'x'
            info = tarfile.TarInfo('../escape.class')
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        with self.assertRaisesRegex(ValueError, 'unsafe'):
            kover_class_outputs.unpack(archive, self.root)
        self.assertFalse(os.path.exists(self.root.parent / 'escape.class'))


if __name__ == '__main__':
    unittest.main()
