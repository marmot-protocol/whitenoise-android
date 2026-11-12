"""Stage only the shared test fixture on the reviewed current-master runtime."""

from pathlib import Path
import shutil
import sys


BASELINE = '827687ba7fdbb05ec1b8f17667ad919fedde7266'
# Qualification sources whose MarmotKit API differs on the baseline, swapped in after the copy. The 0.15.0 baseline
# and 0.16.0 candidate share every type these sources use, so none is needed now.
OVERLAY = Path('scripts/mdk-baseline-overlay')


def stage(destination):
    """Copy test sources and their baseline overlay only after confirming the published baseline; production code and
    bindings stay intact."""
    pin = destination / 'app/src/main/marmotkit/MARMOT_VERSION'
    if f'mdk-sha={BASELINE}' not in pin.read_text().splitlines():
        raise ValueError('Unreviewed baseline runtime')
    relative = Path('app/src/androidTest/java/dev/ipf/whitenoise/android/qualification')
    shutil.copytree(relative, destination / relative, dirs_exist_ok=True)
    for source in sorted(OVERLAY.glob('*.kt.txt')):
        shutil.copyfile(source, destination / relative / source.name.removesuffix('.txt'))


if __name__ == '__main__':
    stage(Path(sys.argv[1]))
