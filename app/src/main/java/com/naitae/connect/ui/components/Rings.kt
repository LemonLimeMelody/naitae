package com.naitae.connect.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.naitae.connect.core.model.RingView
import com.naitae.connect.core.model.SceneDot
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.ui.theme.NaitaeColors
import com.naitae.connect.ui.theme.toColor
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 겹이 이보다 많으면 선을 가늘게, 가운데 원을 작게 그린다 */
private const val MANY_RINGS = 10

/** 바깥 여백(한 변에 대한 비율). 새 점의 번짐이 잘리지 않을 만큼 */
private const val RING_MARGIN = 0.045f

/** 가운데 원 반지름 비율(겹이 적을 때 / 많을 때). 목업과 같은 값 */
private const val INNER_FEW = 0.42f
private const val INNER_MANY = 0.30f

private val CenterFill = Color(0xFFFFF8EE)

/**
 * 나이테 그림. 시그니처는 계약이다.
 * 겹은 안쪽부터 index 순, 두께는 thickness 에 비례. 지난 겹은 닫힌 원, 올해 겹은 점선 원 위에 progress 만큼의 실선 호.
 * 겹 위의 점은 그해의 장면(angleDeg: 위쪽이 0, 시계 방향). 가운데 원 안에는 centerScene 그림.
 */
@Composable
fun RingsView(
    rings: List<RingView>,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFD98E2B),
    centerScene: SceneKind? = null,
) {
    val ordered = remember(rings) { rings.sortedBy { it.index } }
    val innerRatio = if (ordered.size > MANY_RINGS) INNER_MANY else INNER_FEW
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val boundedW = constraints.hasBoundedWidth
        val boundedH = constraints.hasBoundedHeight
        val side = when {
            boundedW && boundedH -> if (maxWidth < maxHeight) maxWidth else maxHeight
            boundedW -> maxWidth
            boundedH -> maxHeight
            else -> 240.dp
        }
        Canvas(Modifier.size(side)) {
            drawRings(ordered, accent, innerRatio)
        }
        if (centerScene != null) {
            // 가운데 원 지름에서 테두리만큼 뺀 크기
            val diameter = side * ((0.5f - RING_MARGIN) * innerRatio * 2f) - 6.dp
            if (diameter >= 20.dp) {
                SceneIllustration(
                    kind = centerScene,
                    modifier = Modifier
                        .size(diameter)
                        .clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape),
                )
            }
        }
    }
}

