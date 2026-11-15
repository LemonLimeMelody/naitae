package com.naitae.connect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.DayRecord
import com.naitae.connect.core.model.Member
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.core.model.Topic
import com.naitae.connect.core.model.VoiceNote
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.components.ButtonKind
import com.naitae.connect.ui.components.NaitaeButton
import com.naitae.connect.ui.components.NaitaeType
import com.naitae.connect.ui.components.RingsView
import com.naitae.connect.ui.components.TagPill
import com.naitae.connect.ui.components.TopicChip
import com.naitae.connect.ui.screens.parts.OutlineColor
import com.naitae.connect.ui.screens.parts.PhotoFrame
import com.naitae.connect.ui.screens.parts.PhotoPlaceholder
import com.naitae.connect.ui.screens.parts.PulsingPill
import com.naitae.connect.ui.screens.parts.ScreenStage
import com.naitae.connect.ui.screens.parts.SeatDiagram
import com.naitae.connect.ui.screens.parts.TouchMin
import com.naitae.connect.ui.screens.parts.TypingText
import com.naitae.connect.ui.screens.parts.WrapRow
import com.naitae.connect.ui.screens.parts.kmLabel
import com.naitae.connect.ui.screens.parts.quoted
import com.naitae.connect.ui.screens.parts.topicAccent
import com.naitae.connect.ui.theme.NaitaeColors
import com.naitae.connect.ui.theme.toColor

/** 주제 칩을 두 줄까지만 보여 준다(화면을 넘기지 않기 위해). */
private const val MaxTopicChoices = 8

