package com.naitae.connect.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import com.naitae.connect.core.model.SceneKind
import kotlin.math.max

/** 그림의 기준 가로세로 비(목업 380×250). 이보다 좁은 칸에서는 가운데를 잘라 보여 준다. */
private const val SCENE_ASPECT = 1.5f

private val Trousers = Color(0xFF4A5563)
private val Skin = Color(0xFFF0CDAE)
private val Hair = Color(0xFF3B2F2A)
private val NavyShirt = Color(0xFF2F4A6B)
private val Sun = Color(0xFFF6B26B)

/**
 * 장면 그림. 사진 대신 쓰는 일러스트로, 어느 장면에나 사람이 보인다.
 * FAMILY_TRIP = 가을 수목원의 가족 넷, FISHING = 새벽 낚시터의 한 사람, RUNNING = 강변길을 달리는 사람,
 * GOLF = 깃발이 선 그린의 두 사람, WORK = 아침 출근길의 한 사람, GENERIC = 언덕.
 */
@Composable
fun SceneIllustration(kind: SceneKind, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val h = size.height
        if (h <= 0f || size.width <= 0f) return@Canvas
        // 정사각형·원 안에 넣을 때도 사람이 겹쳐 보이지 않게, 그림은 3:2 로 그리고 가운데를 보여 준다
        val w = max(size.width, h * SCENE_ASPECT)
        val dx = (size.width - w) / 2f
        clipRect {
            translate(left = dx, top = 0f) {
                when (kind) {
                    SceneKind.FAMILY_TRIP -> arboretum(w, h)
                    SceneKind.FISHING -> pier(w, h)
                    SceneKind.RUNNING -> riverside(w, h)
                    SceneKind.GOLF -> green(w, h)
                    SceneKind.WORK -> morningRoad(w, h)
                    SceneKind.GENERIC -> hills(w, h)
                }
            }
        }
    }
}

// ───────── 공통 ─────────

private fun DrawScope.sky(w: Float, h: Float, top: Color, bottom: Color) {
    drawRect(
        brush = Brush.verticalGradient(colors = listOf(top, bottom), startY = 0f, endY = h),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )
}

private fun DrawScope.oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
    drawOval(color = color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f))
}

private fun DrawScope.polygon(color: Color, vararg points: Float) {
    val path = Path()
    path.moveTo(points[0], points[1])
    var i = 2
    while (i + 1 < points.size) {
        path.lineTo(points[i], points[i + 1])
        i += 2
    }
    path.close()
    drawPath(path = path, color = color)
}

/** 몸통·머리·머리카락. base = 발끝 높이, hh = 키 */
private fun DrawScope.upperBody(x: Float, base: Float, hh: Float, shirt: Color) {
    val r = hh * 0.14f
    drawRoundRect(
        color = shirt,
        topLeft = Offset(x - hh * 0.17f, base - hh + 2f * r - hh * 0.02f),
        size = Size(hh * 0.34f, hh * 0.5f),
        cornerRadius = CornerRadius(hh * 0.12f, hh * 0.12f),
    )
    drawCircle(color = Skin, radius = r, center = Offset(x, base - hh + r))
    val hair = Path()
    hair.moveTo(x - r, base - hh + r * 0.9f)
    hair.quadraticBezierTo(x, base - hh - r * 0.35f, x + r, base - hh + r * 0.9f)
    hair.close()
    drawPath(path = hair, color = Hair)
}

/** 서 있는 사람 */
private fun DrawScope.person(x: Float, base: Float, hh: Float, shirt: Color) {
    val leg = CornerRadius(hh * 0.04f, hh * 0.04f)
    drawRoundRect(
        color = Trousers,
        topLeft = Offset(x - hh * 0.11f, base - hh * 0.42f),
        size = Size(hh * 0.09f, hh * 0.42f),
        cornerRadius = leg,
    )
    drawRoundRect(
        color = Trousers,
        topLeft = Offset(x + hh * 0.02f, base - hh * 0.42f),
        size = Size(hh * 0.09f, hh * 0.42f),
        cornerRadius = leg,
    )
    upperBody(x, base, hh, shirt)
}

