"""Copy only the test harness into current master; never backport application or generated binding changes."""

from pathlib import Path
import shutil
import sys


def stage(destination):
    """Fail closed unless the two reviewed constructor differences are the complete baseline adapter."""
    relative = Path('app/src/androidTest/java/dev/ipf/whitenoise/android/qualification')
    target = destination / relative
    if target.exists():
        raise ValueError('Baseline unexpectedly already has this fixture')
    shutil.copytree(relative, target)
    factory = target / 'UpgradeFixtureRuntime.kt'
    source = factory.read_text()
    for line in ('import dev.ipf.marmotkit.marmotDeploymentWhiteNoise\n',
                 '            deployment = marmotDeploymentWhiteNoise(),\n'):
        if source.count(line) != 1:
            raise ValueError('Unreviewed baseline constructor adapter')
        source = source.replace(line, '')
    factory.write_text(source)
    (target / 'UpgradeStorageMigration.kt').write_text('''package dev.ipf.whitenoise.android.qualification

import dev.ipf.marmotkit.Marmot

/** The old published API has no storage-usage endpoint; only the candidate measures background migration. */
@Suppress("UNUSED_PARAMETER")
internal suspend fun verifyUpgradeStorage(runtime: Marmot, phase: String, beforeStart: Boolean) = Unit
''')


if __name__ == '__main__':
    stage(Path(sys.argv[1]))
