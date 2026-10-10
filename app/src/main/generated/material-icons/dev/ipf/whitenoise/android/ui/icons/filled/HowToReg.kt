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
import androidx.compose.ui.graphics.PathFillType.Companion.EvenOdd
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.HowToReg: ImageVector
    get() {
        if (_howToReg != null) {
            return _howToReg!!
        }
        _howToReg = materialIcon(name = "Filled.HowToReg") {
            materialPath(pathFillType = EvenOdd) {
                moveTo(9.0f, 17.0f)
                lineToRelative(3.0f, -2.94f)
                curveToRelative(-0.39f, -0.04f, -0.68f, -0.06f, -1.0f, -0.06f)
                curveToRelative(-2.67f, 0.0f, -8.0f, 1.34f, -8.0f, 4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(9.0f)
                lineToRelative(-3.0f, -3.0f)
                close()
                moveTo(11.0f, 12.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
                reflectiveCurveToRelative(-4.0f, 1.79f, -4.0f, 4.0f)
                reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
            }
            materialPath(pathFillType = EvenOdd) {
                moveTo(15.47f, 20.5f)
                lineTo(12.0f, 17.0f)
                lineToRelative(1.4f, -1.41f)
                lineToRelative(2.07f, 2.08f)
                lineToRelative(5.13f, -5.17f)
                lineToRelative(1.4f, 1.41f)
                close()
            }
        }
        return _howToReg!!
    }

private var _howToReg: ImageVector? = null