/** 달리는 사람: 다리를 앞뒤로 벌리고 팔을 흔든다 */
private fun DrawScope.runner(x: Float, base: Float, hh: Float, shirt: Color) {
    val limb = hh * 0.085f
    val hipY = base - hh * 0.44f
    val shoulderY = base - hh * 0.62f
    // 뒤로 찬 다리
    val knee = Offset(x - hh * 0.15f, base - hh * 0.2f)
    drawLine(Trousers, Offset(x - hh * 0.03f, hipY), knee, strokeWidth = limb, cap = StrokeCap.Round)
    drawLine(Trousers, knee, Offset(x - hh * 0.3f, base - hh * 0.13f), strokeWidth = limb, cap = StrokeCap.Round)
    // 앞으로 뻗은 다리
    drawLine(Trousers, Offset(x + hh * 0.03f, hipY), Offset(x + hh * 0.2f, base - hh * 0.03f), strokeWidth = limb, cap = StrokeCap.Round)
    // 팔
    drawLine(shirt, Offset(x - hh * 0.1f, shoulderY), Offset(x - hh * 0.27f, shoulderY + hh * 0.14f), strokeWidth = limb, cap = StrokeCap.Round)
    drawLine(shirt, Offset(x + hh * 0.1f, shoulderY), Offset(x + hh * 0.28f, shoulderY + hh * 0.06f), strokeWidth = limb, cap = StrokeCap.Round)
    upperBody(x, base, hh, shirt)
}

// ───────── 장면 ─────────

/** 가을 수목원의 가족 넷 */
private fun DrawScope.arboretum(w: Float, h: Float) {
    sky(w, h, Color(0xFFFCEBD5), Color(0xFFF6D3AA))
    oval(w * 0.3f, h * 0.72f, w * 0.5f, h * 0.22f, Color(0xFFEBC79A))
    oval(w * 0.85f, h * 0.74f, w * 0.45f, h * 0.2f, Color(0xFFE3B888))
    drawRect(color = Color(0xFFEAD7B2), topLeft = Offset(0f, h * 0.72f), size = Size(w, h * 0.28f))
    // 산책길
    polygon(Color(0xFFF6ECDB), w * 0.44f, h, w * 0.5f, h * 0.72f, w * 0.56f, h * 0.72f, w * 0.72f, h)
    tree(w, h, 0.1f, 0.17f, Color(0xFFE39A3B))
    tree(w, h, 0.26f, 0.22f, Color(0xFFD2733A))
    tree(w, h, 0.8f, 0.2f, Color(0xFFC9A227))
    tree(w, h, 0.95f, 0.16f, Color(0xFFE0884A))
    val base = h * 0.9f
    person(w * 0.4f, base, h * 0.46f, NavyShirt)
    person(w * 0.5f, base, h * 0.42f, Color(0xFFC46A5A))
    person(w * 0.59f, base, h * 0.3f, Color(0xFF8E6CC0))
    person(w * 0.67f, base, h * 0.24f, Color(0xFF3E9C8F))
}

private fun DrawScope.tree(w: Float, h: Float, x: Float, r: Float, leaf: Color) {
    drawRect(
        color = Color(0xFF8A6248),
        topLeft = Offset(w * x - w * 0.008f, h * 0.5f),
        size = Size(w * 0.016f, h * 0.26f),
    )
    drawCircle(color = leaf, radius = h * r, center = Offset(w * x, h * 0.42f))
    drawCircle(color = leaf, radius = h * r * 0.7f, center = Offset(w * x - h * r * 0.4f, h * 0.47f), alpha = 0.85f)
}

/** 새벽 낚시터의 한 사람과 낚싯대 */
private fun DrawScope.pier(w: Float, h: Float) {
    val s = h / 250f
    sky(w, h, Color(0xFFF9DCC0), Color(0xFFDCE8F2))
    drawCircle(color = Sun, radius = h * 0.09f, center = Offset(w * 0.25f, h * 0.5f))
    drawRect(color = Color(0xFF8FB7D6), topLeft = Offset(0f, h * 0.55f), size = Size(w, h * 0.45f))
    val wave = Path()
    wave.moveTo(0f, h * 0.66f)
    wave.quadraticBezierTo(w * 0.25f, h * 0.63f, w * 0.5f, h * 0.66f)
    wave.quadraticBezierTo(w * 0.75f, h * 0.69f, w, h * 0.66f)
    drawPath(path = wave, color = Color(0xFFB7D2E6), style = Stroke(width = max(1f, 3f * s)))
    // 방파제와 기둥
    drawRect(color = Color(0xFF8A6B4E), topLeft = Offset(w * 0.55f, h * 0.6f), size = Size(w * 0.45f, h * 0.05f))
    drawRect(color = Color(0xFF6E553E), topLeft = Offset(w * 0.62f, h * 0.65f), size = Size(w * 0.015f, h * 0.2f))
    drawRect(color = Color(0xFF6E553E), topLeft = Offset(w * 0.85f, h * 0.65f), size = Size(w * 0.015f, h * 0.2f))
    person(w * 0.72f, h * 0.6f, h * 0.36f, NavyShirt)
    // 낚싯대와 낚싯줄
    drawLine(Trousers, Offset(w * 0.68f, h * 0.38f), Offset(w * 0.42f, h * 0.12f), strokeWidth = max(1.5f, 3f * s), cap = StrokeCap.Round)
    drawLine(Trousers, Offset(w * 0.42f, h * 0.12f), Offset(w * 0.4f, h * 0.62f), strokeWidth = max(1f, s))
}

