"""Exercise staging tag selection with recorded responses at the Git and GitHub boundary."""
import json
import subprocess
import unittest
from unittest.mock import patch

from scripts import tag_staging_build as staging


SOURCE = "a" * 40
TAG_OBJECT = "b" * 40
TAG = "staging/2026.11.28-1910"
REF = f"refs/tags/{TAG}"
APK = "whitenoise-staging-v8a-release-1910-aaaaaaa"


class StagingTagTest(unittest.TestCase):
    def setUp(self):
        """Supply one successful run and its matching downloadable artifact."""
        repository = {"full_name": staging.REPOSITORY}
        self.run_record = dict(
            id=123, repository=repository, head_repository=repository,
            path=".github/workflows/android-staging-apk.yml", head_branch="develop",
            event="push", status="completed", conclusion="success", head_sha=SOURCE,
            created_at="2026-11-28T12:46:19Z", run_number=1910,
        )
        self.artifact = dict(name=APK, expired=False, digest="sha256:" + "c" * 64)
        self.refs = b""
        self.writes = []
        api = patch.object(staging, "api", side_effect=self.api)
        command = patch.object(staging, "command", side_effect=self.command)
        self.addCleanup(api.stop)
        self.addCleanup(command.stop)
        api.start()
        command.start()

    def api(self, path):
        """Serve only the selected run and its own artifacts."""
        if path == "actions/runs/123":
            return self.run_record
        self.assertEqual(path, "actions/runs/123/artifacts?per_page=100")
        return {"artifacts": [self.artifact]}

    def command(self, *args):
        """Record writes and provide the commit's lock and current remote tag."""
        if args[:2] == ("git", "ls-remote"):
            return self.refs
        if args == ("git", "merge-base", "--is-ancestor", SOURCE, "HEAD"):
            return b""
        if args == ("git", "show", f"{SOURCE}:app/src/main/marmotkit/MARMOT_VERSION"):
            return ("artifact-id=0.20.0-rc.4\nmdk-sha=" + "d" * 40 + "\n").encode()
        self.assertEqual(args[:4], ("gh", "api", "--method", "POST"))
        self.writes.append(args[4:])
        return json.dumps({"sha": TAG_OBJECT}).encode()

    def test_tags_the_build_commit_and_records_its_pin_and_artifact(self):
        """A later develop tip cannot replace the selected build's recorded commit."""
        self.assertTrue(staging.publish_staging_tag("123").endswith(f"/tree/{TAG}"))
        tag, ref = self.writes
        self.assertEqual(tag[0], f"repos/{staging.REPOSITORY}/git/tags")
        self.assertIn(f"object={SOURCE}", tag)
        self.assertIn(f"tag={TAG}", tag)
        annotation = next(item for item in tag if item.startswith("message="))
        self.assertIn("MarmotKit: 0.20.0-rc.4", annotation)
        self.assertIn(f"Artifact: {APK}", annotation)
        self.assertEqual(ref, (f"repos/{staging.REPOSITORY}/git/refs",
                               "-f", f"ref={REF}", "-f", f"sha={TAG_OBJECT}"))

    def test_rejects_untrusted_or_failed_builds(self):
        """A different workflow, branch or unsuccessful run cannot create a tag."""
        for key, value in (("head_branch", "master"), ("conclusion", "failure"), ("path", "other.yml")):
            with self.subTest(key=key), patch.dict(self.run_record, {key: value}):
                with self.assertRaisesRegex(ValueError, "Choose a|completed successfully"):
                    staging.publish_staging_tag("123")
        self.assertEqual(self.writes, [])

    def test_rejects_a_build_without_its_signed_apk(self):
        """A fixture-only or expired run cannot be presented as a staging candidate."""
        self.artifact["expired"] = True
        with self.assertRaisesRegex(ValueError, "unexpired signed staging APK"):
            staging.publish_staging_tag("123")
        self.assertEqual(self.writes, [])

    def test_rejects_a_source_outside_develop_history(self):
        """A successful run cannot tag a commit outside the trusted branch history."""
        ancestry_check = ("git", "merge-base", "--is-ancestor", SOURCE, "HEAD")
        with patch.object(staging, "command", side_effect=subprocess.CalledProcessError(1, ancestry_check)):
            with self.assertRaises(subprocess.CalledProcessError) as failure:
                staging.publish_staging_tag("123")
        self.assertEqual(failure.exception.cmd, ancestry_check)
        self.assertEqual(self.writes, [])

    def test_rerun_keeps_the_existing_tag_and_rejects_collisions(self):
        """Retries reuse the original annotation and never move a published ref."""
        self.refs = f"{TAG_OBJECT}\t{REF}\n{SOURCE}\t{REF}^{{}}\n".encode()
        self.assertTrue(staging.publish_staging_tag("123").endswith(TAG))
        self.refs = f"{TAG_OBJECT}\t{REF}\n".encode()
        with self.assertRaisesRegex(ValueError, "already points to a different commit"):
            staging.publish_staging_tag("123")
        self.assertEqual(self.writes, [])

    def test_ref_creation_recovers_when_the_same_build_wins_a_race(self):
        """Two overlapping requests must leave one tag on the selected commit."""
        original = self.command

        def command(*args):
            """Publish the matching ref immediately before this request loses the race."""
            if args[4:5] == (f"repos/{staging.REPOSITORY}/git/refs",):
                self.refs = f"{SOURCE}\t{REF}^{{}}\n".encode()
                raise subprocess.CalledProcessError(1, args)
            return original(*args)

        with patch.object(staging, "command", side_effect=command):
            self.assertTrue(staging.publish_staging_tag("123").endswith(TAG))
