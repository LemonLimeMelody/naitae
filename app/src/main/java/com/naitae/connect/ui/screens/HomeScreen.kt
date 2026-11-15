package com.naitae.connect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.core.model.Topic
import com.naitae.connect.core.model.TopicStat
import com.naitae.connect.core.model.TopicSummary
import com.naitae.connect.domain.Dday
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.components.NaitaeType
import com.naitae.connect.ui.components.RingsView
import com.naitae.connect.ui.components.SceneIllustration
import com.naitae.connect.ui.components.TagPill
import com.naitae.connect.ui.components.TopicChip
import com.naitae.connect.ui.screens.frame.FrameSpec
import com.naitae.connect.ui.screens.frame.accentColor
import com.naitae.connect.ui.screens.frame.formatKm
import com.naitae.connect.ui.screens.frame.koreanDayOfWeek
import com.naitae.connect.ui.theme.NaitaeColors

/** 나이테 홈: 주제 칩, 선택한 주제의 나이테, 통계, 최근 장면, 다가오는 주제별 일정. 시그니처는 계약 */
@Composable
fun HomeScreen(state: Screen.Home, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    val selected = state.selected
    val locked = ui.driving
    Column(
        modifier.padding(horizontal = FrameSpec.BodyPadH, vertical = FrameSpec.BodyPadV),
    ) {
        if (state.topics.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.topics.forEach { topic ->
                    TopicChip(
                        text = topic.name,
                        selected = topic.id == selected?.topic?.id,
                        onClick = { actions.selectTopic(topic.id) },
                        modifier = Modifier.heightIn(min = 56.dp),
                        enabled = !locked,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (selected == null) {
            HomeEmpty(Modifier.fillMaxWidth().weight(1f))
        } else {
            HomeTopic(
                summary = selected,
                topics = state.topics,
                upcoming = state.upcoming,
                locked = locked,
                onOpenScene = { recordId -> actions.openDecorateFor(recordId) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun HomeTopic(
    summary: TopicSummary,
    topics: List<Topic>,
    upcoming: List<Dday>,
    locked: Boolean,
    onOpenScene: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        // 나이테는 높이를 꽉 채우되, 좁은 화면에서는 오른쪽 칸이 눌리지 않게 너비의 44%까지만
        val widthCap = maxWidth * 0.44f
        val ringSide = if (maxHeight < widthCap) maxHeight else widthCap
        val chipCount = if (maxHeight >= 440.dp) 3 else 2
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RingsView(
                rings = summary.rings,
                modifier = Modifier.size(ringSide),
                accent = summary.topic.scene.accentColor(),
                centerScene = summary.topic.scene,
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = "${summary.topic.name} · 올해의 겹이 자라는 중",
                    style = NaitaeType.Sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary.stats.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        summary.stats.take(3).forEach { stat ->
                            StatTile(stat, Modifier.weight(1f))
                        }
                    }
                }
                LatestSceneCard(summary = summary, locked = locked, onOpen = onOpenScene)
                upcoming.take(chipCount).forEach { dday ->
                    DdayChip(
                        dday = dday,
                        topicName = topics.firstOrNull { it.id == dday.schedule.topicId }?.name,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(stat: TopicStat, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NaitaeColors.Bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(stat.label, style = NaitaeType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stat.value, style = NaitaeType.H2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 최근 장면 카드. 누르면 그 기록의 함께 꾸미기로 간다. */
@Composable
private fun LatestSceneCard(summary: TopicSummary, locked: Boolean, onOpen: (String) -> Unit) {
    val latest = summary.latest
    val shape = RoundedCornerShape(16.dp)
    val title = when {
        latest == null -> "아직 남긴 장면이 없어요"
        latest.firstVisit -> "${latest.placeName} · 처음 온 곳"
        else -> latest.placeName
    }
    val note = latest?.notes?.firstOrNull { it.text.isNotBlank() }?.text
    val sub = when {
        latest == null -> "특별한 날을 남기면 여기에 보여요"
        note != null -> "“$note”"
        else -> "함께 달린 ${formatKm(latest.distanceKm)}km"
    }
    val tap = if (latest != null) {
        Modifier.clickable(enabled = !locked, onClick = { onOpen(latest.id) })
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NaitaeColors.Bg)
            .then(tap)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SceneIllustration(
            kind = latest?.photos?.firstOrNull()?.scene ?: summary.topic.scene,
            modifier = Modifier
                .width(150.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("최근 장면", style = NaitaeType.Caption, maxLines = 1)
            Text(
                text = title,
                style = NaitaeType.H2.copy(fontSize = 20.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sub,
                style = NaitaeType.Sub.copy(fontSize = 15.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 다가오는 주제별 일정: "낚시 D-2" + "토요일 05:00 · 낚시터" */
@Composable
private fun DdayChip(dday: Dday, topicName: String?) {
    val schedule = dday.schedule
    val day = koreanDayOfWeek(schedule.epochDay)
    val time = schedule.time
    val whenText = if (time.isNullOrBlank()) day else "$day $time"
    val detail = listOfNotNull(whenText, schedule.place?.name).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NaitaeColors.Bg)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TagPill("${topicName ?: schedule.title} ${dday.tag}")
        Text(
            text = detail,
            style = NaitaeType.Sub.copy(fontSize = 15.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 아직 주제가 없을 때: 첫 주제를 만들어 보자고 권한다. */
@Composable
private fun HomeEmpty(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val widthCap = maxWidth * 0.36f
        val ringSide = if (maxHeight < widthCap) maxHeight else widthCap
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RingsView(
                rings = emptyList(),
                modifier = Modifier.size(ringSide),
                centerScene = SceneKind.GENERIC,
            )
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TagPill("나이테 · 1년에 한 겹")
                Text("첫 주제를 만들어 보세요", style = NaitaeType.H1)
                Text(
                    text = "본업·낚시·가족 여행처럼 삶의 주제를 정하면,\n그 주제의 나이테가 여기서 자라기 시작해요.",
                    style = NaitaeType.Sub,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
