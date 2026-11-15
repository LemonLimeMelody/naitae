package com.naitae.connect.vehicle.pleos

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.os.SystemClock
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.SeatOccupancy
import com.naitae.connect.vehicle.VehicleSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 차량 신호(읽기): 기어 · 누적 주행거리 · 좌석 착석 · VIN.
 *
 * 시동(ignitionOn) = 운전석 탑승:
 *  Vehicle SDK 공개 API 에는 시동 상태를 읽는 함수가 없다. 그래서 '차량 서비스에 연결된 채 운전석에 앉아 있음'을 탑승(ON)으로 본다.
 *  운전석에 앉으면 ON, 기어 P 에서 운전석이 비면 OFF 이고, 센서 흔들림은 1.5초 동안 가라앉기를 기다린다(DriverSeatIgnition).
 *  운전석 착좌를 알 수 없는 차(센서 없음·읽기 실패)에서는 예전처럼 차량 값이 처음 도착한 때부터 ON 으로 보고,
 *  stop() 이 불리면 OFF 로 본다(docs/PLEOS_ADAPTER_REVIEW.md 3절).
 */
internal class PleosSignals(
    private val session: PleosVehicleSession,
    private val scope: CoroutineScope,
) : VehicleSignals {

    private val _ignitionOn = MutableStateFlow(false)
    override val ignitionOn: StateFlow<Boolean> = _ignitionOn.asStateFlow()

    private val _gear = MutableStateFlow(GearState.UNKNOWN)
    override val gear: StateFlow<GearState> = _gear.asStateFlow()

    private val _odometerKm = MutableStateFlow<Float?>(null)
    override val odometerKm: StateFlow<Float?> = _odometerKm.asStateFlow()

    private val _seats = MutableStateFlow(SeatOccupancy())
    override val seats: StateFlow<SeatOccupancy> = _seats.asStateFlow()

    @Volatile
    private var started = false
    private var job: Job? = null

    @Volatile
    private var cachedVin: String? = null

    /** 운전석 착좌와 기어로 탑승을 판단한다. 바뀌면 가라앉은 뒤에 알려 준다. */
    private val boarding = BoardingWatch(scope, { SystemClock.elapsedRealtime() }) { on -> publishBoarding(on) }

    /** 등록해 둔 좌석 리스너(해제할 때 같은 영역·같은 인스턴스가 필요하다) */
    private val seatListeners = mutableListOf<Pair<SeatArea, SeatOccupancyListener>>()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            if (!started) return
            SdkLog.event("Gear.onSelected → ${gear?.name ?: "값 없음"}")
            applyGear(gear)
        }

        override fun onFailed(e: Exception) {
            SdkLog.sys("Gear 리스너 오류 · ${e.brief()}")
        }
    }

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            if (!started || value == null) return
            // 주행 중에는 값이 자주 오므로 km 자리가 바뀔 때만 기록한다.
            if (_odometerKm.value?.toLong() != value.toLong()) {
                SdkLog.event("Odometer.onDistanceDrivenUpdated → ${formatKm(value)}")
            }
            _odometerKm.value = value
        }

        override fun onFailed(e: Exception) {
            SdkLog.sys("Odometer 리스너 오류 · ${e.brief()}")
        }
    }

    @Synchronized
    override fun start() {
        if (started) return
        started = true
        SdkLog.sys("차량 신호 수신 시작")
        session.connect()
        job = scope.launch {
            session.connected.first { it }
            // 변화를 놓치지 않게 리스너를 먼저 걸고, 지금 기어를 새로 읽는다.
            // (연결을 확인하며 읽어 둔 값은 stop() 뒤 다시 start() 하면 옛 값이므로 그대로 쓰지 않는다.)
            subscribeGear()
            readGear()
            // 시동 ON 을 알리기 전에 주행거리·좌석·VIN 의 첫 값을 채운다(값이 늦으면 기다리지 않고 넘어간다).
            val firstReads = listOf(
                launch { readAndSubscribeOdometer() },
                launch { readAndSubscribeSeats() },
                launch { vin() },
            )
            withTimeoutOrNull(FIRST_READ_WAIT_MS) { firstReads.joinAll() }
            markAlive()
        }
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        job?.cancel()
        job = null
        val vehicle = session.vehicle
        @Suppress("DEPRECATION")
        safeSdk("Gear.unregisterGearSelection()") { vehicle.getGear().unregisterGearSelection(gearListener) }
        safeSdk("Odometer.unregisterDistanceDriven()") { vehicle.getOdometer().unregisterDistanceDriven(odometerListener) }
        val seatsToRelease = synchronized(seatListeners) {
            val copy = seatListeners.toList()
            seatListeners.clear()
            copy
        }
        seatsToRelease.forEach { (area, listener) ->
            safeSdk("Seat.unregisterSeatOccupancy()") { vehicle.getSeat().unregisterSeatOccupancy(area, listener) }
        }
        boarding.stopped()
        _ignitionOn.value = false
        SdkLog.sys("차량 신호 수신 중지 → 시동 OFF 로 봄")
    }

    override suspend fun vin(): String? {
        cachedVin?.let { return it }
        if (!session.awaitConnected()) return null
        val result = awaitSdk<String?>("CarInfo.getVINInfo()") { ok, fail ->
            session.vehicle.getCarInfo().getVINInfo(ok, fail)
        }
        val vin = result.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (vin != null) {
            cachedVin = vin
            SdkLog.api("CarInfo.getVINInfo() → ${maskVin(vin)}")
        } else {
            SdkLog.api("CarInfo.getVINInfo() → 못 읽음 · ${result.exceptionOrNull()?.brief() ?: "값 없음"}")
        }
        return vin
    }

    // ───────── 기어 ─────────

    private fun subscribeGear() {
        if (!started) return
        @Suppress("DEPRECATION")
        safeSdk("Gear.registerGearSelection()") {
            session.vehicle.getGear().registerGearSelection(gearListener)
            SdkLog.api("Gear.registerGearSelection(listener)")
        }
    }

    private suspend fun readGear() {
        val result = awaitSdk<VehicleGear?>("Gear.getCurrentGearSelectionState()") { ok, fail ->
            @Suppress("DEPRECATION")
            session.vehicle.getGear().getCurrentGearSelectionState(ok, fail)
        }
        val read = result.getOrNull()
        SdkLog.api(
            "Gear.getCurrentGearSelectionState() → " +
                (if (result.isSuccess) (read?.name ?: "값 없음") else "실패 · ${result.exceptionOrNull()?.brief()}"),
        )
        if (!started) return
        if (result.isSuccess) {
            applyGear(read)
        } else if (_gear.value == GearState.UNKNOWN) {
            // 읽지 못했고 리스너로 받은 값도 아직 없으면, 방금 연결을 확인하며 읽어 둔 값을 쓴다.
            applyGear(session.firstGear)
        }
    }

    private fun applyGear(gear: VehicleGear?) {
        _gear.value = when (gear) {
            VehicleGear.GEAR_PARK -> GearState.PARK
            VehicleGear.GEAR_REVERSE -> GearState.REVERSE
            VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
            VehicleGear.GEAR_UNKNOWN, null -> GearState.UNKNOWN
            // D 와 수동 단수(1~9단)는 모두 '주행'으로 본다.
            else -> GearState.DRIVE
        }
        boarding.gear(_gear.value)
    }

    // ───────── 주행거리 ─────────

    private suspend fun readAndSubscribeOdometer() {
        val odometer = session.vehicle.getOdometer()
        // 값을 읽는 사이의 변화를 놓치지 않게 리스너를 먼저 건다.
        if (!started) return
        safeSdk("Odometer.registerDistanceDriven()") {
            odometer.registerDistanceDriven(odometerListener)
            SdkLog.api("Odometer.registerDistanceDriven(listener)")
        }
        val result = awaitSdk<Float?>("Odometer.getCurrentDistanceDriven()") { ok, fail ->
            odometer.getCurrentDistanceDriven(ok, fail)
        }
        val km = result.getOrNull()
        if (km != null) _odometerKm.value = km
        SdkLog.api(
            "Odometer.getCurrentDistanceDriven() → " +
                (if (result.isSuccess) formatKm(km) else "실패 · ${result.exceptionOrNull()?.brief()}"),
        )
    }

    // ───────── 좌석 ─────────

    /** 좌석 네 자리. 누가 앉았는지는 읽지도 저장하지도 않는다(착석 여부만). */
    private enum class SeatSlot(val value: SeatValue, val label: String) {
        DRIVER(SeatValue.ROW_1_LEFT, "운전석"),
        FRONT_PASSENGER(SeatValue.ROW_1_RIGHT, "동승석"),
        REAR_LEFT(SeatValue.ROW_2_LEFT, "뒷좌석 왼쪽"),
        REAR_RIGHT(SeatValue.ROW_2_RIGHT, "뒷좌석 오른쪽"),
    }

    private suspend fun readAndSubscribeSeats() {
        val seat = session.vehicle.getSeat()
        val capability = awaitSdk<List<SeatArea>?>("Seat.checkSeatOccupancyCapabilities()") { ok, fail ->
            seat.checkSeatOccupancyCapabilities({ _, areas -> ok(areas) }, fail)
        }
        val supported = capability.getOrNull()
        SdkLog.api(
            "Seat.checkSeatOccupancyCapabilities() → " +
                (if (supported != null) "좌석 영역 ${supported.size}곳" else "실패 · ${capability.exceptionOrNull()?.brief()}"),
        )
        for (slot in SeatSlot.values()) {
            if (!started) return
            // 지원 영역 목록을 받았으면 그 안에서 찾고, 못 받았으면 좌석 하나짜리 영역으로 시도한다.
            val area = if (supported != null) {
                supported.firstOrNull { slot.value in it.values } ?: continue
            } else {
                SeatArea(slot.value)
            }
            val listener = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                    if (!started) return
                    SdkLog.event("Seat.onSeatOccupancyUpdated(${slot.label}) → ${occupancyLabel(occupancy)}")
                    applySeat(slot, occupancy)
                }

                override fun onFailed(e: Exception) {
                    SdkLog.sys("Seat 리스너 오류(${slot.label}) · ${e.brief()}")
                }
            }
            val registered = safeSdk("Seat.registerSeatOccupancy()") {
                seat.registerSeatOccupancy(area, listener)
                SdkLog.api("Seat.registerSeatOccupancy(${slot.label})")
            }
            if (registered) synchronized(seatListeners) { seatListeners.add(area to listener) }

            val current = awaitSdk<Boolean?>("Seat.getCurrentSeatOccupancy()") { ok, fail ->
                seat.getCurrentSeatOccupancy(area, ok, fail)
            }
            SdkLog.api(
                "Seat.getCurrentSeatOccupancy(${slot.label}) → " +
                    (if (current.isSuccess) occupancyLabel(current.getOrNull()) else "실패 · ${current.exceptionOrNull()?.brief()}"),
            )
            if (current.isSuccess) applySeat(slot, current.getOrNull())
        }
    }

    private fun applySeat(slot: SeatSlot, occupied: Boolean?) {
        if (occupied == null) return
        if (slot == SeatSlot.DRIVER) boarding.driverSeat(occupied)
        _seats.update { seats ->
            when (slot) {
                // 좌석 수에서는 운전석을 센서 값과 상관없이 탑승으로 둔다: 운전석 값은 탑승 신호(ignitionOn)로만 쓰고,
                // 운전석 착석 센서가 없는 차에서도 '동승 여부(둘 이상)' 판단이 틀어지지 않게 하기 위해서다.
                SeatSlot.DRIVER -> seats
                SeatSlot.FRONT_PASSENGER -> seats.copy(frontPassenger = occupied)
                SeatSlot.REAR_LEFT -> seats.copy(rearLeft = occupied)
                SeatSlot.REAR_RIGHT -> seats.copy(rearRight = occupied)
            }
        }
    }

    private fun occupancyLabel(occupied: Boolean?): String = when (occupied) {
        true -> "착석"
        false -> "비어 있음"
        null -> "값 없음"
    }

    // ───────── 공통 ─────────

    /** SDK 가 연결되어 차량 값이 도착했다. 이때부터 운전석 착좌로 탑승을 판단한다(위 클래스 설명). */
    private fun markAlive() {
        if (!started) return
        val on = boarding.alive()
        SdkLog.sys(
            when {
                !boarding.seatKnown -> "차량 값 수신 · 운전석 착좌 신호 없음 → 시동 ON 으로 봄"
                on -> "차량 값 수신 · 운전석 착석 → 탑승(시동 ON)으로 봄"
                else -> "차량 값 수신 · 운전석 비어 있음 → 탑승을 기다림"
            },
        )
        _ignitionOn.value = on
    }

    /** 가라앉은 탑승 판단을 알린다. */
    private fun publishBoarding(on: Boolean) {
        if (!started || _ignitionOn.value == on) return
        SdkLog.sys(if (on) "운전석 착석 → 탑승(시동 ON)으로 봄" else "운전석 비움(기어 P) → 내림(시동 OFF)으로 봄")
        _ignitionOn.value = on
    }

    private companion object {
        const val FIRST_READ_WAIT_MS = 3_000L
    }

    /** 로그에는 차대번호 전체를 남기지 않는다. */
    private fun maskVin(vin: String): String =
        if (vin.length <= 7) vin else "${vin.take(3)}…${vin.takeLast(4)}"
}
