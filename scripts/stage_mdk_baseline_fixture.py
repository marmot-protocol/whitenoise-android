"""Stage only the shared test fixture on the reviewed current-master runtime."""

from pathlib import Path
import shutil
import sys


BASELINE = '3fb4b4759ac676b0c21c4c3527074866c9fa4c89'


def stage(destination):
    """Copy test sources only after confirming the published baseline; production code and bindings stay intact."""
    pin = destination / 'app/src/main/marmotkit/MARMOT_VERSION'
    if f'mdk-sha={BASELINE}' not in pin.read_text().splitlines():
        raise ValueError('Unreviewed baseline runtime')
    relative = Path('app/src/androidTest/java/dev/ipf/whitenoise/android/qualification')
    shutil.copytree(relative, destination / relative, dirs_exist_ok=True)


if __name__ == '__main__':
    stage(Path(sys.argv[1]))
