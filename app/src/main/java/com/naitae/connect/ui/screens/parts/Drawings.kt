package com.naitae.connect.ui.screens.parts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.ui.components.NaitaeType
import com.naitae.connect.ui.components.SceneIllustration
import com.naitae.connect.ui.theme.NaitaeColors

/** 옅은 테두리 색(빈 좌석, 색 고르기 테두리, 점선 자리) */
internal val OutlineColor = NaitaeColors.Slate.copy(alpha = 0.45f)

/** 편지 봉투: 흰 봉투에 호박색 테두리와 덮개 선, 가운데 하트 봉인 */
@Composable
internal fun EnvelopeDrawing(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val half = stroke / 2f
        val corner = CornerRadius(10.dp.toPx(), 10.dp.toPx())
        val bodyTopLeft = Offset(half, half)
        val bodySize = Size(size.width - stroke, size.height - stroke)
        drawRoundRect(
            color = NaitaeColors.White,
            topLeft = bodyTopLeft,
            size = bodySize,
            cornerRadius = corner,
        )
        drawRoundRect(
            color = NaitaeColors.Amber,
            topLeft = bodyTopLeft,
            size = bodySize,
            cornerRadius = corner,
            style = Stroke(width = stroke),
        )

        val sealCenter = Offset(size.width / 2f, size.height * 0.55f)
        val flap = Path().apply {
            moveTo(stroke * 2.2f, stroke * 1.6f)
            lineTo(sealCenter.x, sealCenter.y)
            lineTo(size.width - stroke * 2.2f, stroke * 1.6f)
        }
        drawPath(
            path = flap,
            color = NaitaeColors.Amber,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val sealRadius = size.minDimension * 0.155f
        drawCircle(color = NaitaeColors.Amber, radius = sealRadius, center = sealCenter)
        drawPath(
            path = heartPath(sealCenter.x, sealCenter.y - sealRadius * 0.06f, sealRadius * 0.52f),
            color = NaitaeColors.White,
        )
    }
}

/** (cx, cy) 를 가운데로 하는 하트. s = 크기의 절반쯤 */
private fun heartPath(cx: Float, cy: Float, s: Float): Path = Path().apply {
    moveTo(cx, cy + s * 0.9f)
    cubicTo(cx - s * 1.6f, cy - s * 0.2f, cx - s * 0.6f, cy - s * 1.2f, cx, cy - s * 0.35f)
    cubicTo(cx + s * 0.6f, cy - s * 1.2f, cx + s * 1.6f, cy - s * 0.2f, cx, cy + s * 0.9f)
    close()
}

/**
 * 좌석 그림(2×2). 운전석 → 조수석 → 뒷좌석 왼쪽 → 오른쪽 순서로 seatCount 만큼만 채운다.
 * 몇 명이 탔는지만 보여 주고 누가 탔는지는 나타내지 않는다.
 */
@Composable
internal fun SeatDiagram(seatCount: Int, modifier: Modifier = Modifier) {
    val filled = seatCount.coerceIn(0, 4)
    val seatShape = RoundedCornerShape(7.dp)
    Column(
        modifier = modifier
            .border(2.dp, OutlineColor, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..1) {
                    val on = row * 2 + col < filled
                    Box(
                        Modifier
                            .size(width = 30.dp, height = 34.dp)
                            .background(if (on) NaitaeColors.Amber else NaitaeColors.White, seatShape)
                            .border(2.dp, if (on) NaitaeColors.Amber else OutlineColor, seatShape),
                    )
                }
            }
        }
    }
}

/** 사진 자리: 둥근 모서리 안에 장면 그림. zoom 은 그리는 단계에서만 읽어 재구성 없이 천천히 키운다. */
@Composable
internal fun PhotoFrame(
    scene: SceneKind,
    modifier: Modifier = Modifier,
    zoom: () -> Float = { 1f },
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NaitaeColors.White),
    ) {
        SceneIllustration(
            kind = scene,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val z = zoom()
                    scaleX = z
                    scaleY = z
                    transformOrigin = TransformOrigin(0.45f, 0.6f)
                },
        )
    }
}

/** 아직 사진이 없을 때의 점선 자리 */
@Composable
internal fun PhotoPlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NaitaeColors.Bg)
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    color = OutlineColor,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()), 0f),
                    ),
                )
            }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = NaitaeType.Sub.copy(color = NaitaeColors.Slate),
            textAlign = TextAlign.Center,
        )
    }
}
