package com.naitae.connect.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.SdkLogKind
import com.naitae.connect.vehicle.SdkLogLine
import com.naitae.connect.vehicle.SimState
import com.naitae.connect.vehicle.SimulatorConsole
import java.util.Locale

// 콘솔은 차량 화면이 아니라 발표자가 쓰는 도구라서 화면 토큰(NaitaeColors) 대신 목업 콘솔의 어두운 색을 쓴다
private val PanelBg = Color(0xFF171C22)
private val ButtonBg = Color(0xFF232A33)
private val ButtonLine = Color(0xFF2F3945)
private val ButtonText = Color(0xFFC9D2DC)
private val SelectedBg = Color(0xFFD98E2B)
private val LabelText = Color(0xFF8D9BAA)
private val TitleText = Color(0xFFFFFFFF)
private val TileBg = Color(0xFF1C2229)
private val LogBg = Color(0xFF0E1216)
private val LogApi = Color(0xFFF5C784)
private val LogEvent = Color(0xFF8FD0E8)
private val LogSys = Color(0xFFB9A5DD)

private val SeatLabels = listOf("운전", "조수", "뒤 왼", "뒤 오")

/** 이 너비부터는 날짜 3칸 · 목적지 4칸 · 할 말 4칸으로 놓는다 */
private val WideWidth = 480.dp

/** 음성 입력을 흉내 낼 때 고르는 말(시나리오의 한마디) */
private val SayPresets = listOf("내일 세차 예약", "올해 첫 감성돔", "아빠, 내년에 또 오자!", "안내해 줘")

/** 시연 패널에서 차의 신호를 어디서 넣는지 알려 주는 한 줄 */
private const val VIRTUAL_CONSOLE_HINT =
    "기어 · 좌석(운전석 = 탑승) · 조명 · 공조는 Pleos Virtual Console 에서 바꿉니다: " +
        "adb forward tcp:8088 tcp:8088 → http://127.0.0.1:8088/"

/**
 * 가상 콘솔 패널. 화면 오른쪽에 붙는다(너비 360~420dp 기준).
 * 날짜 6개, 시동/기어, 목적지 도착, 좌석 착석, 시각, 다음에 할 말, 지금 값, SDK 호출 로그(SdkLog.lines).
 * Pleos 모드의 시연 패널(console.controlsVehicle 이 false)에서는 차의 신호를 넣는 시동/기어·좌석 묶음을 빼고,
 * 그 신호를 Pleos Virtual Console 에서 넣는다는 안내 한 줄을 보여 준다.
 * 위쪽 조작부는 길면 스크롤되고, 로그는 남은 높이를 쓰며 새 줄이 오면 맨 아래로 따라간다.
 * 누르는 것(조작)을 위에, 읽기만 하는 것(지금 값)을 아래에 두어 큰 화면에서는 스크롤 없이 모든 조작이 보이게 한다.
 * cabin 을 넘기면 지금 값에 실내(조명·열선·공조)도 보여 준다.
 */
