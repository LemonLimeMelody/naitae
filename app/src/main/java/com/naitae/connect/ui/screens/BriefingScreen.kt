package com.naitae.connect.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.domain.BriefingEngine
import com.naitae.connect.domain.BriefingKind
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.components.NaitaeButton
import com.naitae.connect.ui.components.NaitaeType
import com.naitae.connect.ui.components.TagPill
import com.naitae.connect.ui.screens.frame.FrameSpec
import com.naitae.connect.ui.theme.NaitaeColors

/** 한 화면에 보이는 줄 수(스크롤하지 않는다). 항목이 더 많으면 읽는 줄을 따라 창이 움직인다. */
private const val MAX_ROWS = 6

/** 줄 하나의 가장 큰 높이 */
private val RowMaxHeight = 88.dp

/**
 * 일정·메모 브리핑. 시그니처는 계약.
 * 읽는 줄(activeIndex)을 옅은 호박색으로 짚어 주고, 맨 아래 줄은 "메모할 것 있으세요?" → 듣는 중 → 메모했어요 로 바뀐다.
 */
@Composable
fun BriefingScreen(state: Screen.Briefing, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    val briefing = state.briefing
    val items = briefing.items
    val saved = state.savedMemo
    val atMemo = state.activeIndex >= items.size
    val showMemoRow = briefing.askMemo || state.listening || saved != null

    val maxItemRows = if (showMemoRow) MAX_ROWS - 1 else MAX_ROWS
    val start = if (items.size <= maxItemRows) {
        0
    } else {
        (state.activeIndex - 2).coerceIn(0, items.size - maxItemRows)
    }
    val visible = items.drop(start).take(maxItemRows)
    val rowCount = (if (visible.isEmpty()) 1 else visible.size) + (if (showMemoRow) 1 else 0)
    val titleSize = when {
        rowCount <= 4 -> 24.sp
        rowCount == 5 -> 22.sp
        else -> 20.sp
    }
    val subSize = when {
        rowCount <= 4 -> 16.sp
        rowCount == 5 -> 15.sp
        else -> 14.sp
    }
    val status = when {
        saved != null -> "메모했어요"
        state.listening -> "듣고 있어요"
        atMemo -> "다 읽었어요"
        else -> "읽어 주는 중"
    }

    Column(
        modifier.padding(
            start = FrameSpec.BodyPadH,
            end = FrameSpec.BodyPadH,
            top = FrameSpec.BodyPadV,
            // 아래쪽은 음성 자막이 덮으므로 비워 둔다
            bottom = FrameSpec.BodyPadV + FrameSpec.CaptionReserve,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = briefing.dateLabel,
                style = NaitaeType.H1.copy(fontSize = 30.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TagPill(status)
            NaitaeButton(
                text = "그만",
                onClick = { actions.stopBriefing() },
                modifier = Modifier.heightIn(min = 56.dp),
                enabled = !ui.driving,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = RowMaxHeight * rowCount)
                    .fillMaxHeight(),
            ) {
                if (visible.isEmpty()) {
                    BriefingRow(
                        tag = "오늘",
                        tagColor = NaitaeColors.Slate,
                        title = "오늘은 챙길 일정·메모가 없어요",
                        sub = null,
                        active = false,
                        dimmed = true,
                        titleSize = titleSize,
                        subSize = subSize,
                        modifier = Modifier.weight(1f),
                    )
                }
                visible.forEachIndexed { i, item ->
                    BriefingRow(
                        tag = item.tag,
                        tagColor = when (item.kind) {
                            BriefingKind.SCHEDULE -> NaitaeColors.Amber
                            BriefingKind.MEMO -> NaitaeColors.Purple
                            BriefingKind.DDAY -> NaitaeColors.Navy
                        },
                        title = item.title,
                        sub = item.sub,
                        active = start + i == state.activeIndex,
                        dimmed = false,
                        titleSize = titleSize,
                        subSize = subSize,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showMemoRow) {
                    val heard = state.heardText.trim()
                    when {
                        saved != null -> BriefingRow(
                            tag = "메모",
                            tagColor = NaitaeColors.Purple,
                            title = "메모했어요 · ${saved.text}",
                            sub = null,
                            active = true,
                            dimmed = false,
                            titleSize = titleSize,
                            subSize = subSize,
                            modifier = Modifier.weight(1f),
                        )
                        state.listening -> BriefingRow(
                            tag = "메모",
                            tagColor = NaitaeColors.Purple,
                            title = "듣고 있어요",
                            sub = if (heard.isEmpty()) "말씀하시면 메모해 둘게요" else heard,
                            active = true,
                            dimmed = false,
                            titleSize = titleSize,
                            subSize = subSize,
                            modifier = Modifier.weight(1f),
                            // 지금 들리는 말은 또렷하게
                            subColor = if (heard.isEmpty()) NaitaeColors.Ink2 else NaitaeColors.AmberInk,
                            subWeight = if (heard.isEmpty()) FontWeight.Normal else FontWeight.Bold,
                        )
                        else -> BriefingRow(
                            tag = "메모",
                            tagColor = NaitaeColors.Purple,
                            title = BriefingEngine.ASK_MEMO,
                            sub = if (atMemo) "말로 남기면 메모해 둘게요" else null,
                            active = atMemo,
                            dimmed = !atMemo,
                            titleSize = titleSize,
                            subSize = subSize,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BriefingRow(
    tag: String,
    tagColor: Color,
    title: String,
    sub: String?,
    active: Boolean,
    dimmed: Boolean,
    titleSize: TextUnit,
    subSize: TextUnit,
    modifier: Modifier = Modifier,
    subColor: Color = NaitaeColors.Ink2,
    subWeight: FontWeight = FontWeight.Normal,
) {
    // 투명한 검정으로 바뀌면 중간에 잿빛이 끼므로 같은 색의 투명도만 바꾼다
    val bg by animateColorAsState(
        targetValue = if (active) NaitaeColors.Soft else NaitaeColors.Soft.copy(alpha = 0f),
        animationSpec = tween(durationMillis = 200),
        label = "briefingRow",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TagPill(
                text = tag,
                modifier = Modifier.widthIn(min = 72.dp),
                background = if (dimmed) NaitaeColors.Light else tagColor,
                content = if (dimmed) NaitaeColors.Ink2 else NaitaeColors.White,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = NaitaeType.H2.copy(
                        fontSize = titleSize,
                        color = if (dimmed) NaitaeColors.Slate else NaitaeColors.Navy,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!sub.isNullOrBlank()) {
                    Text(
                        text = sub,
                        style = NaitaeType.Sub.copy(fontSize = subSize, color = subColor, fontWeight = subWeight),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(NaitaeColors.Light))
    }
}
