#!/usr/bin/env python3
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-pr-apk-to-blossom.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class BlossomPublicationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / 'preview.apk'
        self.apk.write_bytes(b'validated signed APK fixture')
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.url = f'{publisher.SERVER}/{self.digest}.apk'
        self.environment = patch.dict(os.environ, {'NOSTR_SECRET_KEY': 'test-identity', 'GH_TOKEN': 'must-not-reach-uploader'})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_existing_copy_never_uploads_or_writes_marker(self):
        with patch.object(publisher, 'readback', return_value=True), patch.object(publisher, 'gh', side_effect=AssertionError('effect')), patch.object(publisher.subprocess, 'run', side_effect=AssertionError('upload')):
            self.assertEqual(publisher.publish(self.apk, 'o/r'), self.url)

    def test_upload_is_guarded_and_secret_is_scoped(self):
        effects = []
        def gh(*args):
            effects.append('intent')
            marker = json.loads(Path(args[3]).read_text())
            self.assertEqual(marker['sha256'], self.digest)
            self.assertNotIn('--clobber', args)
        def upload(*args, **kwargs):
            effects.append('upload')
            self.assertNotIn('GH_TOKEN', kwargs['env'])
            self.assertEqual(kwargs['env']['NOSTR_SECRET_KEY'], 'test-identity')
            self.assertEqual(effects, ['intent', 'upload'])
            return subprocess.CompletedProcess(args, 0)
        with patch.object(publisher, 'readback', side_effect=[False, True]), patch.object(publisher, 'marker_exists', return_value=False), patch.object(publisher, 'gh', side_effect=gh), patch.object(publisher.subprocess, 'run', side_effect=upload):
            self.assertEqual(publisher.publish(self.apk, 'o/r'), self.url)

    def test_existing_intent_holds_unknown_outcome(self):
        with patch.object(publisher, 'readback', return_value=False), patch.object(publisher, 'marker_exists', return_value=True), patch.object(publisher.subprocess, 'run', side_effect=AssertionError('replay')):
            with self.assertRaisesRegex(RuntimeError, 'Prior Blossom'): publisher.publish(self.apk, 'o/r')

    def test_marker_write_failure_stops_before_upload(self):
        with patch.object(publisher, 'readback', return_value=False), patch.object(publisher, 'marker_exists', return_value=False), patch.object(publisher, 'gh', side_effect=TimeoutError), patch.object(publisher.subprocess, 'run', side_effect=AssertionError('upload')):
            with self.assertRaises(TimeoutError): publisher.publish(self.apk, 'o/r')

    def test_ambiguous_upload_requires_matching_readback(self):
        with patch.object(publisher, 'readback', side_effect=[False, False]), patch.object(publisher, 'marker_exists', return_value=False), patch.object(publisher, 'gh'), patch.object(publisher.subprocess, 'run', side_effect=subprocess.TimeoutExpired('nak', 240)) as run:
            with self.assertRaisesRegex(RuntimeError, 'outcome unresolved'): publisher.publish(self.apk, 'o/r')
            self.assertEqual(run.call_count, 1)

    def test_lost_upload_response_can_adopt_matching_readback(self):
        with patch.object(publisher, 'readback', side_effect=[False, True]), patch.object(publisher, 'marker_exists', return_value=False), patch.object(publisher, 'gh'), patch.object(publisher.subprocess, 'run', side_effect=subprocess.TimeoutExpired('nak', 240)):
            self.assertEqual(publisher.publish(self.apk, 'o/r'), self.url)

    def test_unknown_initial_read_cannot_admit_upload(self):
        with patch.object(publisher, 'readback', side_effect=TimeoutError), patch.object(publisher, 'marker_exists', side_effect=AssertionError('effect')):
            with self.assertRaises(TimeoutError): publisher.publish(self.apk, 'o/r')

    def test_no_default_or_generated_signing_identity(self):
        with patch.dict(os.environ, {'NOSTR_SECRET_KEY': ''}), patch.object(publisher, 'readback', return_value=False), patch.object(publisher, 'marker_exists', side_effect=AssertionError('effect')):
            with self.assertRaisesRegex(RuntimeError, 'identity required'): publisher.publish(self.apk, 'o/r')

    def test_readback_rejects_redirect_and_changed_bytes(self):
        for data, url in [(b'wrong', self.url), (self.apk.read_bytes(), 'https://other.invalid/blob')]:
            response = io.BytesIO(data)
            response.geturl = lambda: url
            with patch.object(publisher.urllib.request, 'urlopen', return_value=response):
                with self.assertRaises(RuntimeError): publisher.readback(self.url, self.digest, self.apk.stat().st_size)

    def test_redirected_404_never_admits_upload(self):
        error = urllib.error.HTTPError('https://other.invalid/missing', 404, '', {}, None)
        with patch.object(publisher.urllib.request, 'urlopen', side_effect=error), patch.object(publisher, 'marker_exists', side_effect=AssertionError('upload admission')):
            with self.assertRaises(urllib.error.HTTPError): publisher.publish(self.apk, 'o/r')

    def test_only_missing_blob_allows_publication(self):
        for code in (404, 403, 500):
            error = urllib.error.HTTPError(self.url, code, '', {}, None)
            with patch.object(publisher.urllib.request, 'urlopen', side_effect=error):
                if code == 404: self.assertFalse(publisher.readback(self.url, self.digest, 1))
                else:
                    with self.assertRaises(urllib.error.HTTPError): publisher.readback(self.url, self.digest, 1)


if __name__ == '__main__':
    unittest.main()
