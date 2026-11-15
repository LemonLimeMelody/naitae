package com.naitae.connect

import com.naitae.connect.core.model.Place
import com.naitae.connect.vehicle.AppClock
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.NaviGateway
import com.naitae.connect.vehicle.PlatformKind
import com.naitae.connect.vehicle.ProximityEvent
import com.naitae.connect.vehicle.ProximityGateway
import com.naitae.connect.vehicle.SeatOccupancy
import com.naitae.connect.vehicle.VehiclePlatform
import com.naitae.connect.vehicle.VehicleSignals
import com.naitae.connect.vehicle.VoiceInput
import com.naitae.connect.vehicle.VoiceOutput
import com.naitae.connect.vehicle.VoiceResult
import com.naitae.connect.vehicle.pleos.BoardingWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

/*
 * Pleos 에뮬레이터를 흉내 낸 가짜 차량 연결(차량 계약의 가짜 구현). Pleos SDK 는 쓰지 않는다.
 * Pleos Virtual Console 이 하는 일(기어·좌석·주행거리)은 FakeVehicle 의 함수로 넣는다.
 */

/** 차의 물리 상태. 앱 프로세스가 다시 떠도(가짜 연결을 새로 만들어도) 그대로다. */
class FakeCar(
    var vin: String? = "KMHEMULATOR000042",
    var gear: GearState = GearState.PARK,
    var driverSeated: Boolean = false,
    var passengers: Int = 0,
    var odometerKm: Float = 12_800f,
    /** 운전석 착좌 센서가 있는 차인지 */
    val driverSeatSensor: Boolean = true,
)

/** 한 문장을 읽는 데 가상 시간이 걸리는 음성 출력(화면이 문장마다 바뀌는 것을 볼 수 있게). */
class TimedVoice(private val log: MutableList<String>) : VoiceOutput {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    override suspend fun speak(text: String): Result<Unit> {
        log.add("speak:$text")
        _speaking.value = true
        try {
            delay(SPEAK_MS)
        } finally {
            _speaking.value = false
        }
        return Result.success(Unit)
    }

    override fun stop() = Unit

    companion object {
        const val SPEAK_MS = 200L
    }
}

/** 실제 STT 자리. 넣어 둔 답을 차례로 돌려주고, 없으면 말이 없는 것으로 timeout 까지(또는 cancel 까지) 기다린다. */
class FakeVoiceIn(private val log: MutableList<String>) : VoiceInput {
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    val answers = ArrayDeque<String>()
    var listens = 0
        private set
    var cancels = 0
        private set

    @Volatile
    private var cancelled: CompletableDeferred<Unit>? = null

    override suspend fun listen(timeoutMs: Long): VoiceResult {
        listens++
        log.add("listen")
        val answer = answers.removeFirstOrNull()
        if (answer != null) {
            _partial.value = answer
            return VoiceResult.Text(answer)
        }
        val stop = CompletableDeferred<Unit>()
        cancelled = stop
        withTimeoutOrNull(timeoutMs) { stop.await() }
        return VoiceResult.NoSpeech
    }

    override fun cancel() {
        cancels++
        cancelled?.complete(Unit)
    }
}

class FakeNavi(private val log: MutableList<String>) : NaviGateway {
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    /** 실제 내비가 보낸 이벤트 */
    fun emit(event: NaviEvent) {
        _events.tryEmit(event)
    }

    override suspend fun requestRoute(place: Place): Result<Unit> {
        log.add("route:${place.name}")
        _events.emit(NaviEvent.GuidanceStarted(place.name))
        return Result.success(Unit)
    }

    override suspend fun bookmarks(): List<Place> = emptyList()

    override fun start() {
        log.add("navi-start")
    }

    override fun stop() {
        log.add("navi-stop")
    }
}

/** supported 가 false 면 에뮬레이터처럼 위치 서비스가 없는 것 */
class FakeProximity(private val log: MutableList<String>, var supported: Boolean = false) : ProximityGateway {
    private val _events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ProximityEvent> = _events.asSharedFlow()

    fun emit(event: ProximityEvent) {
        _events.tryEmit(event)
    }

