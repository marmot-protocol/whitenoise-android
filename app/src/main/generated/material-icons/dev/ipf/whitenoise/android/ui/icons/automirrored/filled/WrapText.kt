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

package dev.ipf.whitenoise.android.ui.icons.automirrored.filled

import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.materialIcon
import dev.ipf.whitenoise.android.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.AutoMirrored.Filled.WrapText: ImageVector
    get() {
        if (_wrapText != null) {
            return _wrapText!!
        }
        _wrapText = materialIcon(name = "AutoMirrored.Filled.WrapText", autoMirror = true) {
            materialPath {
                moveTo(4.0f, 19.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 17.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(20.0f, 5.0f)
                lineTo(4.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(16.0f)
                lineTo(20.0f, 5.0f)
                close()
                moveTo(17.0f, 11.0f)
                lineTo(4.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(13.25f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                lineTo(15.0f, 17.0f)
                verticalLineToRelative(-2.0f)
                lineToRelative(-3.0f, 3.0f)
                lineToRelative(3.0f, 3.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
                close()
            }
        }
        return _wrapText!!
    }

private var _wrapText: ImageVector? = null
