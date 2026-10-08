package xyz.leedaud.echo.ui

import android.graphics.Paint
import android.graphics.RectF
import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.core.graphics.PathParser
import androidx.core.view.WindowCompat
import org.json.JSONObject
import kotlin.math.*

// Values are taken from upstream/memos/web/src/themes/default[-dark].css.
private fun oklch(lightness: Double, chroma: Double, hue: Double): Color {
    val a = chroma * cos(hue * PI / 180)
    val b = chroma * sin(hue * PI / 180)
    val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    fun channel(value: Double) = (if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1 / 2.4) - 0.055).coerceIn(0.0, 1.0).toFloat()
    return Color(channel(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
        channel(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
        channel(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s))
}

data class MemosPalette(val background: Color, val foreground: Color, val card: Color,
    val muted: Color, val mutedForeground: Color, val border: Color, val sidebar: Color, val popover: Color,
    val cardForeground: Color = foreground)
private val LightPalette = MemosPalette(oklch(.9818, .0054, 95.0986), oklch(.2438, .0269, 95.7226), Color.White,
    oklch(.9341, .0153, 90.239), oklch(.5559, .0075, 97.4233), oklch(.8847, .0069, 97.3627),
    oklch(.9663, .008, 98.8792), Color.White, oklch(.1908, .002, 106.5859))
private val DarkPalette = MemosPalette(oklch(.24, .008, 255.0), oklch(.9, .006, 255.0), oklch(.275, .009, 255.0),
    oklch(.35, .011, 255.0), oklch(.72, .007, 255.0), oklch(.38, .01, 255.0),
    oklch(.21, .009, 255.0), oklch(.32, .01, 255.0))
val LocalMemosPalette = staticCompositionLocalOf { LightPalette }
val MemosTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.5.sp, letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.sp))

@Composable fun MemosTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalMemosPalette provides p, LocalContentColor provides p.foreground, LocalMinimumInteractiveComponentSize provides 44.dp) {
        MaterialTheme(colorScheme = colors.copy(background = p.background, surface = p.card,
            surfaceContainer = p.popover, surfaceTint = Color.Transparent, onSurface = p.foreground,
            onBackground = p.foreground, onSurfaceVariant = p.mutedForeground, outline = p.border,
            outlineVariant = p.border.copy(alpha = .7f), primary = p.foreground, secondaryContainer = p.muted),
            typography = MemosTypography, shapes = Shapes(small = RoundedCornerShape(4.dp),
                medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp)), content = content)
    }
}

@Composable fun MemosCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalMemosPalette.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = p.card, contentColor = p.cardForeground,
        border = BorderStroke(1.dp, p.border.copy(alpha = .7f))) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable fun MemosButton(label: String, icon: String?, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalMemosPalette.current
    Surface(onClick, modifier.heightIn(min = 44.dp).alpha(if (enabled) 1f else .5f), enabled = enabled,
        shape = RoundedCornerShape(6.dp), color = Color.Transparent, contentColor = p.mutedForeground.copy(alpha = .7f),
        border = BorderStroke(1.dp, p.border.copy(alpha = .7f))) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
        icon?.let { MemosIcon(it, size = 16); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal, fontSize = 13.sp))
        }
    }
}

@Composable fun MemosInsertButton(modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalMemosPalette.current
    Surface(onClick, modifier.size(44.dp).alpha(if (enabled) 1f else .5f).semantics { contentDescription = "添加附件或内容" },
        enabled = enabled, shape = RoundedCornerShape(6.dp), color = p.background, contentColor = p.foreground,
        border = BorderStroke(1.dp, p.border), shadowElevation = 1.dp) {
        Box(contentAlignment = Alignment.Center) { MemosIcon("plus", 16) }
    }
}

@Composable fun MemosIconButton(icon: String, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, size: Int = 44, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides size.dp) {
    IconButton(onClick, modifier.size(size.dp).semantics { contentDescription = label }, enabled = enabled) {
        MemosIcon(icon, size = if (size < 32) 16 else 18)
    }
    }
}

@Composable fun MemosIcon(name: String, size: Int = 16) {
    val context = LocalContext.current
    val icons = remember { JSONObject(context.assets.open("memos-icons.json").bufferedReader().use { it.readText() }) }
    val nodes = remember(name) { icons.getJSONArray(name) }
    val color = LocalContentColor.current
    Canvas(Modifier.size(size.dp)) {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.scale(this.size.width / 24f, this.size.height / 24f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
            style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONArray(i); val attrs = node.getJSONObject(1)
            fun n(key: String, fallback: Double = 0.0) = attrs.optDouble(key, fallback).toFloat()
            when (node.getString(0)) {
                "path" -> canvas.drawPath(PathParser.createPathFromPathData(attrs.getString("d"))!!, paint)
                "line" -> canvas.drawLine(n("x1"), n("y1"), n("x2"), n("y2"), paint)
                "circle" -> canvas.drawCircle(n("cx"), n("cy"), n("r"), paint)
                "rect" -> canvas.drawRoundRect(RectF(n("x"), n("y"), n("x") + n("width"), n("y") + n("height")), n("rx"), n("rx"), paint)
                "polyline", "polygon" -> {
                    val points = attrs.getString("points").trim().split(Regex("[ ,]+" )).map(String::toFloat)
                    val path = android.graphics.Path().apply {
                        moveTo(points[0], points[1]); for (j in 2 until points.size step 2) lineTo(points[j], points[j + 1])
                        if (node.getString(0) == "polygon") close()
                    }
                    canvas.drawPath(path, paint)
                }
                "ellipse" -> canvas.drawOval(RectF(n("cx") - n("rx"), n("cy") - n("ry"), n("cx") + n("rx"), n("cy") + n("ry")), paint)
            }
        }
        canvas.restore()
    }
}
