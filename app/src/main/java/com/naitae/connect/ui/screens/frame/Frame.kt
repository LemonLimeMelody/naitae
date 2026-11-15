package com.naitae.connect.ui.screens.frame

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.core.model.Memo
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.components.TagPill
import com.naitae.connect.ui.theme.NaitaeColors
import com.naitae.connect.vehicle.GearState
import java.time.LocalDate

/*
 * 공통 틀 부품 (ui-A). NaitaeRoot 와 ui-A 화면이 함께 쓴다.
 * 화면은 960×540dp 를 기준으로 그리고, NaitaeRoot 가 실제 화면 크기에 맞춰 통째로 키운다.
 */

internal object FrameSpec {
    const val BASE_WIDTH = 960f
    const val BASE_HEIGHT = 540f
    const val MAX_SCALE = 2.5f

    val TopBarHeight = 48.dp
    val BodyPadH = 28.dp
    val BodyPadV = 18.dp

    /** 화면 아래 자막이 본문을 덮는 높이. 읽어 주는 동안 봐야 하는 내용은 이 위에 둔다. */
    val CaptionReserve = 46.dp

    /** 시동이 꺼진 화면 바탕 */
    val OffBg = Color(0xFF05070A)
}

/** 기준 화면보다 클 때만 키운다(작을 때 줄이면 글자가 14sp 아래로 내려가므로 줄이지 않는다). */
internal fun designScale(width: Dp, height: Dp): Float {
    val byWidth = width.value / FrameSpec.BASE_WIDTH
    val byHeight = height.value / FrameSpec.BASE_HEIGHT
    val fit = if (byWidth < byHeight) byWidth else byHeight
    return fit.coerceIn(1f, FrameSpec.MAX_SCALE)
}

/** 주제 그림 종류마다 나이테 선 색을 달리한다(목업의 주제별 색). */
internal fun SceneKind.accentColor(): Color = when (this) {
    SceneKind.WORK -> NaitaeColors.Slate
    SceneKind.FISHING -> Color(0xFF5B8DB8)
    SceneKind.RUNNING -> Color(0xFF5FA37A)
    SceneKind.GOLF -> Color(0xFFB5A04A)
    SceneKind.FAMILY_TRIP -> NaitaeColors.Purple
    SceneKind.GENERIC -> NaitaeColors.Amber
}

internal fun Screen.isDark(): Boolean = when (this) {
    Screen.EngineOff, is Screen.RecallView, is Screen.LetterArrive, is Screen.LetterOpen -> true
    else -> false
}

/** "토요일" */
internal fun koreanDayOfWeek(epochDay: Long): String {
    val names = "월화수목금토일"
    val index = LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1
    return names[index.coerceIn(0, 6)] + "요일"
}

/** 장소 이름 뒤에 '로/으로'를 붙인다: 낚시터로, 수목원으로 */
internal fun withDirectionParticle(name: String): String {
    val last = name.lastOrNull() ?: return name
    if (last !in '가'..'힣') return name + "(으)로"
    val finalConsonant = (last.code - 0xAC00) % 28
    // 받침이 없거나 ㄹ 받침이면 '로'
    return if (finalConsonant == 0 || finalConsonant == 8) name + "로" else name + "으로"
}

/** 32.0 → "32", 32.5 → "32.5" */
internal fun formatKm(km: Float): String {
    val tenths = Math.round(km * 10f)
    return if (tenths % 10 == 0) (tenths / 10).toString() else (tenths / 10).toString() + "." + (tenths % 10)
}

// ───────── 상단 바 ─────────

