package com.naitae.connect.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.naitae.connect.core.model.RingView
import com.naitae.connect.core.model.SceneKind

/**
 * 나이테 그림 (구현 예정: ui-A 담당). 시그니처는 계약이다.
 * 겹은 안쪽부터 index 순, 올해 겹은 progress 만큼만 진하게, 겹 위에 점(scenes), 가운데 원 안에 centerScene 그림.
 */
@Composable
fun RingsView(
    rings: List<RingView>,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFD98E2B),
    centerScene: SceneKind? = null,
) {
    Box(modifier)
}
