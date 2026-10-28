package com.ouor.pocketboard.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// design-system §2.2. 다크 1종(B4), 다이내믹 컬러 없음(A6).
private val Scheme = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF1A2D60),
    primaryContainer = Color(0xFF2A3A6E),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFC1C5DD),
    secondaryContainer = Color(0xFF262B3D),
    onSecondaryContainer = Color(0xFFDDE1F9),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1A15),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E0F13),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF0E0F13),
    onSurface = Color(0xFFE3E2E9),
    surfaceContainerLowest = Color(0xFF0E0F13),
    surfaceContainerLow = Color(0xFF16171C),
    surfaceContainer = Color(0xFF1B1C21),
    surfaceContainerHigh = Color(0xFF25262B),
    surfaceContainerHighest = Color(0xFF2B2C31),
    surfaceVariant = Color(0xFF25262B),
    onSurfaceVariant = Color(0xFFA9AAB4),
    outline = Color(0xFF8E909A),
    outlineVariant = Color(0xFF2E3038),
)

/** M3 역할 밖의 상태·잉크 색(design-system §2.2 확장 토큰). */
@Immutable
data class PocketColors(
    val connected: Color = Color(0xFF7FD99A),
    val connecting: Color = Color(0xFFF2C46D),
    val problem: Color = Scheme.error,
    val idle: Color = Scheme.outline,
    val inkBlack: Color = Color.Black,
    // 쓰기 화면 전용, 창 밝기 10% 기준(sc09-writing-v2 §4.1). 안 보이면 값만 올린다.
    val inkFaint: Color = Color(0xFF262626),
    val inkDim: Color = Color(0xFF4A4A4A),
    val inkDimStrong: Color = Color(0xFF555555),
    val inkTrail: Color = Color(0xFF6E6E6E),
)

val LocalPocketColors = staticCompositionLocalOf { PocketColors() }

/** design-system §4 간격(dp). */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val gutter = 20.dp
    val button = 56.dp
}

private val Base = Typography()
private val Type = Typography(
    headlineMedium = Base.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = Base.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium),
    titleMedium = Base.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val ShapeSet = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPocketColors provides PocketColors()) {
        MaterialTheme(colorScheme = Scheme, typography = Type, shapes = ShapeSet, content = content)
    }
}

val MaterialTheme.pocket: PocketColors
    @Composable get() = LocalPocketColors.current
