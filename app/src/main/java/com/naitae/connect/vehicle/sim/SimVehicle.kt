package com.naitae.connect.vehicle.sim

import com.naitae.connect.core.model.Place
import com.naitae.connect.data.SeedData
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.ProximityEvent
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.SeatOccupancy
import com.naitae.connect.vehicle.SimState
import com.naitae.connect.vehicle.SimulatorConsole
import com.naitae.connect.vehicle.VehicleSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 시뮬레이터의 차 한 대. 가상 콘솔(SimulatorConsole)이 넣는 값을 상태로 들고,
 * 차량 신호(VehicleSignals)·시계·내비·근접 이벤트의 원천이 된다.
 */
internal class SimVehicle(
    private val scope: CoroutineScope,
    private val cabin: SimCabin,
) : SimulatorConsole {

    /** 시계 기준점: base 를 정한 순간의 단조 시계 값. 이후로는 실제 시간만큼 흐른다. */
    private class ClockAnchor(val base: LocalDateTime, val atNanos: Long)

    private val lock = Any()

    @Volatile
    private var anchor = ClockAnchor(SeedData.dateTimeOf(ScenarioDay.WEEKDAY), System.nanoTime())

    private val _state = MutableStateFlow(stateFor(ScenarioDay.WEEKDAY, SeedData.dateTimeOf(ScenarioDay.WEEKDAY)))
    override val state: StateFlow<SimState> = _state.asStateFlow()

    override val places: List<Place> get() = SeedData.places

    private val _ignition = MutableStateFlow(false)
    private val _gear = MutableStateFlow(GearState.PARK)
    private val _odometer = MutableStateFlow<Float?>(_state.value.odometerKm)
    private val _seats = MutableStateFlow(_state.value.seats)

    val naviEvents = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    val proximityEvents = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 16)

    /** 근접 알림을 걸어 둔 장소 이름 → 반경(m) */
    val watched = ConcurrentHashMap<String, Int>()

    /** P 를 벗어난 순간의 주행거리(이번 이동의 시작점). lock 안에서만 바꾼다. */
    private var driveStartOdo: Float? = null

    @Volatile
    private var arrival: Job? = null

    /** 오늘 어딘가에 도착한 적이 있는지(음성 입력 기본값을 고르는 데 쓴다) */
    @Volatile
    private var arrivedToday = false

    @Volatile
    private var lastLoggedOdo = _state.value.odometerKm

    private val queuedUtterance = AtomicReference<String?>(null)

    val signals: VehicleSignals = object : VehicleSignals {
        override val ignitionOn: StateFlow<Boolean> = _ignition.asStateFlow()
        override val gear: StateFlow<GearState> = _gear.asStateFlow()
        override val odometerKm: StateFlow<Float?> = _odometer.asStateFlow()
        override val seats: StateFlow<SeatOccupancy> = _seats.asStateFlow()

        override suspend fun vin(): String? {
            val vin = _state.value.vin.ifBlank { null }
            SdkLog.api("Vehicle.carInfo.getVINInfo() → ${vin ?: "읽지 못함"}")
            return vin
        }

        override fun start() {
            val s = _state.value
            SdkLog.api("Vehicle.initialize() · registerGearSelection · registerDistanceDriven · registerSeatOccupancy")
            SdkLog.api("Vehicle.odometer.getCurrentDistanceDriven() → ${km(s.odometerKm)}km")
        }

        override fun stop() {
            SdkLog.api("Vehicle.release()")
        }
    }

    // ───────── 시계 ─────────

    /** 시뮬레이션 날짜·시각. 정한 순간부터 실제 시간만큼 흐른다. */
    fun clockNow(): LocalDateTime {
        val a = anchor
        return a.base.plusNanos(System.nanoTime() - a.atNanos)
    }

    /** 시계 표시를 갱신하고, D 에 있는 동안 주행거리를 조금씩 올린다. SimPlatform 이 한 번 부른다. */
    fun startClock() {
        scope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(TICK_MS)
                val t = System.nanoTime()
                // 앱이 멈춰 있다 돌아와도 주행거리가 한꺼번에 튀지 않게 한다
                val dtSec = ((t - last) / 1e9f).coerceIn(0f, MAX_TICK_SEC)
                last = t
                val arriving = arrival?.isActive == true
                mutate { s ->
                    val creep = if (s.ignitionOn && s.gear == GearState.DRIVE && !arriving) dtSec * CREEP_KM_PER_SEC else 0f
                    s.copy(now = clockNow().truncatedTo(ChronoUnit.SECONDS), odometerKm = s.odometerKm + creep)
                }
            }
        }
    }

    // ───────── 가상 콘솔 ─────────

    override fun setDay(day: ScenarioDay) {
        cancelArrival()
        val start = SeedData.dateTimeOf(day)
        val before = _state.value
        val next = stateFor(day, start)
        arrivedToday = false
        queuedUtterance.set(null)
        SdkLog.sys(
            "가상 콘솔 · ${day.label} · ${start.format(DAY_FORMAT)} · " +
                "${next.carModel} ${km(next.odometerKm)}km · ${next.seats.count}명 탑승",
        )
        synchronized(lock) {
            anchor = ClockAnchor(start, System.nanoTime())
            mutate { next }
        }
        lastLoggedOdo = next.odometerKm
        if (before.guidanceTo != null) naviEvents.tryEmit(NaviEvent.GuidanceStopped)
    }

    override fun setClock(hour: Int, minute: Int) {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        SdkLog.sys("가상 콘솔 · 시각 = ${String.format(Locale.KOREA, "%02d:%02d", h, m)}")
        synchronized(lock) {
            val target = clockNow().toLocalDate().atTime(h, m)
            anchor = ClockAnchor(target, System.nanoTime())
            mutate { it.copy(now = target) }
        }
    }

    override fun ignition(on: Boolean) {
        applyIgnition(on)
    }

    override fun gear(gear: GearState) {
        val before = _state.value
        if (before.gear == gear) return
        if (!before.ignitionOn && gear != GearState.PARK) {
            SdkLog.sys("가상 콘솔 · 시동이 꺼져 있어 기어를 바꾸지 않음")
            return
        }
        cancelArrival()
        applyGear(gear)
    }

    override fun arrive(place: Place) {
        cancelArrival()
        applyIgnition(true)
        applyGear(GearState.DRIVE)
        arrival = scope.launch {
            delay(ARRIVE_LEAD_MS)
            if (watched.containsKey(place.name)) {
                SdkLog.event("FusedLocation · ProximityAlertListener.onTransition(${place.name}, ENTER)")
                proximityEvents.emit(ProximityEvent(place.name, entered = true))
                delay(PROXIMITY_LEAD_MS)
            }
            val target = distanceKmTo(place)
            var tripKm = target
            mutate { s ->
                // 이미 달린 만큼은 빼서, 이번 이동의 합이 그 장소까지의 거리가 되게 한다
                val driven = (s.odometerKm - (driveStartOdo ?: s.odometerKm)).coerceAtLeast(0f)
                val lastLeg = max(target - driven, MIN_LAST_LEG_KM)
                tripKm = driven + lastLeg
                s.copy(odometerKm = s.odometerKm + lastLeg, guidanceTo = null)
            }
            arrivedToday = true
            val distance = (tripKm * 10f).roundToInt() / 10f
            val minutes = (distance * MINUTES_PER_KM + BASE_TRIP_MINUTES).roundToInt()
            logOdometer()
            SdkLog.event("NaviHelper · onDestinationArrived(${place.name}) · ${km(distance)}km · ${minutes}분")
            naviEvents.emit(NaviEvent.Arrived(place.name, place.lat, place.lon, distance, minutes))
            delay(ARRIVE_TO_PARK_MS)
            applyGear(GearState.PARK)
        }
    }

    override fun setSeat(index: Int, occupied: Boolean) {
        val seatName = SEAT_NAMES.getOrNull(index)
        if (seatName == null) {
            SdkLog.sys("가상 콘솔 · 없는 좌석 번호 $index")
            return
        }
        SdkLog.event("CarPropertyManager · SEAT_OCCUPANCY[$seatName] = ${if (occupied) "착석" else "빈자리"}")
        mutate { s ->
            val seats = when (index) {
                0 -> s.seats.copy(driver = occupied)
                1 -> s.seats.copy(frontPassenger = occupied)
                2 -> s.seats.copy(rearLeft = occupied)
                else -> s.seats.copy(rearRight = occupied)
            }
            s.copy(seats = seats)
        }
    }

    override fun sayNext(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        queuedUtterance.set(said)
        SdkLog.sys("가상 콘솔 · 다음 음성 입력 = \"$said\"")
    }

    // ───────── 다른 시뮬레이터 부품이 쓰는 것 ─────────

    /** 콘솔이 넣어 둔 말을 꺼낸다(한 번 쓰면 비워진다). */
    fun takeQueuedUtterance(): String? = queuedUtterance.getAndSet(null)

    /**
     * 넣어 둔 말이 없을 때 쓰는 시나리오 기본값.
     * 평일의 말은 아침 브리핑의 메모이고, 주말의 말은 다녀온 뒤 남기는 한마디라서 도착 전후로 나눈다.
     * (나누지 않으면 토요일 아침 브리핑이 "올해 첫 감성돔"을 메모로 받아 버린다.)
     */
    fun defaultUtterance(): String? = when (_state.value.day) {
        ScenarioDay.WEEKDAY -> if (!arrivedToday) "내일 세차 예약" else null
        ScenarioDay.FISHING_SATURDAY -> if (arrivedToday) "올해 첫 감성돔" else null
        ScenarioDay.FAMILY_SUNDAY -> if (arrivedToday) "아빠, 내년에 또 오자!" else null
        else -> null
    }

    suspend fun startGuidance(place: Place) {
        mutate { it.copy(guidanceTo = place) }
        SdkLog.event("NaviHelper · onRouteStarted(${place.name})")
        naviEvents.emit(NaviEvent.GuidanceStarted(place.name))
    }

    // ───────── 내부 ─────────

    /** 상태를 바꾸고 신호 흐름에 반영한다. 콘솔(메인 스레드)과 주행 타이머가 함께 부르므로 잠근다. */
    private fun mutate(change: (SimState) -> SimState): SimState = synchronized(lock) {
        val old = _state.value
        val new = change(old)
        if (new == old) return@synchronized old
        if (old.gear == GearState.PARK && new.gear != GearState.PARK) driveStartOdo = old.odometerKm
        if (new.gear == GearState.PARK) driveStartOdo = null
        _state.value = new
        // 시동이 켜질 때는 다른 값이 먼저 자리 잡은 뒤에, 꺼질 때는 가장 먼저 알린다
        if (!new.ignitionOn) _ignition.value = false
        _seats.value = new.seats
        _odometer.value = new.odometerKm
        _gear.value = new.gear
        if (new.ignitionOn) _ignition.value = true
        new
    }

    private fun applyIgnition(on: Boolean) {
        val before = _state.value
        if (before.ignitionOn == on) return
        if (on) {
            cabin.resetToUsual()
            SdkLog.event("CarPropertyManager · IGNITION_STATE = ON")
            SdkLog.event("CarPropertyManager · GEAR_SELECTION = P")
            mutate { it.copy(ignitionOn = true, gear = GearState.PARK) }
        } else {
            cancelArrival()
            if (before.gear != GearState.PARK) SdkLog.event("CarPropertyManager · GEAR_SELECTION = P")
            logOdometer()
            SdkLog.event("CarPropertyManager · IGNITION_STATE = OFF")
            mutate { it.copy(ignitionOn = false, gear = GearState.PARK, guidanceTo = null) }
            if (before.guidanceTo != null) {
                SdkLog.event("NaviHelper · onRouteCancelled()")
                naviEvents.tryEmit(NaviEvent.GuidanceStopped)
            }
        }
    }

    private fun applyGear(gear: GearState) {
        if (_state.value.gear == gear) return
        if (gear == GearState.PARK) logOdometer()
        SdkLog.event("CarPropertyManager · GEAR_SELECTION = ${gear.symbol()}")
        mutate { it.copy(gear = gear) }
    }

    private fun cancelArrival() {
        arrival?.cancel()
        arrival = null
    }

    /** 주행거리는 달리는 내내 바뀌므로 멈출 때 한 번만 남긴다. */
    private fun logOdometer() {
        val odo = _state.value.odometerKm
        if (odo == lastLoggedOdo) return
        lastLoggedOdo = odo
        SdkLog.event("CarPropertyManager · PERF_ODOMETER = ${km(odo)}km")
    }

    private companion object {
        const val TICK_MS = 500L
        const val MAX_TICK_SEC = 2f
        const val CREEP_KM_PER_SEC = 1f

        /** D 로 바꾼 뒤 도착까지: 앱이 '주행 중'을 먼저 알아차릴 틈 */
        const val ARRIVE_LEAD_MS = 300L
        const val PROXIMITY_LEAD_MS = 400L
        const val ARRIVE_TO_PARK_MS = 700L

        const val DEFAULT_DISTANCE_KM = 10f
        const val MIN_LAST_LEG_KM = 1f
        const val MINUTES_PER_KM = 1.5f
        const val BASE_TRIP_MINUTES = 4f

        const val CAR_MODEL_FIRST = "그랜저"
        const val CAR_MODEL_NEW = "새 현대차"

        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val SEAT_NAMES = listOf("운전석", "조수석", "뒷좌석 왼쪽", "뒷좌석 오른쪽")

        fun distanceKmTo(place: Place): Float = when (place.name) {
            "회사" -> 18f
            "낚시터" -> 32f
            "수목원" -> 41f
            "마트" -> 6f
            else -> DEFAULT_DISTANCE_KM
        }

        /** 날짜를 고른 직후의 차 상태: 시동 꺼짐, P, 그날에 맞는 좌석·주행거리·차 */
        fun stateFor(day: ScenarioDay, now: LocalDateTime): SimState {
            val newCar = day == ScenarioDay.TWENTY_YEARS_LATER
            val family = day == ScenarioDay.FAMILY_SUNDAY || day == ScenarioDay.ONE_YEAR_LATER
            val odometer = when (day) {
                ScenarioDay.WEEKDAY -> 12_800f
                ScenarioDay.FISHING_SATURDAY -> 12_840f
                ScenarioDay.FAMILY_SUNDAY -> 12_910f
                ScenarioDay.ONE_YEAR_LATER -> 26_410f
                ScenarioDay.BIRTHDAY -> 26_530f
                ScenarioDay.TWENTY_YEARS_LATER -> 0f
            }
            return SimState(
                day = day,
                now = now,
                ignitionOn = false,
                gear = GearState.PARK,
                odometerKm = odometer,
                seats = if (family) SeatOccupancy(true, true, true, true) else SeatOccupancy(),
                vin = if (newCar) SeedData.VIN_NEW_CAR else SeedData.VIN_FIRST_CAR,
                carModel = if (newCar) CAR_MODEL_NEW else CAR_MODEL_FIRST,
                guidanceTo = null,
            )
        }
    }
}

/** 12840.0 → "12,840" */
private fun km(value: Float): String = String.format(Locale.KOREA, "%,d", value.roundToInt())

private fun GearState.symbol(): String = when (this) {
    GearState.PARK -> "P"
    GearState.REVERSE -> "R"
    GearState.NEUTRAL -> "N"
    GearState.DRIVE -> "D"
    GearState.UNKNOWN -> "?"
}
