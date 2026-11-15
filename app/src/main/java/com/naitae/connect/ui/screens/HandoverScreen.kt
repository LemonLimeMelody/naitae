package com.naitae.connect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.components.ButtonKind
import com.naitae.connect.ui.components.NaitaeButton
import com.naitae.connect.ui.components.NaitaeType
import com.naitae.connect.ui.components.RingsView
import com.naitae.connect.ui.screens.parts.ScreenStage
import com.naitae.connect.ui.screens.parts.TouchMin
import com.naitae.connect.ui.screens.parts.kmLabel
import com.naitae.connect.ui.theme.NaitaeColors

/** 이어받기: 새 차는 0km 에서 시작하지만 나이테는 그대로 이어진다. */
@Composable
fun HandoverScreen(state: Screen.Handover, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    val ringCount = state.summary.ringCount.coerceAtLeast(0)
    val odometerKm = state.newCarOdometerKm ?: ui.odometerKm
    val rings = state.rings

    ScreenStage(background = NaitaeColors.Screen, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                HandoverFigure(
                    label = "새 차",
                    // 주행거리를 읽지 못했으면 숫자를 지어내지 않는다
                    value = if (odometerKm != null) kmLabel(odometerKm) else "첫 시동",
                    unit = if (odometerKm != null) "km" else null,
                    caption = "오늘 처음 달리는 차",
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.weight(1.2f).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    RingsView(
                        rings = rings?.rings ?: emptyList(),
                        modifier = Modifier.aspectRatio(1f),
                        accent = NaitaeColors.Purple,
                        centerScene = rings?.topic?.scene ?: SceneKind.FAMILY_TRIP,
                    )
                }
                HandoverFigure(
                    label = "나이테",
                    value = if (ringCount == 20) "스무 겹" else "${ringCount}겹",
                    unit = null,
                    caption = if (ringCount == 20) "가족과 함께한 스무 해" else "가족과 함께한 ${ringCount}해",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = TouchMin)) {
                Text(
                    text = "차는 바뀌어도, 나이테는 이어집니다",
                    style = NaitaeType.H2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
                NaitaeButton(
                    text = "나이테 보기",
                    onClick = { actions.dismissHandover() },
                    modifier = Modifier.align(Alignment.CenterEnd).heightIn(min = TouchMin),
                    kind = ButtonKind.PRIMARY,
                    enabled = !ui.driving,
                )
            }
        }
    }
}

/** 작은 이름 · 큰 숫자 · 한 줄 설명 */
@Composable
private fun HandoverFigure(
    label: String,
    value: String,
    unit: String?,
    caption: String,
    modifier: Modifier = Modifier,
) {
    // 숫자가 길면 한 줄에 들어가게 조금 줄인다
    val bigSize = if (value.length > 4) 50.sp else 66.sp
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = NaitaeType.Sub, textAlign = TextAlign.Center)
        Text(
            text = buildAnnotatedString {
                append(value)
                if (unit != null) {
                    withStyle(SpanStyle(fontSize = 30.sp)) { append(unit) }
                }
            },
            style = TextStyle(
                fontSize = bigSize,
                fontWeight = FontWeight.ExtraBold,
                color = NaitaeColors.Navy,
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
        Text(text = caption, style = NaitaeType.Sub, textAlign = TextAlign.Center)
    }
}
