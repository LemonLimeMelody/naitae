package com.naitae.connect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naitae.connect.ui.theme.NaitaeColors

/*
 * 공통 부품 (계약 파일: 시그니처 유지, 모양 다듬기는 ui-A 가 할 수 있음)
 * 차량 화면은 팔을 뻗어 누르므로 버튼은 크게(높이 56dp 이상), 글자는 크게 쓴다.
 */

object NaitaeType {
    val H1 = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = NaitaeColors.Navy)
    val H2 = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NaitaeColors.Navy)
    val Body = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = NaitaeColors.Navy)
    val Sub = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, color = NaitaeColors.Ink2)
    val Caption = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = NaitaeColors.Ink2)
    val Button = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

enum class ButtonKind { PRIMARY, SECONDARY, NAVY, DARK }

/** 큰 버튼. enabled=false 면 흐리게 보이고 눌리지 않는다(주행 중 잠금에 씀). */
@Composable
fun NaitaeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
) {
    val (bg, fg) = when (kind) {
        ButtonKind.PRIMARY -> NaitaeColors.Amber to NaitaeColors.White
        ButtonKind.SECONDARY -> NaitaeColors.Light to NaitaeColors.Navy
        ButtonKind.NAVY -> NaitaeColors.Navy to NaitaeColors.White
        ButtonKind.DARK -> NaitaeColors.DarkCard to NaitaeColors.DarkText
    }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = 26.dp, vertical = 16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = NaitaeType.Button, color = fg)
    }
}

/** 작은 꼬리표: "일정", "메모", "D-2", "낚시 · 오늘 일정" */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = NaitaeColors.Soft,
    content: Color = NaitaeColors.AmberInk,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = NaitaeType.Caption.copy(fontWeight = FontWeight.Bold), color = content)
    }
}

/** 주제 칩: 본업 · 낚시 · 러닝 · 골프 · 우리 가족 여행 */
@Composable
fun TopicChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) NaitaeColors.Amber else NaitaeColors.Light)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = NaitaeType.Sub.copy(fontWeight = FontWeight.Bold),
            color = if (selected) NaitaeColors.White else NaitaeColors.Navy,
        )
    }
}
