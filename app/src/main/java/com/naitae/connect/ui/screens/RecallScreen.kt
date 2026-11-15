package com.naitae.connect.ui.screens

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.components.ButtonKind
import com.naitae.connect.ui.components.NaitaeButton
import com.naitae.connect.ui.screens.parts.DarkH1
import com.naitae.connect.ui.screens.parts.DarkSubStyle
import com.naitae.connect.ui.screens.parts.PhotoFrame
import com.naitae.connect.ui.screens.parts.ScreenStage
import com.naitae.connect.ui.screens.parts.TouchMin
import com.naitae.connect.ui.screens.parts.quoted
import com.naitae.connect.ui.theme.NaitaeColors
import java.time.LocalDate

/** 차에서 다시 보기: 같은 곳에 다시 온 날, 그날의 장면과 한마디를 차가 먼저 꺼내 준다. */
@Composable
fun RecallScreen(state: Screen.RecallView, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    val recall = state.recall
    val record = recall.record
    val note = record.notes.firstOrNull()
    val scene = record.photos.firstOrNull()?.scene ?: recall.topic.scene
    val enabled = !ui.driving
    // 겹의 순번은 이 화면에 오지 않으므로 그 기록이 새겨진 해로 겹을 가리킨다
    val ringYear = LocalDate.ofEpochDay(record.epochDay).year
    val subLine = "${record.placeName} · ${recall.topic.name} · ${ringYear}년의 겹"

    ScreenStage(background = NaitaeColors.DarkBg, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Box(
                modifier = Modifier.weight(0.56f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                // 기록이 바뀌면 확대를 처음부터 다시 한다
                key(record.id) {
                    var started by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { started = true }
                    val zoom = animateFloatAsState(
                        targetValue = if (started) 1.08f else 1f,
                        animationSpec = tween(durationMillis = 10_000, easing = LinearOutSlowInEasing),
                        label = "recallZoom",
                    )
                    PhotoFrame(
                        scene = scene,
                        modifier = Modifier.aspectRatio(1.53f),
                        zoom = { zoom.value },
                    )
                }
            }
            Column(modifier = Modifier.weight(0.44f)) {
                Text(
                    text = recall.title,
                    style = DarkH1.copy(fontSize = 38.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subLine,
                    style = DarkSubStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note != null) {
                    Spacer(Modifier.height(22.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NaitaeColors.DarkCard, RoundedCornerShape(16.dp))
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                    ) {
                        Text(
                            text = quoted(note.text),
                            style = TextStyle(
                                fontSize = 26.sp,
                                lineHeight = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NaitaeColors.DarkText,
                            ),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.playingVoice) {
                            PlayMark()
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = "그날의 목소리",
                            style = if (state.playingVoice) {
                                DarkSubStyle.copy(color = NaitaeColors.DarkText, fontWeight = FontWeight.Bold)
                            } else {
                                DarkSubStyle
                            },
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (note != null) {
                        NaitaeButton(
                            text = "목소리 듣기",
                            onClick = { actions.playRecallVoice() },
                            modifier = Modifier.heightIn(min = TouchMin),
                            kind = ButtonKind.PRIMARY,
                            enabled = enabled,
                        )
                    }
                    NaitaeButton(
                        text = "닫기",
                        onClick = { actions.closeRecall() },
                        modifier = Modifier.heightIn(min = TouchMin),
                        kind = ButtonKind.DARK,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

/** 재생 중 표시(▶). 글꼴에 따라 기호가 그림 문자로 바뀔 수 있어 직접 그린다. */
@Composable
private fun PlayMark() {
    Canvas(Modifier.size(14.dp)) {
        val mark = Path().apply {
            moveTo(size.width * 0.15f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width * 0.15f, size.height)
            close()
        }
        drawPath(path = mark, color = NaitaeColors.Amber)
    }
}