/** 오늘을 남길까요? 돌아가려 시동을 걸었을 때 한 번 묻는다. */
@Composable
fun RecordAskScreen(state: Screen.RecordAsk, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    val draft = state.draft
    val choices = state.topicChoices.take(MaxTopicChoices)
    // 초안이 바뀌면 고른 주제도 처음으로 돌린다
    var pickedId by remember(draft, state.topicChoices) { mutableStateOf<String?>(null) }
    val picked = choices.firstOrNull { it.id == pickedId }
    val topic: Topic? = state.topic ?: picked
    val enabled = !ui.driving

    val prepared = buildList {
        add("차가 준비한 기록")
        add("${kmLabel(draft.distanceKm)}km")
        if (draft.firstVisit) add("처음 온 곳")
        add(if (draft.seatCount > 1) "함께한 날" else "나와 이 차의 날")
    }.joinToString(" · ")

    ScreenStage(background = NaitaeColors.Screen, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Box(
                modifier = Modifier.weight(0.38f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                RingsView(
                    rings = state.summary?.rings ?: emptyList(),
                    modifier = Modifier.aspectRatio(1f),
                    accent = topicAccent(topic?.scene),
                    centerScene = topic?.scene ?: SceneKind.GENERIC,
                )
            }
            Column(modifier = Modifier.weight(0.62f)) {
                if (topic != null) {
                    TagPill(
                        text = if (draft.scheduleId != null) "${topic.name} · 오늘 일정" else topic.name,
                        background = NaitaeColors.Amber,
                        content = NaitaeColors.White,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    text = "오늘 ${draft.placeName}, 어땠어요?",
                    style = NaitaeType.H1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (topic != null) {
                        "돌아가기 전에, 오늘을 '${topic.name}' 나이테에 남길까요?"
                    } else {
                        "돌아가기 전에, 오늘을 어느 나이테에 남길까요?"
                    },
                    style = NaitaeType.Body.copy(fontWeight = FontWeight.Normal, color = NaitaeColors.Ink2),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = prepared,
                    style = NaitaeType.Caption.copy(fontSize = 15.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.topic == null && choices.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    WrapRow(modifier = Modifier.fillMaxWidth(), gap = 8.dp) {
                        choices.forEach { choice ->
                            TopicChip(
                                text = choice.name,
                                selected = choice.id == pickedId,
                                onClick = { pickedId = choice.id },
                                modifier = Modifier.heightIn(min = TouchMin),
                                enabled = enabled,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NaitaeButton(
                        text = "남기기",
                        onClick = { topic?.let { actions.acceptRecord(it.id) } },
                        modifier = Modifier.heightIn(min = TouchMin),
                        kind = ButtonKind.PRIMARY,
                        // 주제가 정해져야 남길 수 있다
                        enabled = enabled && topic != null,
                    )
                    NaitaeButton(
                        text = "나중에",
                        onClick = { actions.declineRecord() },
                        modifier = Modifier.heightIn(min = TouchMin),
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

/** 남긴 기록: 차가 채운 정보 + 한마디·사진 더하기 */
@Composable
fun RecordEditScreen(state: Screen.RecordEdit, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    ScreenStage(background = NaitaeColors.Screen, modifier = modifier) {
        if (state.listening) {
            VoiceListening(topicName = state.topic.name, heardText = state.heardText)
        } else {
            RecordEditBody(state = state, enabled = !ui.driving, actions = actions)
        }
    }
}

/** 한마디를 듣는 동안: 받아 적히는 말을 가운데에 크게 */
@Composable
private fun VoiceListening(topicName: String, heardText: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        PulsingPill(text = "듣고 있어요")
        if (heardText.isBlank()) {
            Text(
                text = "한마디를 말해 주세요",
                style = NaitaeType.H1.copy(color = NaitaeColors.Slate),
                textAlign = TextAlign.Center,
            )
        } else {
            TypingText(
                text = heardText,
                typing = true,
                style = NaitaeType.H1,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
        Text(
            text = "'$topicName' 나이테에 목소리와 함께 남겨요",
            style = NaitaeType.Sub,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordEditBody(state: Screen.RecordEdit, enabled: Boolean, actions: NaitaeActions) {
    val record = state.record
    val topic = state.topic
    // 방금 남긴 기록의 점이 도드라지게 표시한다
    val rings = remember(state.summary.rings, record.id) {
        state.summary.rings.map { ring ->
            ring.copy(scenes = ring.scenes.map { dot -> dot.copy(isNew = dot.recordId == record.id) })
        }
    }
    val carLines = listOf(
        if (record.firstVisit) "${record.placeName} · 처음 온 곳" else record.placeName,
        if (record.together) "오늘은 함께한 날" else "오늘은 나와 이 차의 날",
        "주행거리 ${kmLabel(record.distanceKm)}km",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TagPill(text = topic.name, background = NaitaeColors.Amber, content = NaitaeColors.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "오늘을 '${topic.name}' 나이테에 남겼어요",
                    style = NaitaeType.H1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(text = "차가 채운 기록", style = NaitaeType.Sub)
                Spacer(Modifier.height(6.dp))
                carLines.forEach { line ->
                    Text(
                        text = line,
                        style = NaitaeType.Body.copy(fontSize = 22.sp, lineHeight = 36.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (record.notes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    NoteQuotes(
                        notes = record.notes,
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    )
                }
            }
            Column(
                modifier = Modifier.width(210.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                RingsView(
                    rings = rings,
                    modifier = Modifier.weight(1f, fill = false).aspectRatio(1f),
                    accent = topicAccent(topic.scene),
                    centerScene = topic.scene,
                )
                SeatDiagram(seatCount = record.seatCount)
                Text(text = "좌석 ${record.seatCount}석", style = NaitaeType.Caption)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NaitaeButton(
                text = "한마디 남기기",
                onClick = { actions.startVoiceNote() },
                modifier = Modifier.heightIn(min = TouchMin),
                kind = ButtonKind.PRIMARY,
                enabled = enabled,
            )
            NaitaeButton(
                text = "사진 · 꾸미기",
                onClick = { actions.openDecorate() },
                modifier = Modifier.heightIn(min = TouchMin),
                enabled = enabled,
            )
            NaitaeButton(
                text = "완료",
                onClick = { actions.finishRecord() },
                modifier = Modifier.heightIn(min = TouchMin),
                enabled = enabled,
            )
        }
    }
}

/** 남긴 한마디를 인용문으로. 화면을 넘기지 않게 가장 최근 두 개만 보여 준다. */
@Composable
private fun NoteQuotes(notes: List<VoiceNote>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(NaitaeColors.Bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        notes.takeLast(2).forEach { note ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 22.dp)
                        .background(NaitaeColors.Amber, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = quoted(note.text),
                    style = NaitaeType.Body.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (notes.size > 2) {
            Text(text = "한마디 ${notes.size}개", style = NaitaeType.Caption)
        }
    }
}

/** 함께 꾸미기: 사진, 이 장면의 색(= 실내 조명 색) */
@Composable
fun DecorateScreen(state: Screen.Decorate, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    val record = state.record
    val enabled = !ui.driving
    val firstPhoto = record.photos.firstOrNull()
    val firstNote = record.notes.firstOrNull()

    ScreenStage(background = NaitaeColors.Screen, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagPill(text = "${state.topic.name} · 공유받은 사람만")
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "오늘의 ${record.placeName} 함께 꾸미기",
                    style = NaitaeType.H2,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                NaitaeButton(
                    text = "완료",
                    onClick = { actions.finishDecorate() },
                    modifier = Modifier.heightIn(min = TouchMin),
                    kind = ButtonKind.NAVY,
                    enabled = enabled,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                Column(modifier = Modifier.weight(0.52f).fillMaxHeight()) {
                    if (firstPhoto != null) {
                        PhotoFrame(
                            scene = firstPhoto.scene,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        PhotoPlaceholder(
                            text = "휴대폰에서 사진을 기다려요",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    if (firstNote != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = quoted(firstNote.text),
                            style = NaitaeType.Sub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DecorateTools(
                    record = record,
                    members = state.members,
                    colors = state.colors,
                    enabled = enabled,
                    actions = actions,
                    modifier = Modifier.weight(0.48f).fillMaxHeight().padding(top = 4.dp),
                )
            }
        }
    }
}

/** 꾸미기 오른쪽: 함께 꾸미는 사람, 색 고르기, 사진 더하기 */
@Composable
private fun DecorateTools(
    record: DayRecord,
    members: List<Member>,
    colors: List<AmbientColor>,
    enabled: Boolean,
    actions: NaitaeActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (members.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = "함께 꾸미는 사람", style = NaitaeType.Sub)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    members.take(6).forEachIndexed { index, member ->
                        MemberAvatar(name = member.name, index = index)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "이 장면의 색 = 실내 조명 색", style = NaitaeType.Sub)
                val pickedColor = record.color
                if (pickedColor != null) {
                    Text(
                        text = " · ${pickedColor.label}",
                        style = NaitaeType.Sub.copy(fontWeight = FontWeight.Bold, color = NaitaeColors.Navy),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEach { color ->
                    ColorSwatch(
                        color = color,
                        selected = record.color == color,
                        enabled = enabled,
                        onClick = { actions.pickColor(color) },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NaitaeButton(
                text = "+ 사진 (휴대폰 · QR)",
                onClick = { actions.addPhoto() },
                modifier = Modifier.heightIn(min = TouchMin),
                enabled = enabled,
            )
            Text(
                text = "사진 ${record.photos.size}장",
                style = NaitaeType.Caption.copy(fontSize = 15.sp),
            )
        }
    }
}

/** 이름 앞 글자를 넣은 동그라미 */
@Composable
private fun MemberAvatar(name: String, index: Int) {
    val palette = listOf(NaitaeColors.Purple, NaitaeColors.AmberInk, NaitaeColors.Navy, NaitaeColors.Slate)
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(palette[index % palette.size], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(2),
            style = NaitaeType.Caption.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
            color = NaitaeColors.White,
            maxLines = 1,
        )
    }
}

/** 색 고르기 동그라미. 고른 색은 짙은 테두리로 표시한다. */
@Composable
private fun ColorSwatch(color: AmbientColor, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(TouchMin)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = color.label }
            .border(
                width = if (selected) 3.dp else 1.5.dp,
                color = if (selected) NaitaeColors.Navy else OutlineColor,
                shape = CircleShape,
            )
            .padding(6.dp)
            .background(color.toColor(), CircleShape),
    )
}
