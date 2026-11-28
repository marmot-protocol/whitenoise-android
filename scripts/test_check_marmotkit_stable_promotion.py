"""Exercise production pin verification against a real staging tag and fixture MDK metadata."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import check_marmotkit_stable_promotion as promotion
from scripts import prepare_marmotkit_artifact as artifact


RC_SOURCE = "a" * 40
STABLE_SOURCE = "b" * 40
META = "c" * 40
DIGEST = "d" * 64
TAG = "staging/2026.11.28-1910"


class StablePromotionTest(unittest.TestCase):
    def setUp(self):
        """Create an annotated staging tag and a different working Stable lock."""
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = Path(work.name)
        self.lock = self.root / promotion.channel.LOCK
        self.selection_file = self.root / promotion.PROMOTION
        self.lock.parent.mkdir(parents=True)
        base_pin = artifact.parse_properties(promotion.ROOT / promotion.channel.LOCK)
        self.git("init", "-q")
        self.git("config", "user.name", "Staging fixture")
        self.git("config", "user.email", "fixture@example.com")
        self.write_pin(self.pin(base_pin, "0.20.0-rc.4", RC_SOURCE, "e" * 64))
        self.git("add", ".")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "Staging candidate")
        candidate = self.git("rev-parse", "HEAD").strip()
        self.git("update-ref", "refs/remotes/origin/develop", candidate)
        self.git("-c", "tag.gpgsign=false", "tag", "-a", TAG, "-m", "tested staging")
        self.selection = dict(staging_tag=TAG, staging_commit=candidate, mdk_meta_commit=META)
        self.selection_file.write_text(json.dumps(self.selection), encoding="utf-8")
        self.stable_pin = self.pin(base_pin, "0.20.0", STABLE_SOURCE, DIGEST)
        self.write_pin(self.stable_pin)
        self.responses = {
            f"commits/{META}": {"sha": META, "commit": {"verification": {"verified": True}}},
            f"compare/{META}...rc-meta?per_page=1": {"merge_base_commit": {"sha": META}},
            f"commits/{STABLE_SOURCE}": {"sha": STABLE_SOURCE, "parents": [{"sha": RC_SOURCE}]},
            f"contents/stable/latest.json?ref={META}": {
                "schema": 2, "channel": "stable", "version": "0.20.0",
                "promoted_from_rc": "0.20.0-rc.4", "source_sha": RC_SOURCE, "release_sha": STABLE_SOURCE,
                "tracks": {"marmotkit": {"tag": self.stable_pin["artifact-tag"], "assets": [{
                    "name": f"{self.stable_pin['archive-root']}.zip",
                    "url": self.stable_pin["artifact-url"], "sha256": DIGEST, "size": 1234,
                }]}},
            },
        }
        self.metadata = self.responses[f"contents/stable/latest.json?ref={META}"]
        network = patch.object(promotion, "mdk_json", side_effect=lambda path: self.responses[path])
        self.addCleanup(network.stop)
        self.network = network.start()

    def git(self, *args):
        """Run a Git fixture operation inside the temporary repository."""
        return promotion.git(self.root, *args)

    def pin(self, base, version, source, digest):
        """Build a complete pin with the given release coordinates."""
        tag = f"marmotkit-v{version}"
        archive = f"marmotkit-android-{version}"
        return {
            **base, "artifact-id": version, "mdk-sha": source, "mdk-short-sha": source[:8],
            "artifact-tag": tag, "artifact-sha256": digest, "archive-root": archive,
            "artifact-url": f"https://github.com/marmot-protocol/mdk/releases/download/{tag}/{archive}.zip",
        }

    def write_pin(self, values):
        """Write a lock to the release working tree."""
        self.lock.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")

    def test_production_accepts_a_verified_promotion_for_tagged_rc(self):
        """The selected staging commit supplies the RC even when the working lock has changed."""
        promotion.check_production_pin(self.root)

    def test_production_accepts_stable_built_directly_from_the_tested_source(self):
        """A promotion can name the original RC source as the Stable build commit."""
        self.metadata["release_sha"] = RC_SOURCE
        self.write_pin(self.pin(self.stable_pin, "0.20.0", RC_SOURCE, DIGEST))
        promotion.check_production_pin(self.root)

    def test_rejects_an_unrelated_rc_or_stable_release(self):
        """A matching Stable version alone cannot authorize a different build."""
        for key, value, error in (
            ("promoted_from_rc", "0.20.0-rc.3", "different RC"),
            ("source_sha", STABLE_SOURCE, "different RC"),
            ("release_sha", RC_SOURCE, "published release commit"),
        ):
            with self.subTest(key=key), patch.dict(self.metadata, {key: value}):
                with self.assertRaisesRegex(artifact.PreparationError, error):
                    promotion.check_production_pin(self.root)

    def test_rejects_unverified_or_off_branch_metadata(self):
        """Only verified records from the public promotion history are usable."""
        for path, replacement, error in (
            (f"commits/{META}", {"sha": META}, "verified signature"),
            (f"compare/{META}...rc-meta?per_page=1", {"merge_base_commit": {"sha": RC_SOURCE}}, "rc-meta history"),
        ):
            with self.subTest(path=path), patch.dict(self.responses, {path: replacement}):
                with self.assertRaisesRegex(artifact.PreparationError, error):
                    promotion.check_production_pin(self.root)

    def test_rejects_release_parent_or_android_digest_mismatch(self):
        """The release commit and the Android ZIP must agree with the promotion record."""
        with patch.dict(self.responses[f"commits/{STABLE_SOURCE}"], parents=[{"sha": META}]):
            with self.assertRaisesRegex(artifact.PreparationError, "directly based"):
                promotion.check_production_pin(self.root)
        self.write_pin({**self.stable_pin, "artifact-sha256": "f" * 64})
        with self.assertRaisesRegex(artifact.PreparationError, "SHA-256"):
            promotion.check_production_pin(self.root)

    def test_rejects_when_staging_tag_changed(self):
        """The recorded Android commit detects a tag that was moved."""
        self.git("add", promotion.channel.LOCK.as_posix())
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "Different staging commit")
        self.git("-c", "tag.gpgsign=false", "tag", "-f", TAG)
        with self.assertRaisesRegex(artifact.PreparationError, "no longer points"):
            promotion.check_production_pin(self.root)
        self.selection["staging_commit"] = self.git("rev-parse", "HEAD").strip()
        self.selection_file.write_text(json.dumps(self.selection), encoding="utf-8")
        with self.assertRaisesRegex(artifact.PreparationError, "origin/develop history"):
            promotion.check_production_pin(self.root)
        self.network.assert_not_called()

    def test_new_pin_requires_selection_and_existing_stable_pin_still_passes(self):
        """The known deployed pin stays usable and a new pin must provide staging evidence."""
        self.selection_file.unlink()
        with self.assertRaisesRegex(artifact.PreparationError, "record the staging tag"):
            promotion.check_production_pin(self.root)
        legacy = promotion.LEGACY_PIN
        self.write_pin(self.pin(self.stable_pin, legacy["artifact-id"], legacy["mdk-sha"], legacy["artifact-sha256"]))
        promotion.check_production_pin(self.root)
        self.network.assert_not_called()

    def test_develop_accepts_rc_but_validates_a_formal_stable_pin(self):
        """Daily RC updates need no production receipt, while a Stable update does."""
        self.selection_file.unlink()
        with self.assertRaisesRegex(artifact.PreparationError, "record the staging tag"):
            promotion.check_production_pin(self.root, if_stable=True)
        self.write_pin(self.pin(self.stable_pin, "0.20.0-rc.5", RC_SOURCE, DIGEST))
        promotion.check_production_pin(self.root, if_stable=True)
        with self.assertRaisesRegex(artifact.PreparationError, "final X.Y.Z"):
            promotion.check_production_pin(self.root)
        self.network.assert_not_called()
