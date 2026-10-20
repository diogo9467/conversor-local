// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.officelocal

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val OfficeBlue = Color(0xFF185ABD)
val OfficeNavy = Color(0xFF122B50)

@Composable fun OfficeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val activity = LocalActivity.current
    SideEffect {
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !dark
        } }
    }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFA8C8FF), onPrimary = Color(0xFF102F60),
        primaryContainer = Color(0xFF223D62), onPrimaryContainer = Color(0xFFDCE9FF),
        secondary = Color(0xFFB2C6E3), secondaryContainer = Color(0xFF253448),
        background = Color(0xFF121923), onBackground = Color(0xFFE8EDF5),
        surface = Color(0xFF1B2431), onSurface = Color(0xFFE8EDF5),
        surfaceVariant = Color(0xFF283446), onSurfaceVariant = Color(0xFFB6C3D5),
        outline = Color(0xFF7C8A9F), outlineVariant = Color(0xFF354358),
        surfaceContainer = Color(0xFF1B2431), surfaceContainerLow = Color(0xFF1B2431),
        surfaceContainerHigh = Color(0xFF253144), surfaceContainerHighest = Color(0xFF2D3B50)
    ) else lightColorScheme(
        primary = OfficeBlue, onPrimary = Color.White,
        primaryContainer = Color(0xFFE6EFFF), onPrimaryContainer = Color(0xFF123C78),
        secondary = Color(0xFF43658D), secondaryContainer = Color(0xFFEDF2FA),
        background = Color(0xFFF4F6FA), onBackground = Color(0xFF202B3D),
        surface = Color.White, onSurface = Color(0xFF202B3D),
        surfaceVariant = Color(0xFFEDF2FA), onSurfaceVariant = Color(0xFF596A80),
        outline = Color(0xFF7C8CA2), outlineVariant = Color(0xFFDCE3ED),
        surfaceContainer = Color.White, surfaceContainerLow = Color.White,
        surfaceContainerHigh = Color(0xFFF0F4FA), surfaceContainerHighest = Color(0xFFE6EDF7)
    )
    val base = Typography()
    MaterialTheme(colorScheme = colors,
        shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp)),
        typography = Typography(
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
        ), content = content)
}

@Composable fun OfficeAppBar(title: String = "Office Local", onHome: (() -> Unit)? = null) {
    val chrome = if (isSystemInDarkTheme()) OfficeNavy else OfficeBlue
    Surface(color = chrome, contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 58.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onHome != null) IconButton(onClick = onHome, modifier = Modifier.semantics { contentDescription = "Página inicial" }) { OfficeGlyph("back", color = Color.White) }
            else OfficeGlyph("document", Modifier.size(28.dp), Color.White)
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("Offline", style = MaterialTheme.typography.labelMedium, color = Color(0xFFDCE9FF))
        }
    }
}

/** Small original line icons; no external font, image, or network dependency. */
@Composable fun OfficeGlyph(kind: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.65f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, ex: Float, ey: Float) = drawLine(color, Offset(x, y), Offset(ex, ey), 1.65f, StrokeCap.Round)
            when (kind) {
                "home" -> { drawPath(Path().apply { moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f); moveTo(5f, 10f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 15f); lineTo(14f, 15f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 10f) }, color, style = stroke) }
                "folder" -> { drawPath(Path().apply { moveTo(3f, 7f); lineTo(3f, 20f); lineTo(21f, 20f); lineTo(21f, 7f); lineTo(11f, 7f); lineTo(9f, 4f); lineTo(3f, 4f); close() }, color, style = stroke); line(4f, 10f, 20f, 10f) }
                "convert" -> { line(4f, 7f, 20f, 7f); line(16f, 3f, 20f, 7f); line(16f, 11f, 20f, 7f); line(20f, 17f, 4f, 17f); line(8f, 13f, 4f, 17f); line(8f, 21f, 4f, 17f) }
                "recent" -> { drawCircle(color, 8.5f, Offset(12f, 12f), style = stroke); line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f) }
                "info" -> { drawCircle(color, 9f, Offset(12f, 12f), style = stroke); drawCircle(color, 1f, Offset(12f, 7f)); line(12f, 11f, 12f, 17f) }
                "back" -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
                "arrow" -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
                "sheet" -> { drawRoundRect(color, Offset(3f, 4f), Size(18f, 16f), CornerRadius(1f), style = stroke); line(3f, 10f, 21f, 10f); line(3f, 15f, 21f, 15f); line(9f, 4f, 9f, 20f); line(15f, 4f, 15f, 20f) }
                "slides" -> { drawRoundRect(color, Offset(2f, 4f), Size(20f, 14f), CornerRadius(1f), style = stroke); line(12f, 18f, 12f, 22f); line(8f, 22f, 16f, 22f); line(6f, 9f, 17f, 9f); line(6f, 13f, 13f, 13f) }
                else -> { drawPath(Path().apply { moveTo(5f, 2f); lineTo(14f, 2f); lineTo(20f, 8f); lineTo(20f, 22f); lineTo(5f, 22f); close(); moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f) }, color, style = stroke); line(9f, 12f, 16f, 12f); line(9f, 16f, 16f, 16f) }
            }
        }
    }
}
