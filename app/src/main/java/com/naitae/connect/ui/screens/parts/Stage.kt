package com.naitae.connect.ui.screens.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ui-B 화면(기록·꾸미기·다시 보기·편지·이어받기)이 함께 쓰는 틀 */

/** 목업의 화면 본문 크기(960×540 에서 상단 바 44 를 뺀 영역). 이 크기를 기준으로 그린다. */
private val DesignWidth = 960.dp
private val DesignHeight = 496.dp

/** 큰 화면에서 본문을 키우는 한도. 1f 로 두면 키우지 않는다. */
private const val MaxStageScale = 3f

/** 팔을 뻗어 누르는 화면이라 누르는 곳은 56dp 이상 */
internal val TouchMin = 56.dp

/**
 * 화면 본문 틀. 배경을 칠하고 목업과 같은 여백을 둔다.
 * 기준 크기보다 큰 디스플레이에서는 밀도를 키워 글자와 버튼이 같은 비율로 커지게 한다
 * (목업이 화면에 맞춰 통째로 커지는 것과 같은 방식). 기준보다 작으면 줄이지 않는다(글자 14sp 유지).
 */
@Composable
internal fun ScreenStage(
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(background)) {
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val scale = if (bounded) {
            minOf(maxWidth / DesignWidth, maxHeight / DesignHeight).coerceIn(1f, MaxStageScale)
        } else {
            1f
        }
        val base = LocalDensity.current
        val scaled = remember(base, scale) { Density(base.density * scale, base.fontScale) }
        CompositionLocalProvider(LocalDensity provides scaled) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
                content = content,
            )
        }
    }
}

/** 칩을 가로로 놓다가 넘치면 다음 줄로 넘긴다(FlowRow 가 실험적 API 라 직접 배치). */
@Composable
internal fun WrapRow(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var x = 0
        var y = 0
        var rowHeight = 0
        var usedWidth = 0
        placeables.forEachIndexed { index, placeable ->
            if (x > 0 && x + placeable.width > constraints.maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            xs[index] = x
            ys[index] = y
            x += placeable.width + gapPx
            usedWidth = maxOf(usedWidth, x - gapPx)
            rowHeight = maxOf(rowHeight, placeable.height)
        }
        val width = usedWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (y + rowHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(xs[index], ys[index]) }
        }
    }
}