@Composable
internal fun FrameTopBar(
    carLabel: String,
    clockLabel: String,
    gear: GearState,
    dark: Boolean,
    engineOff: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = tween<Color>(durationMillis = 240)
    val bg by animateColorAsState(
        targetValue = if (engineOff) FrameSpec.OffBg else if (dark) NaitaeColors.DarkBg else NaitaeColors.White,
        animationSpec = spec,
        label = "topBarBg",
    )
    val brand by animateColorAsState(
        targetValue = if (dark) NaitaeColors.DarkText else NaitaeColors.Navy,
        animationSpec = spec,
        label = "topBarBrand",
    )
    val sub by animateColorAsState(
        targetValue = if (dark) NaitaeColors.DarkSub else NaitaeColors.Ink2,
        animationSpec = spec,
        label = "topBarSub",
    )
    val line by animateColorAsState(
        targetValue = if (engineOff) FrameSpec.OffBg else if (dark) NaitaeColors.DarkCard else NaitaeColors.Light,
        animationSpec = spec,
        label = "topBarLine",
    )
    Column(modifier.fillMaxWidth().background(bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FrameSpec.TopBarHeight - 1.dp)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("「나이테」", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = brand, maxLines = 1)
            if (carLabel.isNotBlank()) {
                Text(
                    text = " · $carLabel",
                    fontSize = 16.sp,
                    color = sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 남는 너비를 모두 차지해 시계와 기어를 오른쪽 끝으로 민다
                    // (fill = false 인 칸과 Spacer 가 너비를 반씩 나눠 가지면 시계가 가운데에 떠 버린다).
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (clockLabel.isNotBlank()) {
                Text(clockLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = sub, maxLines = 1)
                Spacer(Modifier.width(12.dp))
            }
            GearBadge(gear)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
    }
}

/** 기어 표시: P 는 호박색(화면을 쓸 수 있음), 그 밖은 남색 */
@Composable
private fun GearBadge(gear: GearState) {
    val letter = when (gear) {
        GearState.PARK -> "P"
        GearState.DRIVE -> "D"
        GearState.REVERSE -> "R"
        GearState.NEUTRAL -> "N"
        GearState.UNKNOWN -> "-"
    }
    val bg = when (gear) {
        GearState.PARK -> NaitaeColors.Amber
        GearState.UNKNOWN -> NaitaeColors.Slate
        else -> NaitaeColors.Navy
    }
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = NaitaeColors.White, maxLines = 1)
    }
}

// ───────── 시동 꺼짐 ─────────

@Composable
internal fun EngineOffBody(modifier: Modifier = Modifier) {
    Box(modifier.background(FrameSpec.OffBg), contentAlignment = Alignment.Center) {
        Text(
            text = "시동을 걸면 「나이테」가 깨어나요",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = NaitaeColors.Slate.copy(alpha = 0.75f),
        )
    }
}

// ───────── 주행 중 잠금 ─────────

/** 보이지 않는 덮개. 아래 화면으로 가는 터치를 모두 삼킨다(주행 중에는 음성만 쓴다). */
@Composable
internal fun DrivingLock(modifier: Modifier = Modifier) {
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                }
            }
        },
    )
}

// ───────── 음성 자막 ─────────

@Composable
internal fun VoiceCaptionBar(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(NaitaeColors.Navy.copy(alpha = 0.94f))
            .border(1.dp, NaitaeColors.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Equalizer()
        Text(
            text = text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = NaitaeColors.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private val EqualizerBars = listOf(8, 14, 18, 11, 16)

/** 읽는 중임을 알리는 작은 막대 다섯 개 */
@Composable
private fun Equalizer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val levels = EqualizerBars.mapIndexed { i, _ ->
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 150),
            ),
            label = "equalizerBar",
        )
    }
    Canvas(modifier.size(width = 32.dp, height = 18.dp)) {
        val barWidth = 4.dp.toPx()
        val step = 7.dp.toPx()
        EqualizerBars.forEachIndexed { i, h ->
            // 그리는 단계에서만 값을 읽어 다시 짜기(recomposition) 없이 움직인다
            val barHeight = h.dp.toPx() * levels[i].value
            drawRoundRect(
                color = NaitaeColors.Ring2,
                topLeft = Offset(i * step, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

// ───────── 메모 카드 ─────────

@Composable
internal fun MemoToastCard(
    memo: Memo,
    author: String?,
    tapEnabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val title = if (author.isNullOrBlank()) "메모 · ${memo.text}" else "$author 메모 · ${memo.text}"
    val sub = when (val remind = memo.remind) {
        is MemoRemind.EveningIgnition -> "퇴근길이라 다시 알려 드려요"
        is MemoRemind.AtPlace -> "${remind.place.name} 근처라 다시 알려 드려요"
        MemoRemind.NextIgnition -> null
    }
    Row(
        modifier = modifier
            .shadow(elevation = 12.dp, shape = shape)
            .clip(shape)
            .background(NaitaeColors.Navy)
            .clickable(enabled = tapEnabled, onClick = onDismiss)
            .heightIn(min = 72.dp)
            .padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TagPill("메모", background = NaitaeColors.Purple, content = NaitaeColors.White)
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = NaitaeColors.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    fontSize = 15.sp,
                    color = NaitaeColors.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