@Composable
fun ConsoleOverlay(console: SimulatorConsole, modifier: Modifier = Modifier, cabin: CabinState? = null) {
    val sim by console.state.collectAsStateWithLifecycle()
    val lines by SdkLog.lines.collectAsStateWithLifecycle()
    var lastSaid by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier.background(PanelBg)) {
        // 조작부가 로그 자리를 다 차지하지 않게 높이의 60%까지만(1080 높이에서 조작 버튼이 모두 들어가는 높이)
        val controlsMax = if (constraints.hasBoundedHeight) maxHeight * 0.6f else Dp.Unspecified
        // 패널이 넓으면 한 줄에 더 많이 놓아 조작부의 높이를 줄인다
        val wide = constraints.hasBoundedWidth && maxWidth >= WideWidth
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 시연 패널은 실제 차에 연결되어 있다. 기어·좌석은 여기서 넣지 않는다.
            val simulated = console.controlsVehicle
            if (simulated) {
                PanelTitle("가상 콘솔", "Virtual Console 입력을 흉내 냄")
            } else {
                PanelTitle("시연 패널", "Pleos 모드 · 날짜 · 도착 · 할 말")
                Text(VIRTUAL_CONSOLE_HINT, color = LogEvent, fontSize = 14.sp, lineHeight = 19.sp)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = controlsMax)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ControlGroup("날짜") {
                    ButtonGrid(ScenarioDay.values().toList(), columns = if (wide) 3 else 2) { day, cell ->
                        ConsoleButton(
                            text = day.label,
                            selected = sim.day == day,
                            onClick = { console.setDay(day) },
                            modifier = cell,
                        )
                    }
                }
                if (simulated) ControlGroup("차") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleButton("시동", sim.ignitionOn, { console.ignition(true) }, Modifier.weight(1f))
                        ConsoleButton(
                            text = "기어 P",
                            selected = sim.ignitionOn && sim.gear == GearState.PARK,
                            onClick = { console.gear(GearState.PARK) },
                            modifier = Modifier.weight(1f),
                        )
                        ConsoleButton(
                            text = "기어 D",
                            selected = sim.ignitionOn && sim.gear == GearState.DRIVE,
                            onClick = { console.gear(GearState.DRIVE) },
                            modifier = Modifier.weight(1f),
                        )
                        ConsoleButton("시동 끄기", false, { console.ignition(false) }, Modifier.weight(1.2f))
                    }
                }
                if (console.places.isNotEmpty()) {
                    ControlGroup(if (simulated) "목적지 도착 (즐겨찾기 = 주제 장소)" else "목적지 도착 이벤트 넣기 (기어는 그대로)") {
                        ButtonGrid(console.places, columns = if (wide) 4 else 3) { place, cell ->
                            ConsoleButton(
                                text = place.name,
                                selected = sim.guidanceTo?.name == place.name,
                                onClick = { console.arrive(place) },
                                modifier = cell,
                            )
                        }
                    }
                }
                if (simulated) ControlGroup("좌석 착석 (운전석 · 조수석 · 뒷좌석 왼쪽 · 오른쪽)") {
                    val occupied = listOf(
                        sim.seats.driver,
                        sim.seats.frontPassenger,
                        sim.seats.rearLeft,
                        sim.seats.rearRight,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SeatLabels.forEachIndexed { i, label ->
                            // 운전석은 늘 앉아 있다
                            ConsoleButton(
                                text = label,
                                selected = if (i == 0) true else occupied[i],
                                onClick = { console.setSeat(i, !occupied[i]) },
                                modifier = Modifier.weight(1f),
                                enabled = i != 0,
                            )
                        }
                    }
                }
                ControlGroup("시각 바꾸기") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleButton(
                            text = "+10분",
                            selected = false,
                            onClick = {
                                val t = sim.now.plusMinutes(10)
                                console.setClock(t.hour, t.minute)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ConsoleButton(
                            text = "+1시간",
                            selected = false,
                            onClick = {
                                val t = sim.now.plusHours(1)
                                console.setClock(t.hour, t.minute)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ConsoleButton(
                            text = "퇴근 18:40",
                            selected = false,
                            onClick = { console.setClock(18, 40) },
                            modifier = Modifier.weight(1.2f),
                        )
                    }
                }
                ControlGroup(if (simulated) "다음에 할 말 (음성 입력 흉내)" else "다음에 할 말 (마이크 대신)") {
                    ButtonGrid(SayPresets, columns = if (wide) 4 else 2) { text, cell ->
                        ConsoleButton(
                            text = text,
                            selected = lastSaid == text,
                            onClick = {
                                lastSaid = text
                                console.sayNext(text)
                            },
                            modifier = cell,
                        )
                    }
                }
                // 읽기만 하는 값은 조작과 조금 띄운다
                Spacer(Modifier.height(2.dp))
                ControlGroup("지금 값") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!simulated) ValueTile("탑승", if (sim.ignitionOn) "탑승" else "내림", Modifier.weight(0.7f))
                        ValueTile("기어", gearLabel(sim, always = !simulated), Modifier.weight(0.7f))
                        ValueTile("주행거리", odometerLabel(sim.odometerKm), Modifier.weight(1.2f))
                        ValueTile("VIN 끝자리", vinTail(sim.vin), Modifier.weight(1.2f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ValueTile("날짜 · 시각", clockLabel(sim), Modifier.weight(1.4f))
                        ValueTile("차", sim.carModel.ifBlank { "-" }, Modifier.weight(1f))
                    }
                    if (cabin != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ValueTile("실내 조명", ambientLabel(cabin), Modifier.weight(1.2f))
                            ValueTile("시트 열선", seatHeatLabel(cabin), Modifier.weight(1f))
                            ValueTile("핸들 열선", steeringHeatLabel(cabin), Modifier.weight(1f))
                            ValueTile("공조", climateLabel(cabin), Modifier.weight(1.4f))
                        }
                    }
                }
            }
            PanelTitle("SDK 호출 로그", "앱이 부르는 API와 받는 이벤트")
            SdkLogBox(lines, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun PanelTitle(title: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = TitleText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            text = hint,
            color = LabelText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ControlGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = LabelText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        content()
    }
}