    override suspend fun isSupported(): Boolean = supported

    override suspend fun watch(place: Place, radiusM: Int): Result<Unit> {
        log.add("watch:${place.name}:$radiusM")
        return Result.success(Unit)
    }

    override suspend fun unwatch(placeName: String) {
        log.add("unwatch:$placeName")
    }
}

/**
 * 가짜 Pleos 플랫폼(kind = PLEOS, 콘솔 없음). 탑승 신호는 실제 어댑터와 같은 로직(BoardingWatch)으로 낸다:
 * 운전석에 앉고 1.5초 뒤 ON, 기어 P 에서 비우고 1.5초 뒤 OFF.
 */
internal class FakeVehicle(
    scope: CoroutineScope,
    nowMs: () -> Long,
    val car: FakeCar = FakeCar(),
) {
    val log = ArrayList<String>()

    private val ignition = MutableStateFlow(false)
    private val gear = MutableStateFlow(GearState.UNKNOWN)
    private val odometer = MutableStateFlow<Float?>(null)
    private val seats = MutableStateFlow(SeatOccupancy())
    private var started = false

    private val boarding = BoardingWatch(scope, nowMs) { on -> if (started) ignition.value = on }

    val cabin = FakeCabin(log = log)
    val navi = FakeNavi(log)
    val voiceOut = TimedVoice(log)
    val voiceIn = FakeVoiceIn(log)
    val proximity = FakeProximity(log)

    val signals: VehicleSignals = object : VehicleSignals {
        override val ignitionOn: StateFlow<Boolean> = ignition.asStateFlow()
        override val gear: StateFlow<GearState> = this@FakeVehicle.gear.asStateFlow()
        override val odometerKm: StateFlow<Float?> = odometer.asStateFlow()
        override val seats: StateFlow<SeatOccupancy> = this@FakeVehicle.seats.asStateFlow()

        override suspend fun vin(): String? = car.vin

        /** 서비스에 연결되어 첫 값을 읽은 것까지 한 번에 흉내 낸다. */
        override fun start() {
            if (started) return
            started = true
            this@FakeVehicle.gear.value = car.gear
            odometer.value = car.odometerKm
            this@FakeVehicle.seats.value = seatsOf(1 + car.passengers)
            boarding.gear(car.gear)
            if (car.driverSeatSensor) boarding.driverSeat(car.driverSeated)
            ignition.value = boarding.alive()
        }

        override fun stop() {
            if (!started) return
            started = false
            boarding.stopped()
            ignition.value = false
        }
    }

    val platform: VehiclePlatform = VehiclePlatform(
        kind = PlatformKind.PLEOS,
        signals = signals,
        cabin = cabin,
        navi = navi,
        voiceOut = voiceOut,
        voiceIn = voiceIn,
        proximity = proximity,
        // 실제 Pleos 플랫폼의 시계는 기기 시각이다. 시연 층이 이것을 쓰지 않는지 보려고 엉뚱한 날짜를 둔다.
        clock = AppClock { DEVICE_TIME },
        console = null,
    )

    // ───────── Pleos Virtual Console 의 카드 ─────────

    /** Seat Occupancy · 운전석 */
    fun sit(occupied: Boolean) {
        car.driverSeated = occupied
        if (started && car.driverSeatSensor) boarding.driverSeat(occupied)
    }

    /** Seat Occupancy · 동승석과 뒷좌석 */
    fun passengers(count: Int) {
        car.passengers = count
        if (started) seats.value = seatsOf(1 + count)
    }

    /** Gear Selection */
    fun shift(next: GearState) {
        car.gear = next
        if (!started) return
        gear.value = next
        boarding.gear(next)
    }

    /** Odometer */
    fun odometer(km: Float) {
        car.odometerKm = km
        if (started) odometer.value = km
    }

    val spoken: List<String> get() = log.filter { it.startsWith("speak:") }.map { it.removePrefix("speak:") }

    companion object {
        val DEVICE_TIME: LocalDateTime = LocalDateTime.of(2030, 1, 1, 0, 0)
    }
}
