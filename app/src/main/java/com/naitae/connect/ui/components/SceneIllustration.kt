package com.naitae.connect.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.naitae.connect.core.model.SceneKind

/**
 * 장면 그림 (구현 예정: ui-A 담당). 사진 대신 쓰는 일러스트로, 사람이 보이게 그린다.
 * 예: FAMILY_TRIP = 가을 수목원의 가족 넷, FISHING = 새벽 낚시터의 한 사람.
 */
@Composable
fun SceneIllustration(kind: SceneKind, modifier: Modifier = Modifier) {
    Box(modifier)
}
