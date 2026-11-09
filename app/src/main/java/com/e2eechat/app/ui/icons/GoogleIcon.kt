package com.e2eechat.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Official multi-colored Google "G" logo.
 * Blue: #4285F4 | Green: #34A853 | Yellow: #FBBC05 | Red: #EA4335
 */
val GoogleGLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "GoogleGLogo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Blue Path
        addPath(
            pathData = PathParser().parsePathString(
                "M24 12.25c0-.82-.07-1.61-.2-2.37H12v4.48h6.73c-.29 1.57-1.18 2.89-2.5 3.78v3.15h4.05c2.37-2.18 3.72-5.39 3.72-9.04z"
            ).toNodes(),
            fill = SolidColor(Color(0xFF4285F4))
        )
        // Green Path
        addPath(
            pathData = PathParser().parsePathString(
                "M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
            ).toNodes(),
            fill = SolidColor(Color(0xFF34A853))
        )
        // Yellow Path
        addPath(
            pathData = PathParser().parsePathString(
                "M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
            ).toNodes(),
            fill = SolidColor(Color(0xFFFBBC05))
        )
        // Red Path
        addPath(
            pathData = PathParser().parsePathString(
                "M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
            ).toNodes(),
            fill = SolidColor(Color(0xFFEA4335))
        )
    }.build()
}
