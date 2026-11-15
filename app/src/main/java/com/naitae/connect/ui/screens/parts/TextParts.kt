package com.naitae.connect.ui.screens.parts

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.ui.components.NaitaeType
import com.naitae.connect.ui.components.TagPill
import com.naitae.connect.ui.theme.NaitaeColors
import java.util.Locale
import kotlin.math.roundToInt

/** 어두운 화면(다시 보기·편지)의 글자 */
internal val DarkH1 = NaitaeType.H1.copy(color = NaitaeColors.DarkText)
internal val DarkH2 = NaitaeType.H2.copy(color = NaitaeColors.DarkText)
internal val DarkSubStyle = NaitaeType.Sub.copy(color = NaitaeColors.DarkSub)

/** 주행거리 숫자("32", "12,840", "4.5"). 단위는 붙이지 않는다. */
internal fun kmLabel(km: Float): String {
    val safe = if (km.isNaN() || km < 0f) 0f else km
    val rounded = safe.roundToInt()
    return if (safe >= 10f || safe == rounded.toFloat()) {
        String.format(Locale.KOREA, "%,d", rounded)
    } else {
        String.format(Locale.KOREA, "%.1f", safe)
    }
}

/** 한마디를 따옴표로 감싼다 */
internal fun quoted(text: String): String = "\"" + text.trim() + "\""

/** 주제 나이테의 색. 가족 여행은 보라, 나머지는 기본 호박색 */
internal fun topicAccent(scene: SceneKind?): Color =
    if (scene == SceneKind.FAMILY_TRIP) NaitaeColors.Purple else NaitaeColors.Amber

private const val CaretId = "naitae-caret"

/** 붙여 쓴 글자 사이에 넣어 그 자리에서 줄이 바뀌지 않게 하는, 폭이 없는 문자(WORD JOINER) */
private const val WORD_JOINER = '\u2060'

/**
 * 한글은 글자마다 줄을 바꿀 수 있어 "아빠 / 가," 처럼 낱말 가운데에서 끊긴다.
 * 낱말 안의 글자 사이를 이어 붙여 띄어쓰기에서만 줄이 바뀌게 한다(큰 글씨로 읽는 편지 글에 쓴다).
 */
internal fun keepWordsTogether(text: String): String {
    if (text.length < 2) return text
    val out = StringBuilder(text.length * 2)
    for ((index, ch) in text.withIndex()) {
        if (index > 0 && !ch.isWhitespace() && !text[index - 1].isWhitespace()) out.append(WORD_JOINER)
        out.append(ch)
    }
    return out.toString()
}

/**
 * 받아 적히는 글자. typing 이면 끝에 깜빡이는 막대를 붙인다.
 * 막대는 글꼴에 없는 기호일 수 있어 글자 대신 그려 넣는다.
 */
@Composable
internal fun TypingText(
    text: String,
    typing: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (typing) {
        val transition = rememberInfiniteTransition(label = "caret")
        val caretAlpha = transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "caretAlpha",
        )
        val inline = remember(caretAlpha) {
            mapOf(
                CaretId to InlineTextContent(
                    Placeholder(
                        width = 0.5.em,
                        height = 1.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(start = 4.dp)
                            .graphicsLayer { alpha = caretAlpha.value }
                            .background(NaitaeColors.Amber, RoundedCornerShape(2.dp)),
                    )
                },
            )
        }
        val annotated = remember(text) {
            buildAnnotatedString {
                append(text)
                appendInlineContent(CaretId)
            }
        }
        Text(
            text = annotated,
            modifier = modifier,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            inlineContent = inline,
        )
    } else {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "듣고 있어요" 처럼 천천히 깜빡이는 꼬리표 */
@Composable
internal fun PulsingPill(text: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pill")
    val pillAlpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pillAlpha",
    )
    TagPill(text = text, modifier = modifier.graphicsLayer { alpha = pillAlpha.value })
}
