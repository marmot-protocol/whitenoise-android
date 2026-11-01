"""Select which emulator lanes a PR's complete diff can affect.

The workflow's own PR `paths` list decides whether the instrumented workflow
starts at all. This narrows it further: fixture-only inputs do not boot the
connected-test emulator, and changes outside the fixture's inputs do not boot
the two attachment-fixture emulators. A fixture change confined to the focused
video-metadata scenario's own inputs runs only that scenario. A change to the
dispatch plumbing every scenario shares runs the whole campaign, because the
workflow has no merge-queue run to catch what a narrower PR run skipped. Every
other event, and any diff that cannot be read or classified, runs both lanes
and the whole fixture campaign.
"""

import argparse
import os
from pathlib import Path
import re
import subprocess

from scripts.check_fuzz_pr_triggers import TriggerPolicyError, pr_paths

WORKFLOW = '.github/workflows/android-instrumented.yml'

# Only the controlled attachment fixture reads these. None carries the
# @PullRequestDeviceSmoke annotation the PR connected run selects.
FIXTURE_ONLY = (
    'tools/attachment-fixture/**',
    'scripts/run-controlled-attachment-fixture.sh',
    'scripts/media-latency.init.gradle',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/ControlledAttachmentProbe.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/MediaAttachmentLatencyProbe.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/NativePhaseAttachmentProbe.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/AndroidAttachmentFixtureSender.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/MediaLifecycleAttachmentProbe.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/ForwardAttachmentProbe.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/FixtureMediaAssets.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/ApkInstallerAttachmentProbe.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/FixtureSession.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/MatrixAttachmentProbe.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/IncomingAttachmentBackgroundDownloadAndroidTest.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/BackgroundReceiptFixtureSupport.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/BackgroundReceiptNetworkDiagnostics.kt',
)

# Production and build inputs the fixture exercises alongside other device tests.
FIXTURE_SHARED = (
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/BackgroundReceiptPolicyDiagnostics.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/media/BackgroundReceiptProcessStopDiagnostics.kt',
    '.github/workflows/android-instrumented.yml',
    'scripts/select_instrumented_lanes.py',
    'app/build.gradle.kts',
    'settings.gradle.kts',
    'gradle/libs.versions.toml',
    'app/src/androidTest/AndroidManifest.xml',
    'app/src/main/marmotkit/**',
    'app/src/main/java/dev/ipf/whitenoise/android/state/AttachmentPlaintextResolver.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/state/IncomingAttachmentScheduling.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/state/AutomaticAttachmentPolicy.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentPermissions.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/state/AttachmentDownloadWorker.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/state/AutomaticAttachmentNetworkWaits.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/state/AppStateForwardTransport.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/state/MessageForwarding.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/media/MediaPipeline.kt',
)

# The focused #3376 video-metadata scenario reads only these test inputs, and
# none carries the @PullRequestDeviceSmoke annotation.
VIDEO_METADATA_FIXTURE_ONLY = (
    'tools/attachment-fixture/video_metadata_runner.py',
    'tools/attachment-fixture/video_metadata_checker.py',
    'tools/attachment-fixture/test_video_metadata_runner.py',
    'tools/attachment-fixture/test_video_metadata_checker.py',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/ui/conversation/media/VideoMetadataFixtureDeviceTest.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/ui/conversation/media/VideoMetadataFixtureEvidence.kt',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/ui/conversation/media/GeneratedVideoDocuments.java',
    'app/src/androidTest/java/dev/ipf/whitenoise/android/ui/conversation/media/GeneratedDocumentProvider.java',
    # Routing that only the video-metadata send exercises on a device.
    'app/src/main/java/dev/ipf/whitenoise/android/media/VideoContentClassifier.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/media/UncleanableContainerStreams.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/VideoMetadataRemoval.kt',
)

# Production scrubber inputs and fixtures: the scenario sends through them and
# the connected lane's platform proof reads them too.
VIDEO_METADATA_SHARED = (
    'app/src/main/java/dev/ipf/whitenoise/android/media/VideoMetadataScrubber.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/media/VideoScrubStore.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/media/IsoBmffMetadataScrubber.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/media/IsoBoxEditor.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/media/IsoSampleZeroing.kt',
    'app/src/main/java/dev/ipf/whitenoise/android/media/MatroskaMetadataScrubber.kt',
    'app/src/androidTest/assets/video-metadata/**',
)

