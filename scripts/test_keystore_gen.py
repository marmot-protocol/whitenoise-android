"""Release keystore generation keeps signing secrets owner-only and off the process list."""

import os
import pathlib
import shutil
import stat
import subprocess
import tempfile
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent / "keystore-gen.sh"

FAKE_KEYTOOL = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    # Records its arguments and writes a placeholder keystore where asked.
    printf '%s\\n' "$@" > "$FAKE_LOG/keytool-args"
    printf '%s' "${KEYSTORE_PASSWORD:-}" > "$FAKE_LOG/keytool-env-password"
    while [[ $# -gt 0 ]]; do
      if [[ "$1" == "-keystore" ]]; then
        printf 'synthetic keystore' > "$2"
      fi
      shift
    done
    """
)

FAKE_OPENSSL = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    echo "c3ludGhldGljLXBhc3N3b3JkLWZvci10ZXN0cy0xMjM0"
    """
)


def mode(path: pathlib.Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


class KeystoreGenTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.repo = self.root / "repo"
        (self.repo / "scripts").mkdir(parents=True)
        shutil.copy(SCRIPT, self.repo / "scripts" / "keystore-gen.sh")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name, body in (("keytool", FAKE_KEYTOOL), ("openssl", FAKE_OPENSSL)):
            tool = self.bin / name
            tool.write_text(body)
            tool.chmod(0o755)
        self.log = self.root / "log"
        self.log.mkdir()
        self.keystore = self.root / "keystores" / "release.p12"
        self.props = self.repo / "local.properties"

    def run_script(self) -> subprocess.CompletedProcess:
        env = dict(os.environ, PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}", FAKE_LOG=str(self.log))
        env.pop("KEYSTORE_PASSWORD", None)
        # A permissive umask, as on many developer machines, must not leak the secrets.
        return subprocess.run(
            ["bash", "-c", 'umask 022; exec bash "$0" "$1"', str(self.repo / "scripts" / "keystore-gen.sh"),
             str(self.keystore)],
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_owner_only_outputs(self) -> str:
        self.assertEqual(0o600, mode(self.keystore))
        self.assertEqual(0o600, mode(self.props))
        self.assertEqual("synthetic keystore", self.keystore.read_text())
        self.assertEqual([], [p.name for p in self.keystore.parent.iterdir() if p != self.keystore])
        self.assertEqual(["local.properties", "scripts"], sorted(p.name for p in self.repo.iterdir()))
        return self.props.read_text()

    def test_new_files_are_owner_only_under_a_permissive_umask(self) -> None:
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        props = self.assert_owner_only_outputs()
        password = (self.log / "keytool-env-password").read_text()
        self.assertTrue(password)
        self.assertIn(f"WHITENOISE_KEYSTORE_PASSWORD={password}", props)
        self.assertIn(f"WHITENOISE_KEY_PASSWORD={password}", props)
        self.assertIn(f"WHITENOISE_KEYSTORE_PATH={self.keystore}", props)

    def test_passwords_never_reach_keytool_arguments(self) -> None:
        self.assertEqual(0, self.run_script().returncode)
        args = (self.log / "keytool-args").read_text().splitlines()
        password = (self.log / "keytool-env-password").read_text()
        self.assertNotIn(password, "\n".join(args))
        self.assertIn("-storepass:env", args)
        self.assertIn("-keypass:env", args)

    def test_existing_properties_become_owner_only_and_keep_other_lines(self) -> None:
        self.props.write_text(
            "sdk.dir=/opt/android-sdk\n"
            "WHITENOISE_KEYSTORE_PASSWORD=old-secret\n"
            "WHITENOISE_KEY_ALIAS=old-alias\n"
            "custom.flag=true\n"
        )
        self.props.chmod(0o644)
        self.assertEqual(0, self.run_script().returncode)
        props = self.assert_owner_only_outputs()
        self.assertIn("sdk.dir=/opt/android-sdk\n", props)
        self.assertIn("custom.flag=true\n", props)
        self.assertNotIn("old-secret", props)
        self.assertNotIn("old-alias", props)
        self.assertEqual(1, props.count("WHITENOISE_KEY_ALIAS="))

    def test_existing_keystore_is_never_replaced(self) -> None:
        self.keystore.parent.mkdir()
        self.keystore.write_text("shipped keystore")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("shipped keystore", self.keystore.read_text())
        self.assertFalse(self.props.exists())
        self.assertFalse((self.log / "keytool-args").exists())

    def test_symlinked_properties_are_refused(self) -> None:
        target = self.root / "elsewhere.properties"
        target.write_text("unchanged\n")
        self.props.symlink_to(target)
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("unchanged\n", target.read_text())
        self.assertFalse(self.keystore.exists())


if __name__ == "__main__":
    unittest.main()
