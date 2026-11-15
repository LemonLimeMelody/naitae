package com.naitae.connect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.components.ButtonKind
import com.naitae.connect.ui.components.NaitaeButton
import com.naitae.connect.ui.components.NaitaeType
import com.naitae.connect.ui.components.SceneIllustration
import com.naitae.connect.ui.components.TagPill
import com.naitae.connect.ui.screens.frame.FrameSpec
import com.naitae.connect.ui.screens.frame.withDirectionParticle

/**
 * 길 안내 질문. 시그니처는 계약.
 * 일정에 장소가 있는 날 출발 전에 "낚시터로 안내할까요?" 하고 묻는다. 답하기 전에는 안내를 시작하지 않는다.
 */
@Composable
fun RouteAskScreen(state: Screen.RouteAsk, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    val schedule = state.schedule
    val topic = state.topic
    val placeName = schedule.place?.name
    val whenWhere = listOfNotNull(schedule.time, placeName).joinToString(" · ")
    val question = if (placeName.isNullOrBlank()) "길을 안내할까요?" else "${withDirectionParticle(placeName)} 안내할까요?"
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier.padding(
            start = FrameSpec.BodyPadH,
            end = FrameSpec.BodyPadH,
            top = FrameSpec.BodyPadV,
            // 질문을 읽어 주는 자막이 버튼을 가리지 않게 아래를 비운다
            bottom = FrameSpec.BodyPadV + FrameSpec.CaptionReserve,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        SceneIllustration(
            kind = topic?.scene ?: SceneKind.GENERIC,
            modifier = Modifier
                .weight(0.42f)
                .aspectRatio(1.52f)
                .shadow(elevation = 6.dp, shape = shape)
                .clip(shape),
        )
        Column(Modifier.weight(0.58f)) {
            TagPill(if (topic != null) "주제별 일정 · ${topic.name}" else "오늘 일정")
            Spacer(Modifier.height(14.dp))
            Text(
                text = schedule.title,
                style = NaitaeType.H1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (whenWhere.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(whenWhere, style = NaitaeType.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(22.dp))
            Text(question, style = NaitaeType.H2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NaitaeButton(
                    text = "안내해 줘",
                    onClick = { actions.acceptRoute() },
                    modifier = Modifier.heightIn(min = 56.dp),
                    kind = ButtonKind.PRIMARY,
                    enabled = !ui.driving,
                )
                NaitaeButton(
                    text = "괜찮아요",
                    onClick = { actions.declineRoute() },
                    modifier = Modifier.heightIn(min = 56.dp),
                    enabled = !ui.driving,
                )
            }
        }
    }
}
