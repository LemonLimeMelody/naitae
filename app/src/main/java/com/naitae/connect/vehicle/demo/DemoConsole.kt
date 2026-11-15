package com.naitae.connect.vehicle.demo

import com.naitae.connect.core.model.Place
import com.naitae.connect.data.SeedData
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.SimState
import com.naitae.connect.vehicle.SimulatorConsole
import com.naitae.connect.vehicle.VehicleSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pleos 모드의 시연 패널. 가상 콘솔과 같은 계약(SimulatorConsole)이지만 차의 신호는 넣지 않는다.
 *
 *  - 하는 것: 날짜(setDay), 시각(setClock), 목적지 도착 이벤트(arrive), 다음 음성 입력(sayNext)
 *  - 하지 않는 것: 시동(ignition), 기어(gear), 좌석(setSeat). Pleos Virtual Console 이 넣는다.
 *  - state 의 기어·주행거리·좌석·VIN·탑승 여부는 실제 차량 신호를 비춘 값이다.
 *
 * arrive 는 도착 이벤트만 내고 기어를 건드리지 않는다. 기어가 D 면 앱은 P 가 될 때까지 화면 반응을 미루고,
 * 이미 P 면 그 자리에 도착해 세운 것으로 처리한다(평소 도착과 기록 초안 준비는 조용히 지나가고, 다시 보기는 바로 열린다).
 */
internal class DemoConsole(
    private val signals: VehicleSignals,
    private val clock: DemoClock,
    private val navi: DemoNavi,
    private val voiceIn: DemoVoiceInput,
    private val proximity: DemoProximity,
    private val scope: CoroutineScope,
    private val onClockSet: (day: ScenarioDay, at: LocalDateTime) -> Unit,
) : SimulatorConsole {

    private val _state = MutableStateFlow(
        SimState(
            day = clock.day,
            now = clock.now().truncatedTo(ChronoUnit.SECONDS),
            ignitionOn = signals.ignitionOn.value,
            gear = signals.gear.value,
            odometerKm = signals.odometerKm.value ?: 0f,
            seats = signals.seats.value,
            carModel = carModelOf(clock.day),
        ),
    )
    override val state: StateFlow<SimState> = _state.asStateFlow()

    override val places: List<Place> get() = SeedData.places

    override val controlsVehicle: Boolean get() = false

    init {
        scope.launch { signals.gear.collect { gear -> _state.update { it.copy(gear = gear) } } }
        scope.launch { signals.odometerKm.collect { km -> if (km != null) _state.update { it.copy(odometerKm = km) } } }
        scope.launch { signals.seats.collect { seats -> _state.update { it.copy(seats = seats) } } }
        scope.launch {
            signals.ignitionOn.collect { on ->
                _state.update { it.copy(ignitionOn = on) }
                // VIN 은 연결된 뒤에야 읽힌다. 아직 모르면 탈 때마다 다시 읽어 본다.
                if (on && _state.value.vin.isBlank()) scope.launch { refreshVin() }
            }
        }
        scope.launch {
            // 기어 값이 왔다 = 차량 서비스에 연결됐다.
            signals.gear.first { it != GearState.UNKNOWN }
            refreshVin()
        }
        scope.launch { navi.events.collect { onNavi(it) } }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                _state.update { it.copy(now = clock.now().truncatedTo(ChronoUnit.SECONDS)) }
            }
        }
    }

    override fun setDay(day: ScenarioDay) {
        val start = SeedData.dateTimeOf(day)
        clock.set(day, start)
        voiceIn.clearQueued()
        SdkLog.sys("시연 패널 · ${day.label} · ${start.format(DAY_FORMAT)}")
        _state.update { it.copy(day = day, now = start, carModel = carModelOf(day), guidanceTo = null) }
        onClockSet(day, start)
        // 20년 뒤의 날에는 차가 VIN 을 알려 주지 않아도 새 차 VIN 이 정해지므로 표시를 다시 맞춘다.
        scope.launch { refreshVin() }
    }

    override fun setClock(hour: Int, minute: Int) {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val target = clock.now().toLocalDate().atTime(h, m)
        clock.set(clock.day, target)
        SdkLog.sys("시연 패널 · 시각 = ${String.format(Locale.KOREA, "%02d:%02d", h, m)}")
        _state.update { it.copy(now = target) }
        onClockSet(clock.day, target)
    }

    override fun ignition(on: Boolean) = notHere()

    override fun gear(gear: GearState) = notHere()

    override fun setSeat(index: Int, occupied: Boolean) = notHere()

    override fun arrive(place: Place) {
        val distance = SeedData.demoDistanceKm(place)
        val minutes = (distance * MINUTES_PER_KM + BASE_TRIP_MINUTES).roundToInt()
        SdkLog.event("시연 패널 · 도착 이벤트 넣음 → ${place.name} · ${distance.roundToInt()}km · ${minutes}분")
        proximity.arrive(place)
        navi.inject(NaviEvent.Arrived(place.name, place.lat, place.lon, distance, minutes))
    }

    override fun sayNext(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        voiceIn.sayNext(said)
        SdkLog.sys("시연 패널 · 다음 음성 입력 = \"$said\"")
    }

    private fun notHere() {
        SdkLog.sys("시연 패널 · 탑승(운전석 착좌) · 기어 · 좌석은 Pleos Virtual Console 에서 바꿉니다")
    }

    /** 길 안내 중인 목적지를 목적지 버튼에 표시한다. */
    private fun onNavi(event: NaviEvent) {
        val target = when (event) {
            is NaviEvent.GuidanceStarted -> places.firstOrNull { it.name == event.name }
            is NaviEvent.Arrived, NaviEvent.GuidanceStopped -> null
        }
        _state.update { it.copy(guidanceTo = target) }
    }

    private suspend fun refreshVin() {
        val vin = signals.vin().orEmpty()
        _state.update { it.copy(vin = vin) }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MINUTES_PER_KM = 1.5f
        const val BASE_TRIP_MINUTES = 4f
        const val CAR_MODEL_NEW = "새 현대차"
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** 가상 데이터의 차 이름. 20년 뒤는 선물 받은 새 차의 날이다. */
        fun carModelOf(day: ScenarioDay): String =
            if (day == ScenarioDay.TWENTY_YEARS_LATER) CAR_MODEL_NEW else SeedData.CAR_MODEL
    }
}
