"""Stage only the shared test fixture on the reviewed current-master runtime."""

from pathlib import Path
import shutil
import sys


BASELINE = '2854335c6e29f6bf236b1f986c62c1b9ab31114c'


def stage(destination):
    """Copy test sources only after confirming the published baseline; production code and bindings stay intact."""
    pin = destination / 'app/src/main/marmotkit/MARMOT_VERSION'
    if f'mdk-sha={BASELINE}' not in pin.read_text().splitlines():
        raise ValueError('Unreviewed baseline runtime')
    relative = Path('app/src/androidTest/java/dev/ipf/whitenoise/android/qualification')
    shutil.copytree(relative, destination / relative, dirs_exist_ok=True)


if __name__ == '__main__':
    stage(Path(sys.argv[1]))