private fun DrawScope.drawRings(rings: List<RingView>, accent: Color, innerRatio: Float) {
    val side = size.minDimension
    if (side <= 0f) return
    val c = center
    val outer = side / 2f - side * RING_MARGIN
    val r0 = outer * innerRatio
    val n = rings.size
    val many = n > MANY_RINGS
    // 목업은 한 변 380 을 기준으로 선 굵기를 정했다
    val unit = side / 380f
    val lineW = max(1f, (if (many) 1f else 1.6f) * unit)
    val dashLen = max(2f, 5f * unit)
    val dashed = PathEffect.dashPathEffect(floatArrayOf(dashLen, dashLen), 0f)

    if (n == 0) {
        // 아직 겹이 없을 때: 자랄 자리를 점선으로만 보여 준다
        drawCircle(
            color = NaitaeColors.Ring2,
            radius = outer,
            center = c,
            style = Stroke(width = max(1f, 2f * unit), pathEffect = dashed),
        )
    }

    // 겹의 바깥 반지름: 두께에 비례해 나눈다
    val radii = FloatArray(n)
    var total = 0f
    for (ring in rings) total += ringWeight(ring)
    var acc = 0f
    for (i in 0 until n) {
        acc += ringWeight(rings[i])
        radii[i] = r0 + (outer - r0) * acc / total
    }

    // 바깥 겹부터 칠해야 안쪽 겹이 위에 놓인다
    for (i in n - 1 downTo 0) {
        val ring = rings[i]
        val rOut = radii[i]
        val band = rOut - (if (i == 0) r0 else radii[i - 1])
        val line = ringLineColor(ring, accent)
        if (ring.complete) {
            drawCircle(color = ringBandFill(ring, i), radius = rOut, center = c)
            drawCircle(
                color = line,
                radius = rOut,
                center = c,
                style = Stroke(width = min(lineW, max(0.5f, band * 0.4f))),
            )
        } else {
            if (i != n - 1) drawCircle(color = CenterFill, radius = rOut, center = c)
            drawCircle(
                color = NaitaeColors.Ring2,
                radius = rOut,
                center = c,
                style = Stroke(width = max(1f, (if (many) 1.2f else 2f) * unit), pathEffect = dashed),
            )
            val progress = ring.progress.coerceIn(0f, 1f)
            if (progress > 0f) {
                drawArc(
                    color = line,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(c.x - rOut, c.y - rOut),
                    size = Size(rOut * 2f, rOut * 2f),
                    style = Stroke(width = max(1.5f, (if (many) 3f else 5f) * unit), cap = StrokeCap.Round),
                )
            }
        }
    }

    // 가운데 원
    drawCircle(color = CenterFill, radius = r0, center = c)
    drawCircle(color = accent, radius = r0, center = c, style = Stroke(width = max(1f, 1.4f * unit)))

    // 점: 보통 점을 먼저, 방금 새긴 점(isNew)은 맨 위에
    val baseDot = max(side * 0.016f, 2.dp.toPx())
    for (pass in 0..1) {
        val drawNew = pass == 1
        for (i in 0 until n) {
            val ring = rings[i]
            if (ring.scenes.isEmpty()) continue
            val rOut = radii[i]
            val rIn = if (i == 0) r0 else radii[i - 1]
            val band = rOut - rIn
            // 지난 겹은 띠 한가운데, 올해 겹은 자라는 선 위
            val onRadius = if (ring.complete) (rIn + rOut) / 2f else rOut
            val dotR = min(baseDot, band * (if (ring.complete) 0.45f else 0.6f))
            val line = ringLineColor(ring, accent)
            for (dot in ring.scenes) {
                if (dot.isNew != drawNew) continue
                drawSceneDot(dot, c, onRadius, dotR, line, side, unit)
            }
        }
    }
}

private fun DrawScope.drawSceneDot(
    dot: SceneDot,
    c: Offset,
    onRadius: Float,
    baseRadius: Float,
    color: Color,
    side: Float,
    unit: Float,
) {
    var r = baseRadius
    if (dot.firstVisit) r *= 1.25f
    if (dot.isNew) r = max(r * 1.9f, side * 0.03f)
    // 너무 작은 점은 얼룩처럼 보이므로 그리지 않는다
    if (r < 2f) return
    val angle = Math.toRadians((dot.angleDeg - 90f).toDouble())
    val p = Offset(c.x + onRadius * cos(angle).toFloat(), c.y + onRadius * sin(angle).toFloat())
    if (dot.isNew) drawCircle(color = color.copy(alpha = 0.22f), radius = r * 2f, center = p)
    val outline = if (dot.isNew) max(1.5f, 2.5f * unit) else max(1f, 1.2f * unit)
    if (r >= 3f) drawCircle(color = Color.White, radius = r + outline * 0.6f, center = p)
    drawCircle(color = color, radius = r, center = p)
}

private fun ringWeight(ring: RingView): Float {
    val t = ring.thickness
    return if (t.isNaN()) 1f else t.coerceIn(0.2f, 3f)
}

/** 겹의 선 색: 그해에 고른 색이 있으면 그 색. 너무 밝은 색(흰색 조명)은 선이 안 보이므로 주제 색을 쓴다. */
private fun ringLineColor(ring: RingView, accent: Color): Color {
    val tint = ring.color?.toColor() ?: return accent
    return if (tint.luminance() > 0.75f) accent else tint
}

/** 겹의 띠 색: 나무결처럼 번갈아 칠하고, 그해에 고른 색이 있으면 그 색을 섞는다. */
private fun ringBandFill(ring: RingView, position: Int): Color {
    val wood = if (position % 2 == 1) NaitaeColors.Ring2 else NaitaeColors.Soft
    val tint = ring.color?.toColor() ?: return wood
    return lerp(wood, tint, if (tint.luminance() > 0.75f) 0.6f else 0.38f)
}
