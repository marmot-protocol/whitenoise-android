"""Stage only the shared test fixture on the reviewed current-master runtime."""

from pathlib import Path
import shutil
import sys


BASELINE = '34646a577807f2b112ee031f3c164ec58aa18b64'
# Qualification sources whose MarmotKit API differs on the baseline, swapped in after the copy. None differ between
# the 0.18.0 baseline and the candidate, so the directory holds no overlay now.
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
