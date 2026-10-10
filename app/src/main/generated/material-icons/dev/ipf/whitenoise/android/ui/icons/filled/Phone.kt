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

public val Icons.Filled.Phone: ImageVector
    get() {
        if (_phone != null) {
            return _phone!!
        }
        _phone = materialIcon(name = "Filled.Phone") {
            materialPath {
                moveTo(6.62f, 10.79f)
                curveToRelative(1.44f, 2.83f, 3.76f, 5.14f, 6.59f, 6.59f)
                lineToRelative(2.2f, -2.2f)
                curveToRelative(0.27f, -0.27f, 0.67f, -0.36f, 1.02f, -0.24f)
                curveToRelative(1.12f, 0.37f, 2.33f, 0.57f, 3.57f, 0.57f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineTo(20.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                curveToRelative(-9.39f, 0.0f, -17.0f, -7.61f, -17.0f, -17.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(3.5f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                curveToRelative(0.0f, 1.25f, 0.2f, 2.45f, 0.57f, 3.57f)
                curveToRelative(0.11f, 0.35f, 0.03f, 0.74f, -0.25f, 1.02f)
                lineToRelative(-2.2f, 2.2f)
                close()
            }
        }
        return _phone!!
    }

private var _phone: ImageVector? = null
