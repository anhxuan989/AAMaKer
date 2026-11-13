package com.aamaker.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Apple Liquid Glass Design System
object AppleGlass {
    val PureBlack = Color(0xFF000000)
    val FrostedDark = Color(0xFF0A0A0D).copy(alpha = 0.90f)
    val GlassCard = Color(0xFF15151A).copy(alpha = 0.72f)
    val GlassCardHigh = Color(0xFF1E1E24).copy(alpha = 0.78f)
    val GlassSurface = Color(0xFF101014).copy(alpha = 0.65f)
    val GlassInner = Color(0xFF1C1C22).copy(alpha = 0.50f)
    val GlassInteractive = Color(0x24FFFFFF)
    
    val AppleBlue = Color(0xFF0A84FF)
    val AppleBlueGlass = Color(0xFF0A84FF).copy(alpha = 0.18f)
    val AppleGreen = Color(0xFF30D158)
    val AppleGreenGlass = Color(0xFF30D158).copy(alpha = 0.16f)
    val AppleRed = Color(0xFFFF453A)
    val AppleRedGlass = Color(0xFFFF453A).copy(alpha = 0.18f)
    val AppleOrange = Color(0xFFFF9F0A)
    val AppleIndigo = Color(0xFF5E5CE6)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0x99EBEBF5)
    val TextTertiary = Color(0x61EBEBF5)

    // Specular highlight on glass top border
    val SpecularBorder = BorderStroke(
        1.dp,
        Brush.verticalGradient(
            listOf(
                Color(0x4DFFFFFF), // 30% top light reflection
                Color(0x14FFFFFF)  // 8% bottom rim
            )
        )
    )

    val ThinBorder = BorderStroke(1.dp, Color(0x28FFFFFF))
    val ActiveBorder = BorderStroke(1.5.dp, Color(0xFF0A84FF).copy(alpha = 0.85f))
    val GreenBorder = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.45f))

    val LiquidGradient = Brush.verticalGradient(
        listOf(
            Color(0x2EFFFFFF),
            Color(0x0AFFFFFF)
        )
    )

    val AmbientGlow = Brush.verticalGradient(
        listOf(
            Color(0xFF0A84FF).copy(alpha = 0.08f),
            Color.Transparent
        )
    )
}

private val AppleLiquidGlassColorScheme = darkColorScheme(
    primary = AppleGlass.AppleBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AppleGlass.AppleBlueGlass,
    onPrimaryContainer = Color(0xFF82C2FF),
    secondary = AppleGlass.AppleGreen,
    onSecondary = Color(0xFF000000),
    secondaryContainer = AppleGlass.AppleGreenGlass,
    onSecondaryContainer = Color(0xFF75F594),
    tertiary = AppleGlass.AppleIndigo,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF5E5CE6).copy(alpha = 0.20f),
    onTertiaryContainer = Color(0xFFC7C6FF),
    background = AppleGlass.PureBlack,
    onBackground = Color(0xFFFFFFFF),
    surface = AppleGlass.PureBlack,
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF141418),
    onSurfaceVariant = AppleGlass.TextSecondary,
    surfaceContainerLowest = AppleGlass.PureBlack,
    surfaceContainerLow = Color(0xFF0A0A0D),
    surfaceContainer = Color(0xFF121216),
    surfaceContainerHigh = Color(0xFF1B1B22),
    surfaceContainerHighest = Color(0xFF262630),
    outline = Color(0x38FFFFFF),
    outlineVariant = Color(0x1AFFFFFF),
    error = AppleGlass.AppleRed,
    onError = Color(0xFF000000),
    errorContainer = AppleGlass.AppleRedGlass,
    onErrorContainer = Color(0xFFFF9E98)
)

// Apple iOS continuous corner curvature
private val AppleSquircleShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private val AppleSFProTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
        color = AppleGlass.TextPrimary
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
        color = AppleGlass.TextPrimary
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
        color = AppleGlass.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = AppleGlass.TextPrimary
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = AppleGlass.TextSecondary
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.1).sp,
        color = AppleGlass.TextPrimary
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = AppleGlass.TextSecondary
    )
)

@Composable
fun AAMaKerTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AppleLiquidGlassColorScheme,
        shapes = AppleSquircleShapes,
        typography = AppleSFProTypography,
        content = content
    )
}
