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

package dev.ipf.whitenoise.android.ui.icons.outlined

import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.materialIcon
import dev.ipf.whitenoise.android.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.NewReleases: ImageVector
    get() {
        if (_newReleases != null) {
            return _newReleases!!
        }
        _newReleases = materialIcon(name = "Outlined.NewReleases") {
            materialPath {
                moveTo(23.0f, 12.0f)
                lineToRelative(-2.44f, -2.78f)
                lineToRelative(0.34f, -3.68f)
                lineToRelative(-3.61f, -0.82f)
                lineToRelative(-1.89f, -3.18f)
                lineTo(12.0f, 3.0f)
                lineTo(8.6f, 1.54f)
                lineTo(6.71f, 4.72f)
                lineToRelative(-3.61f, 0.81f)
                lineToRelative(0.34f, 3.68f)
                lineTo(1.0f, 12.0f)
                lineToRelative(2.44f, 2.78f)
                lineToRelative(-0.34f, 3.69f)
                lineToRelative(3.61f, 0.82f)
                lineToRelative(1.89f, 3.18f)
                lineTo(12.0f, 21.0f)
                lineToRelative(3.4f, 1.46f)
                lineToRelative(1.89f, -3.18f)
                lineToRelative(3.61f, -0.82f)
                lineToRelative(-0.34f, -3.68f)
                lineTo(23.0f, 12.0f)
                close()
                moveTo(18.49f, 14.11f)
                lineToRelative(0.26f, 2.79f)
                lineToRelative(-2.74f, 0.62f)
                lineToRelative(-1.43f, 2.41f)
                lineTo(12.0f, 18.82f)
                lineToRelative(-2.58f, 1.11f)
                lineToRelative(-1.43f, -2.41f)
                lineToRelative(-2.74f, -0.62f)
                lineToRelative(0.26f, -2.8f)
                lineTo(3.66f, 12.0f)
                lineToRelative(1.85f, -2.12f)
                lineToRelative(-0.26f, -2.78f)
                lineToRelative(2.74f, -0.61f)
                lineToRelative(1.43f, -2.41f)
                lineTo(12.0f, 5.18f)
                lineToRelative(2.58f, -1.11f)
                lineToRelative(1.43f, 2.41f)
                lineToRelative(2.74f, 0.62f)
                lineToRelative(-0.26f, 2.79f)
                lineTo(20.34f, 12.0f)
                lineToRelative(-1.85f, 2.11f)
                close()
                moveTo(11.0f, 15.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(11.0f, 7.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
        }
        return _newReleases!!
    }

private var _newReleases: ImageVector? = null