/** 하늘·해·풀밭·길(가운데 점선). 출근길과 강변길이 함께 쓴다 */
private fun DrawScope.roadBase(w: Float, h: Float) {
    val s = h / 250f
    sky(w, h, Color(0xFFFDF1DE), Color(0xFFE7EEF5))
    drawCircle(color = Sun, radius = h * 0.1f, center = Offset(w * 0.75f, h * 0.32f))
    drawRect(color = Color(0xFFD9E2C8), topLeft = Offset(0f, h * 0.58f), size = Size(w, h * 0.42f))
    polygon(Color(0xFFC7CED6), w * 0.2f, h, w * 0.47f, h * 0.58f, w * 0.53f, h * 0.58f, w * 0.8f, h)
    val dash = max(2f, 10f * s)
    drawLine(
        color = Color.White,
        start = Offset(w * 0.5f, h * 0.62f),
        end = Offset(w * 0.5f, h),
        strokeWidth = max(1f, 3f * s),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f),
    )
}

/** 강변길을 달리는 사람 */
private fun DrawScope.riverside(w: Float, h: Float) {
    roadBase(w, h)
    // 길 오른쪽으로 흐르는 강
    polygon(Color(0xFFA9CBE3), w * 0.61f, h * 0.58f, w, h * 0.58f, w, h, w * 0.93f, h)
    drawLine(
        color = Color(0xFFCFE2F0),
        start = Offset(w * 0.74f, h * 0.7f),
        end = Offset(w * 0.9f, h * 0.7f),
        strokeWidth = max(1f, h * 0.01f),
        cap = StrokeCap.Round,
    )
    runner(w * 0.3f, h * 0.92f, h * 0.42f, Color(0xFF5FA37A))
}

/** 아침 출근길의 한 사람 */
private fun DrawScope.morningRoad(w: Float, h: Float) {
    roadBase(w, h)
    // 멀리 보이는 건물
    val building = Color(0xFFD3DDE7)
    drawRect(color = building, topLeft = Offset(w * 0.07f, h * 0.4f), size = Size(w * 0.06f, h * 0.18f))
    drawRect(color = building, topLeft = Offset(w * 0.14f, h * 0.47f), size = Size(w * 0.07f, h * 0.11f))
    drawRect(color = building, topLeft = Offset(w * 0.6f, h * 0.45f), size = Size(w * 0.06f, h * 0.13f))
    drawRect(color = building, topLeft = Offset(w * 0.87f, h * 0.42f), size = Size(w * 0.07f, h * 0.16f))
    person(w * 0.3f, h * 0.92f, h * 0.42f, NavyShirt)
}

/** 깃발이 선 그린의 두 사람 */
private fun DrawScope.green(w: Float, h: Float) {
    val s = h / 250f
    sky(w, h, Color(0xFFEAF2F8), Color(0xFFDDEBF5))
    drawRect(color = Color(0xFFBFD8A8), topLeft = Offset(0f, h * 0.55f), size = Size(w, h * 0.45f))
    oval(w * 0.62f, h * 0.72f, w * 0.2f, h * 0.07f, Color(0xFFA6CC8C))
    drawLine(Color(0xFF55687B), Offset(w * 0.68f, h * 0.72f), Offset(w * 0.68f, h * 0.3f), strokeWidth = max(1f, 3f * s))
    polygon(Color(0xFFD98E2B), w * 0.68f, h * 0.3f, w * 0.78f, h * 0.35f, w * 0.68f, h * 0.4f)
    person(w * 0.28f, h * 0.9f, h * 0.42f, NavyShirt)
    person(w * 0.4f, h * 0.9f, h * 0.4f, Color(0xFFB5A04A))
}

/** 주제 그림이 정해지지 않았을 때: 언덕과 한 사람 */
private fun DrawScope.hills(w: Float, h: Float) {
    sky(w, h, Color(0xFFEAF2F8), Color(0xFFFBEBD3))
    drawCircle(color = Sun, radius = h * 0.09f, center = Offset(w * 0.7f, h * 0.3f))
    oval(w * 0.25f, h * 0.84f, w * 0.48f, h * 0.3f, Color(0xFFCFE3BC))
    oval(w * 0.82f, h * 0.88f, w * 0.5f, h * 0.3f, Color(0xFFBFD8A8))
    drawRect(color = Color(0xFFB4D09C), topLeft = Offset(0f, h * 0.84f), size = Size(w, h * 0.16f))
    person(w * 0.48f, h * 0.93f, h * 0.36f, NavyShirt)
}
