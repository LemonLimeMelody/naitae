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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.domain.LetterPhase
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.ui.components.ButtonKind
import com.naitae.connect.ui.components.NaitaeButton
import com.naitae.connect.ui.components.RingsView
import com.naitae.connect.ui.screens.parts.DarkH1
import com.naitae.connect.ui.screens.parts.DarkH2
import com.naitae.connect.ui.screens.parts.DarkSubStyle
import com.naitae.connect.ui.screens.parts.EnvelopeDrawing
import com.naitae.connect.ui.screens.parts.PhotoFrame
import com.naitae.connect.ui.screens.parts.ScreenStage
import com.naitae.connect.ui.screens.parts.TouchMin
import com.naitae.connect.ui.screens.parts.TypingText
import com.naitae.connect.ui.theme.NaitaeColors

private const val ReservedLetterNote = "오늘 열리도록 예약해 둔 편지"

/** 편지 글: 29sp, 줄 간격 1.55 */
private val LetterTextStyle = TextStyle(
    fontSize = 29.sp,
    lineHeight = 45.sp,
    fontWeight = FontWeight.Bold,
    color = NaitaeColors.DarkText,
)

/** 편지가 도착했어요: 다른 사람이 함께 탔을 때는 바로 열지 않고 먼저 묻는다. */
@Composable
fun LetterArriveScreen(state: Screen.LetterArrive, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    val enabled = !ui.driving
    ScreenStage(background = NaitaeColors.DarkBg, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            EnvelopeDrawing(Modifier.size(width = 170.dp, height = 112.dp))
            Text(text = "편지가 도착했어요", style = DarkH1, textAlign = TextAlign.Center)
            Text(
                text = if (state.fromNames.isBlank()) {
                    ReservedLetterNote
                } else {
                    "${state.fromNames} · $ReservedLetterNote"
                },
                style = DarkSubStyle,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NaitaeButton(
                    text = "열기",
                    onClick = { actions.openLetter() },
                    modifier = Modifier.heightIn(min = TouchMin),
                    kind = ButtonKind.PRIMARY,
                    enabled = enabled,
                )
                NaitaeButton(
                    text = "도착해서 보기",
                    onClick = { actions.postponeLetter() },
                    modifier = Modifier.heightIn(min = TouchMin),
                    kind = ButtonKind.DARK,
                    enabled = enabled,
                )
            }
        }
    }
}

/** 편지 연출·낭독: 차가 먼저 빛과 온기로 맞이한 뒤 편지 글이 한 글자씩 나타난다. */
@Composable
fun LetterOpenScreen(state: Screen.LetterOpen, ui: UiState, actions: NaitaeActions, modifier: Modifier = Modifier) {
    ScreenStage(background = NaitaeColors.DarkBg, modifier = modifier) {
        when (state.phase) {
            LetterPhase.WARMING -> LetterWarming(state = state)
            LetterPhase.READING, LetterPhase.DONE -> LetterReading(
                state = state,
                enabled = !ui.driving,
                actions = actions,
            )
        }
    }
}

/** 연출 중: 봉투와 한 줄 안내만 보여 준다. */
@Composable
private fun LetterWarming(state: Screen.LetterOpen) {
    val newCar = state.letter.openOnNewCar
    val fromLine = when {
        state.fromNames.isBlank() -> if (newCar) "이날 열리도록 예약해 둔 편지" else ""
        newCar -> "${state.fromNames} · 이날 열리도록 예약해 둔 편지"
        else -> state.fromNames
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        EnvelopeDrawing(Modifier.size(width = 170.dp, height = 112.dp))
        Text(
            text = if (newCar) "새 차의 첫 시동" else "편지가 열리고 있어요",
            style = DarkH2,
            textAlign = TextAlign.Center,
        )
        if (fromLine.isNotEmpty()) {
            Text(
                text = fromLine,
                style = DarkSubStyle,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 낭독 중·낭독 끝: 왼쪽에 나이테(새 차 첫 시동) 또는 가족 사진, 오른쪽에 편지 글 */
@Composable
private fun LetterReading(state: Screen.LetterOpen, enabled: Boolean, actions: NaitaeActions) {
    val rings = state.rings
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        Box(
            modifier = Modifier.weight(0.42f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            if (rings != null) {
                RingsView(
                    rings = rings.rings,
                    modifier = Modifier.aspectRatio(1f),
                    accent = NaitaeColors.Purple,
                    centerScene = rings.topic.scene,
                )
            } else {
                PhotoFrame(
                    scene = SceneKind.FAMILY_TRIP,
                    modifier = Modifier.aspectRatio(400f / 280f),
                )
            }
        }
        Column(modifier = Modifier.weight(0.58f)) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)) {
                // 글자가 늘어나도 자리가 흔들리지 않게, 편지 전체 크기만큼 자리를 먼저 잡아 둔다
                Text(
                    text = state.letter.text,
                    style = LetterTextStyle,
                    modifier = Modifier.alpha(0f).clearAndSetSemantics { },
                )
                TypingText(
                    text = state.shownText,
                    typing = state.phase == LetterPhase.READING,
                    style = LetterTextStyle,
                )
            }
            Spacer(Modifier.height(14.dp))
            if (state.fromNames.isNotBlank()) {
                Text(
                    text = "— ${state.fromNames}",
                    style = DarkSubStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(22.dp))
            NaitaeButton(
                text = "닫기",
                onClick = { actions.closeLetter() },
                modifier = Modifier.heightIn(min = TouchMin),
                kind = ButtonKind.DARK,
                enabled = enabled,
            )
        }
    }
}
