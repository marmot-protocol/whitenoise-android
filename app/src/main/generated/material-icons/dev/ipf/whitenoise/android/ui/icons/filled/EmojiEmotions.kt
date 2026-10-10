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

public val Icons.Filled.EmojiEmotions: ImageVector
    get() {
        if (_emojiEmotions != null) {
            return _emojiEmotions!!
        }
        _emojiEmotions = materialIcon(name = "Filled.EmojiEmotions") {
            materialPath {
                moveTo(11.99f, 2.0f)
                curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                curveToRelative(0.0f, 5.52f, 4.47f, 10.0f, 9.99f, 10.0f)
                curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                curveTo(22.0f, 6.48f, 17.52f, 2.0f, 11.99f, 2.0f)
                close()
                moveTo(8.5f, 8.0f)
                curveTo(9.33f, 8.0f, 10.0f, 8.67f, 10.0f, 9.5f)
                reflectiveCurveTo(9.33f, 11.0f, 8.5f, 11.0f)
                reflectiveCurveTo(7.0f, 10.33f, 7.0f, 9.5f)
                reflectiveCurveTo(7.67f, 8.0f, 8.5f, 8.0f)
                close()
                moveTo(12.0f, 18.0f)
                curveToRelative(-2.28f, 0.0f, -4.22f, -1.66f, -5.0f, -4.0f)
                horizontalLineToRelative(10.0f)
                curveTo(16.22f, 16.34f, 14.28f, 18.0f, 12.0f, 18.0f)
                close()
                moveTo(15.5f, 11.0f)
                curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveTo(14.67f, 8.0f, 15.5f, 8.0f)
                reflectiveCurveTo(17.0f, 8.67f, 17.0f, 9.5f)
                reflectiveCurveTo(16.33f, 11.0f, 15.5f, 11.0f)
                close()
            }
        }
        return _emojiEmotions!!
    }

private var _emojiEmotions: ImageVector? = null
