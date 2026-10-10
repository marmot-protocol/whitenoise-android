/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ipf.whitenoise.android.ui.icons.filled

import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.materialIcon
import dev.ipf.whitenoise.android.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.CloudOff: ImageVector
    get() {
        if (_cloudOff != null) {
            return _cloudOff!!
        }
        _cloudOff = materialIcon(name = "Filled.CloudOff") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveToRelative(-1.48f, 0.0f, -2.85f, 0.43f, -4.01f, 1.17f)
                lineToRelative(1.46f, 1.46f)
                curveTo(10.21f, 6.23f, 11.08f, 6.0f, 12.0f, 6.0f)
                curveToRelative(3.04f, 0.0f, 5.5f, 2.46f, 5.5f, 5.5f)
                verticalLineToRelative(0.5f)
                horizontalLineTo(19.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
                curveToRelative(0.0f, 1.13f, -0.64f, 2.11f, -1.56f, 2.62f)
                lineToRelative(1.45f, 1.45f)
                curveTo(23.16f, 18.16f, 24.0f, 16.68f, 24.0f, 15.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(3.0f, 5.27f)
                lineToRelative(2.75f, 2.74f)
                curveTo(2.56f, 8.15f, 0.0f, 10.77f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(11.73f)
                lineToRelative(2.0f, 2.0f)
                lineTo(21.0f, 20.73f)
                lineTo(4.27f, 4.0f)
                lineTo(3.0f, 5.27f)
                close()
                moveTo(7.73f, 10.0f)
                lineToRelative(8.0f, 8.0f)
                horizontalLineTo(6.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
                horizontalLineToRelative(1.73f)
                close()
            }
        }
        return _cloudOff!!
    }

private var _cloudOff: ImageVector? = null