# Plumbing that dispatches every fixture scenario, so a change to it always runs
# the whole campaign, even beside video-metadata inputs.
FIXTURE_DISPATCH = (
    '.github/workflows/android-instrumented.yml',
    'scripts/select_instrumented_lanes.py',
    'scripts/run-controlled-attachment-fixture.sh',
    'tools/attachment-fixture/README.md',
)

FULL_FIXTURE = 'full'
VIDEO_METADATA_FIXTURE = 'video-metadata'
BOTH = {'attachment_fixture': True, 'attachment_fixture_scope': FULL_FIXTURE, 'connected': True}


def glob_regex(pattern):
    """GitHub path filters: `**` crosses directories, `*` and `?` do not."""
    out = []
    index = 0
    while index < len(pattern):
        if pattern.startswith('**', index):
            out.append('.*')
            index += 2
        elif pattern[index] == '*':
            out.append('[^/]*')
            index += 1
        elif pattern[index] == '?':
            out.append('[^/]')
            index += 1
        else:
            out.append(re.escape(pattern[index]))
            index += 1
    return re.compile(''.join(out))


def matches(path, patterns):
    return any(glob_regex(pattern).fullmatch(path) for pattern in patterns)


def changed_paths(base, head):
    """Return every path in the PR's merge-base diff, or None when unavailable."""
    if not all(re.fullmatch(r'[0-9a-f]{40}', ref or '') for ref in (base, head)):
        return None
    try:
        diff = subprocess.run(
            ['git', 'diff', '--name-only', '--no-renames', '-z', f'{base}...{head}'],
            capture_output=True, check=True, timeout=30,
        )
        paths = diff.stdout.decode('utf-8').split('\0')
    except (OSError, subprocess.SubprocessError, UnicodeDecodeError):
        return None
    if not paths or paths[-1] != '':
        return None
    return paths[:-1] or None


def fixture_scope(paths):
    """The fixture campaign [paths] can affect: the whole campaign, only the video-metadata scenario, or none."""
    video = VIDEO_METADATA_FIXTURE_ONLY + VIDEO_METADATA_SHARED
    focused = any(matches(path, video) for path in paths)
    other = any(matches(path, FIXTURE_ONLY + FIXTURE_SHARED + FIXTURE_DISPATCH) and not matches(path, video)
                for path in paths)
    if other:
        return FULL_FIXTURE
    return VIDEO_METADATA_FIXTURE if focused else ''


def select(event, paths, triggers):
    """Fail open: anything other than a readable PR diff runs both lanes."""
    if event != 'pull_request' or not paths or not triggers:
        return dict(BOTH)
    triggering = [path for path in paths if matches(path, triggers)]
    if not triggering:
        # The workflow started, so some path matched. A mismatch with GitHub's
        # matcher means this reader cannot be trusted for this diff.
        return dict(BOTH)
    scope = fixture_scope(paths)
    return {
        'attachment_fixture': bool(scope),
        'attachment_fixture_scope': scope,
        'connected': any(not matches(path, FIXTURE_ONLY + VIDEO_METADATA_FIXTURE_ONLY) for path in triggering),
    }


def output_value(value):
    """A GitHub step output: booleans as true or false, and a scope as its name."""
    if isinstance(value, bool):
        return 'true' if value else 'false'
    return value


def main():
    """Print the selected lanes and fixture scope, and append them to the step outputs on GitHub."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', default='')
    args = parser.parse_args()
    lanes = dict(BOTH)
    if args.event == 'pull_request':
        try:
            triggers = pr_paths((Path(__file__).resolve().parents[1] / WORKFLOW).read_text())
        except (OSError, TriggerPolicyError):
            triggers = None
        lanes = select(args.event, changed_paths(args.base, args.head), triggers)
    line = ''.join(f'{key}={output_value(value)}\n' for key, value in lanes.items())
    print(line, end='')
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a', encoding='utf-8') as stream:
            stream.write(line)


if __name__ == '__main__':
    main()
