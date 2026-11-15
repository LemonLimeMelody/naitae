package com.naitae.connect.vehicle.demo

import android.os.SystemClock
import com.naitae.connect.data.SeedData
import com.naitae.connect.vehicle.AppClock
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VehiclePlatform
import com.naitae.connect.vehicle.VehicleSignals
import kotlinx.coroutines.CoroutineScope
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pleos 모드의 시연 층. 실제 Pleos 플랫폼을 감싸, Pleos Virtual Console 이 넣어 주지 못하는 것만 채운다.
 *
 *  - 그대로 두는 것: 차량 신호(기어·주행거리·좌석·탑승), 실내 제어, 음성 출력. 모두 실제 SDK 다.
 *  - 채우는 것: 날짜·시각(시나리오 날짜에서 시작해 실제 시간만큼 흐르는 시계), 목적지 도착 이벤트,
 *    마이크 대신 넣는 말, 도착한 곳의 근접 알림. 앱 화면의 시연 패널(DemoConsole)에서 넣는다.
 *
 * 기어·좌석·조명·공조는 시연 패널이 아니라 Virtual Console 에서 바꾼다.
 */
object DemoPlatform {

    /** 지난 실행에서 이어 갈 시연 날짜와 시각 */
    data class Resume(val day: ScenarioDay, val now: LocalDateTime)

    /**
     * @param resume 지난 실행의 날짜·시각. 없으면 발표 시나리오의 첫날(평일 아침)에서 시작한다.
     * @param monotonicNanos 시연 시계가 기대는 단조 시계
     * @param onClockSet 시연 날짜·시각을 정할 때마다(처음 시작할 때 포함) 불린다. 다음 실행이 이어 갈 수 있게 적어 두는 데 쓴다.
     */
    fun wrap(
        real: VehiclePlatform,
        scope: CoroutineScope,
        resume: Resume? = null,
        monotonicNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
        onClockSet: (day: ScenarioDay, at: LocalDateTime) -> Unit = { _, _ -> },
    ): VehiclePlatform {
        val day = resume?.day ?: ScenarioDay.WEEKDAY
        val start = resume?.now ?: SeedData.dateTimeOf(day)
        val clock = DemoClock(day, start, monotonicNanos)
        val signals = DemoSignals(real.signals, clock)
        val navi = DemoNavi(real.navi, scope)
        val voiceIn = DemoVoiceInput(real.voiceIn)
        val proximity = DemoProximity(real.proximity, scope)
        val console = DemoConsole(signals, clock, navi, voiceIn, proximity, scope, onClockSet)
        if (resume == null) onClockSet(day, start)
        SdkLog.sys(
            "Pleos 시연 패널 준비 · ${day.label} · ${start.format(START_FORMAT)}" +
                (if (resume != null) " (지난 실행에서 이어 감)" else ""),
        )
        return VehiclePlatform(
            kind = real.kind,
            signals = signals,
            cabin = real.cabin,
            navi = navi,
            voiceOut = real.voiceOut,
            voiceIn = voiceIn,
            proximity = proximity,
            clock = AppClock { clock.now() },
            console = console,
        )
    }

    private val START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}

/** 시연 날짜와 시계. 정한 순간부터 실제 시간만큼 흐른다. */
internal class DemoClock(
    day: ScenarioDay,
    start: LocalDateTime,
    private val monotonicNanos: () -> Long,
) {
    private class Anchor(val day: ScenarioDay, val base: LocalDateTime, val atNanos: Long)

    @Volatile
    private var anchor = Anchor(day, start, monotonicNanos())

    val day: ScenarioDay get() = anchor.day

    fun now(): LocalDateTime {
        val a = anchor
        return a.base.plusNanos(monotonicNanos() - a.atNanos)
    }

    fun set(day: ScenarioDay, base: LocalDateTime) {
        anchor = Anchor(day, base, monotonicNanos())
    }
}

/**
 * 차량 신호는 실제 값 그대로 넘긴다. VIN 만, 차가 알려 주지 않는 환경(에뮬레이터)에서 '20년 뒤 · 새 차'의 날에
 * 정해 둔 새 차 VIN 으로 대신한다. 그날은 차의 VIN 이 계정에 등록된 차와 달라야 이어받기가 돌기 때문이다.
 */
internal class DemoSignals(
    private val real: VehicleSignals,
    private val clock: DemoClock,
) : VehicleSignals by real {

    override suspend fun vin(): String? =
        real.vin() ?: if (clock.day == ScenarioDay.TWENTY_YEARS_LATER) SeedData.VIN_NEW_CAR else null
}