/** 버튼을 columns 칸씩 같은 너비로 늘어놓는다(실험적 FlowRow 를 쓰지 않으려고 직접 나눈다). */
@Composable
private fun <T> ButtonGrid(
    items: List<T>,
    columns: Int,
    cell: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    items.chunked(columns).forEach { rowItems ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rowItems.forEach { item -> cell(item, Modifier.weight(1f)) }
            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ConsoleButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .alpha(if (enabled || selected) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) SelectedBg else ButtonBg)
            .border(1.dp, if (selected) SelectedBg else ButtonLine, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else ButtonText,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ValueTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TileBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, color = LabelText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = value,
            color = ButtonText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** SDK 호출 로그: 최신 줄이 맨 아래. 종류별 색(API 호박색, EVENT 청록, SYS 보라), 고정폭 글자 */
@Composable
private fun SdkLogBox(lines: List<SdkLogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(LogBg),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        items(count = lines.size) { i ->
            val line = lines.getOrNull(i)
            if (line != null) {
                Text(
                    text = line.text,
                    color = when (line.kind) {
                        SdkLogKind.API -> LogApi
                        SdkLogKind.EVENT -> LogEvent
                        SdkLogKind.SYS -> LogSys
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 시뮬레이터는 시동이 꺼지면 기어를 비워 보이고, 시연 패널(always)은 차가 알려 준 기어를 늘 보여 준다. */
private fun gearLabel(sim: SimState, always: Boolean = false): String {
    if (!sim.ignitionOn && !always) return "-"
    return when (sim.gear) {
        GearState.PARK -> "P"
        GearState.REVERSE -> "R"
        GearState.NEUTRAL -> "N"
        GearState.DRIVE -> "D"
        GearState.UNKNOWN -> "-"
    }
}

private fun ambientLabel(cabin: CabinState): String {
    val color = cabin.ambientColor ?: return "-"
    val level = cabin.ambientBrightness ?: return color.label
    return if (level <= 0) "끔" else "${color.label} $level"
}

private fun seatHeatLabel(cabin: CabinState): String {
    val level = cabin.seatHeatLevel ?: return "-"
    return if (level <= 0) "끔" else "${level}단"
}

private fun steeringHeatLabel(cabin: CabinState): String = when (cabin.steeringHeat) {
    null -> "-"
    true -> "켬"
    false -> "끔"
}

private fun climateLabel(cabin: CabinState): String {
    val temp = cabin.cabinTempC?.let { String.format(Locale.US, "%.0f℃", it) }
    val fan = cabin.fanLevel?.let { "바람 $it" }
    return listOfNotNull(temp, fan).joinToString(" · ").ifEmpty { "-" }
}

private fun odometerLabel(km: Float): String = String.format(Locale.US, "%,dkm", Math.round(km))

private fun vinTail(vin: String): String = if (vin.isBlank()) "-" else "…" + vin.takeLast(6)

private fun clockLabel(sim: SimState): String {
    val now = sim.now
    return String.format(
        Locale.US,
        "%d.%02d.%02d %02d:%02d",
        now.year,
        now.monthValue,
        now.dayOfMonth,
        now.hour,
        now.minute,
    )
}
