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

public val Icons.Filled.Edit: ImageVector
    get() {
        if (_edit != null) {
            return _edit!!
        }
        _edit = materialIcon(name = "Filled.Edit") {
            materialPath {
                moveTo(3.0f, 17.25f)
                verticalLineTo(21.0f)
                horizontalLineToRelative(3.75f)
                lineTo(17.81f, 9.94f)
                lineToRelative(-3.75f, -3.75f)
                lineTo(3.0f, 17.25f)
                close()
                moveTo(20.71f, 7.04f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineToRelative(-2.34f, -2.34f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                lineToRelative(-1.83f, 1.83f)
                lineToRelative(3.75f, 3.75f)
                lineToRelative(1.83f, -1.83f)
                close()
            }
        }
        return _edit!!
    }

private var _edit: ImageVector? = null
