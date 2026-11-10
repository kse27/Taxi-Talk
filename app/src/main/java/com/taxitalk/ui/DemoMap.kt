package com.taxitalk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot

private val Land = Color(0xFF101A28)
private val Park = Color(0xFF19302F)
private val Water = Color(0xFF132E45)
private val Road = Color(0xFF29384D)
private val RoadEdge = Color(0xFF45566D)
private val RouteAhead = TT.Speaking
private val RoutePassed = Color(0xFF69788D)

/** Route coordinates are a static presentation drawing, not navigation geometry. */
private val routePoints = listOf(
    0.20f to 0.62f,
    0.34f to 0.62f,
    0.34f to 0.42f,
    0.56f to 0.42f,
    0.56f to 0.24f,
    0.74f to 0.24f,
)

/**
 * Midnight Pearl demonstration map. It deliberately remains labelled as a demo,
 * because it is a Canvas concept rather than an actual navigation-map surface.
 */
@Composable
fun DemoMap(driving: Boolean, modifier: Modifier = Modifier) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(driving) {
        while (driving && progress < 1f) {
            kotlinx.coroutines.delay(50)
            progress = (progress + 0.001f).coerceAtMost(1f)
        }
    }
    val measurer = rememberTextMeasurer()

    Box(modifier) {
        Canvas(Modifier.fillMaxSize().background(Land)) {
            drawBackground()
            drawRoads()
            val points = routePoints.map { (x, y) -> Offset(x * size.width, y * size.height) }
            drawRoute(points, progress)

            fun label(text: String, x: Float, y: Float, color: Color, fontSize: Int, bold: Boolean = false) = drawText(
                measurer,
                text,
                Offset(x * size.width, y * size.height),
                TextStyle(color = color, fontSize = fontSize.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            )
            label("한강", 0.60f, 0.80f, Color(0xFF79A9C8), 24)
            label("남산공원", 0.76f, 0.47f, Color(0xFF92B6A0), 20)
            label("세종대로", 0.37f, 0.36f, TT.DriverSub, 16)
            label("통일로", 0.215f, 0.25f, TT.DriverSub, 16)
            label("서울역", 0.765f, 0.205f, TT.PearlIvory, 24, bold = true)
        }
        Text(
            "시연용 지도 · 실제 길안내가 아님",
            color = TT.DriverText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 76.dp, end = 22.dp)
                .background(TT.DriverBg.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun DrawScope.drawBackground() {
    val width = size.width
    val height = size.height
    drawOval(Park, Offset(width * 0.70f, height * 0.36f), androidx.compose.ui.geometry.Size(width * 0.24f, height * 0.26f))
    drawOval(Park, Offset(width * 0.04f, height * 0.08f), androidx.compose.ui.geometry.Size(width * 0.13f, height * 0.20f))
    val river = Path().apply {
        moveTo(0f, height * 0.84f)
        cubicTo(width * 0.25f, height * 0.70f, width * 0.55f, height * 0.92f, width, height * 0.68f)
        lineTo(width, height * 0.86f)
        cubicTo(width * 0.55f, height * 1.08f, width * 0.25f, height * 0.88f, 0f, height)
        close()
    }
    drawPath(river, Water)
}

private fun DrawScope.drawRoads() {
    val width = size.width
    val height = size.height
    val horizontal = listOf(0.12f to 10f, 0.24f to 16f, 0.42f to 22f, 0.62f to 22f, 0.76f to 10f)
    val vertical = listOf(0.10f to 10f, 0.20f to 16f, 0.34f to 22f, 0.56f to 22f, 0.74f to 14f, 0.90f to 10f)
    for (edge in listOf(true, false)) {
        val color = if (edge) RoadEdge else Road
        val extra = if (edge) 4f else 0f
        horizontal.forEach { (y, thickness) -> drawLine(color, Offset(0f, height * y), Offset(width, height * y), thickness + extra) }
        vertical.forEach { (x, thickness) -> drawLine(color, Offset(width * x, 0f), Offset(width * x, height), thickness + extra) }
    }
}

private fun DrawScope.drawRoute(points: List<Offset>, progress: Float) {
    val lengths = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
    var remaining = lengths.sum() * progress
    var index = 0
    while (index < lengths.lastIndex && remaining > lengths[index]) {
        remaining -= lengths[index]
        index++
    }
    val start = points[index]
    val end = points[index + 1]
    val fraction = (remaining / lengths[index]).coerceIn(0f, 1f)
    val car = Offset(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)

    fun line(route: List<Offset>, color: Color) {
        val path = Path().apply {
            moveTo(route[0].x, route[0].y)
            route.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color, style = Stroke(14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    line(points.take(index + 1) + car, RoutePassed)
    line(listOf(car) + points.drop(index + 1), RouteAhead)

    val destination = points.last()
    drawCircle(TT.DustyRose, 18f, destination)
    drawCircle(TT.MidnightNavy, 7f, destination)

    val angle = Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat() + 90f
    drawCircle(TT.PearlIvory, 26f, car)
    rotate(angle, car) {
        val arrow = Path().apply {
            moveTo(car.x, car.y - 18f)
            lineTo(car.x + 14f, car.y + 14f)
            lineTo(car.x, car.y + 7f)
            lineTo(car.x - 14f, car.y + 14f)
            close()
        }
        drawPath(arrow, RouteAhead)
    }
}
