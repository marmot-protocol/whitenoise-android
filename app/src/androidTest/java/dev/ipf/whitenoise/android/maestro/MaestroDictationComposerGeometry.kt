package dev.ipf.whitenoise.android.maestro

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** The actual production composer's measured height must grow after the resize gesture. */
internal class MaestroDictationComposerGeometry {
    var height by mutableIntStateOf(0)
    private var baseline = 0
    var expandedVerified = false
        private set

    fun saveBaseline() {
        check(height > 0 && baseline == 0)
        baseline = height
    }

    fun verifyExpanded() {
        check(baseline > 0 && height > baseline) { "Resize gesture did not expand the mounted composer" }
        expandedVerified = true
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun MaestroDictationGeometryControls(
    fixture: MaestroPresentationFixture,
    geometry: MaestroDictationComposerGeometry,
) {
    Button(onClick = {
        geometry.saveBaseline()
        fixture.record("compact-geometry-saved")
    }) { Text("Record compact composer geometry") }
    Button(onClick = {
        geometry.verifyExpanded()
        fixture.record("expanded-geometry-verified")
    }) { Text("Check expanded composer geometry") }
}
