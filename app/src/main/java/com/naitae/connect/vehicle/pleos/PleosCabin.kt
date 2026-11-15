package com.naitae.connect.vehicle.pleos

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.vehicle.CabinCapabilities
import com.naitae.connect.vehicle.CabinControl
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * 실내 제어(쓰기): 앰비언트 라이트 색·밝기, 운전석 시트 열선, 핸들 열선, 공조 온도·풍량.
 *
 * 차마다 지원 영역과 값 범위가 달라서, 먼저 check…Capabilities 로 영역·범위를 받아 두고 그 안에서만 쓴다.
 * 기능 확인 자체가 실패하면(응답 없음 등) 운전석 영역으로 한 번 시도하고, 결과는 SDK 의 실패 콜백에 맡긴다.
 */
internal class PleosCabin(
    private val session: PleosVehicleSession,
    scope: CoroutineScope,
) : CabinControl {

    private val _state = MutableStateFlow(CabinState())
    override val state: StateFlow<CabinState> = _state.asStateFlow()

    private val ambientCap = Cached { loadAmbientCap() }
    private val seatHeatCap = Cached { loadSeatHeatCap() }
    private val steeringCap = Cached { loadSteeringCap() }
    private val climateCap = Cached { loadClimateCap() }

    /** 밝기를 단계적으로 올릴 때 같은 색을 매번 다시 보내지 않으려고 기억해 둔다. */
    @Volatile
    private var lastColorArgb: Int? = null

    @Volatile
    private var lastColorAtMs: Long = 0L

    init {
        scope.launch {
            session.connected.first { it }
            readCurrentState()
        }
    }

    override suspend fun capabilities(): CabinCapabilities {
        if (!session.awaitConnected()) return CabinCapabilities()
        return coroutineScope {
            val ambient = async { ambientCap.get() }
            val seat = async { seatHeatCap.get() }
            val steering = async { steeringCap.get() }
            val climate = async { climateCap.get() }
            val ambientOk = ambient.await()?.supported == true
            CabinCapabilities(
                ambientLight = ambientOk,
                // SDK 는 색을 32비트 ARGB 로 받으므로 앱이 쓰는 네 색을 모두 보낼 수 있다.
                ambientColors = if (ambientOk && ambient.await()?.colorAreas?.isNotEmpty() == true) {
                    AmbientColor.values().toList()
                } else {
                    emptyList()
                },
                seatHeat = seat.await()?.supported == true,
                steeringHeat = steering.await()?.supported == true,
                climate = climate.await()?.supported == true,
            )
        }
    }

    // ───────── 앰비언트 라이트 ─────────

    override suspend fun setAmbient(color: AmbientColor, brightness: Int): Result<Unit> {
        val level = brightness.coerceIn(0, 100)
        if (!session.awaitConnected()) return notConnected()
        val cap = ambientCap.get()
        if (cap != null && !cap.supported) return unsupported("앰비언트 라이트")
        val light = session.vehicle.getLight()
        val argb = color.argb.toInt()
        val colorAreas = cap?.colorAreas ?: listOf(driverArea())
        val brightnessAreas = cap?.brightness?.keys?.toList() ?: listOf(driverArea())
        var firstError: Throwable? = null

        if (level > 0) {
            cap?.switchAreas?.forEach { area ->
                val switched = awaitSdk<LightSwitch?>("Light.setAmbientLightSwitch()") { ok, fail ->
                    light.setAmbientLightSwitch(area, LightSwitch.ON, { _, value -> ok(value) }, fail)
                }
                logResult("Light.setAmbientLightSwitch(ON)", switched) { "켜짐" }
            }
        }

        val sameColorJustSent = lastColorArgb == argb && System.currentTimeMillis() - lastColorAtMs < COLOR_RESEND_MS
        var colorOk = sameColorJustSent
        if (!sameColorJustSent) {
            for (area in colorAreas) {
                val result = awaitSdk<Int?>("Light.setAmbientLightBrightnessColor()") { ok, fail ->
                    light.setAmbientLightBrightnessColor(area, argb, { _, value -> ok(value) }, fail)
                }
                logResult("Light.setAmbientLightBrightnessColor(${formatArgb(argb)} ${color.label})", result) { "적용" }
                if (result.isSuccess) colorOk = true else firstError = firstError ?: result.exceptionOrNull()
            }
            if (colorOk) {
                lastColorArgb = argb
                lastColorAtMs = System.currentTimeMillis()
            }
        }

        if (colorAreas.isEmpty()) firstError = UnsupportedOperationException("앰비언트 색: 이 차에서 지원하지 않음")

        var brightnessOk = false
        if (brightnessAreas.isEmpty()) firstError = firstError ?: UnsupportedOperationException("앰비언트 밝기: 이 차에서 지원하지 않음")
        for (area in brightnessAreas) {
            val value = scaleBrightness(level, cap?.brightness?.get(area))
            val result = awaitSdk<Int?>("Light.setAmbientLightBrightness()") { ok, fail ->
                light.setAmbientLightBrightness(area, value, { _, applied -> ok(applied) }, fail)
            }
            logResult("Light.setAmbientLightBrightness($level%)", result) { "적용" }
            if (result.isSuccess) brightnessOk = true else firstError = firstError ?: result.exceptionOrNull()
        }

        _state.update { current ->
            current.copy(
                ambientColor = if (colorOk) color else current.ambientColor,
                ambientBrightness = if (brightnessOk) level else current.ambientBrightness,
            )
        }
        val error = firstError
        return if (error == null) Result.success(Unit) else Result.failure(error)
    }

    // ───────── 시트 열선 ─────────

    override suspend fun setSeatHeat(level: Int): Result<Unit> {
        val want = level.coerceIn(0, MAX_HEAT_LEVEL)
        if (!session.awaitConnected()) return notConnected()
        val cap = seatHeatCap.get()
        if (cap != null && !cap.supported) return unsupported("운전석 시트 열선")
        val area = cap?.area ?: driverArea()
        // 양수 = 열선 단계(음수는 통풍). 차의 최대 단계가 3이 아니면 비율로 맞춘다.
        val value = if (cap == null) want else scaleLevel(want, cap.max)
        val result = awaitSdk<Int?>("Seat.setHvacSeatTemperature()") { ok, fail ->
            session.vehicle.getSeat().setHvacSeatTemperature(area, value, { _, applied -> ok(applied) }, fail)
        }
        logResult("Seat.setHvacSeatTemperature(운전석, ${value}단)", result) { "적용" }
        if (result.isSuccess) _state.update { it.copy(seatHeatLevel = want) }
        return result.map { }
    }

    // ───────── 핸들 열선 ─────────

    override suspend fun setSteeringHeat(on: Boolean): Result<Unit> {
        if (!session.awaitConnected()) return notConnected()
        val cap = steeringCap.get()
        if (cap != null && !cap.supported) return unsupported("핸들 열선")
        // 켤 때는 중간 단계로 켠다(3단이면 2단). 단계 정보를 못 받았으면 1단.
        val value = when {
            !on -> 0
            cap == null -> 1
            else -> ((cap.max + 1) / 2).coerceAtLeast(1)
        }
        val result = awaitSdk<Int?>("SteeringWheel.setHvacSteeringWheelHeat()") { ok, fail ->
            session.vehicle.getSteeringWheel().setHvacSteeringWheelHeat(value, ok, fail)
        }
        logResult("SteeringWheel.setHvacSteeringWheelHeat(${value}단)", result) { "적용" }
        if (result.isSuccess) _state.update { it.copy(steeringHeat = on) }
        return result.map { }
    }

    // ───────── 공조 ─────────

    override suspend fun setClimate(tempC: Float?, fanLevel: Int?): Result<Unit> {
        if (tempC == null && fanLevel == null) return Result.success(Unit)
        if (!session.awaitConnected()) return notConnected()
        val cap = climateCap.get()
        if (cap != null && !cap.supported) return unsupported("공조")
        val hvac = session.vehicle.getHVAC()
        var firstError: Throwable? = null

        // 공조 전원이 꺼져 있으면 온도·풍량 설정이 먹지 않으므로 먼저 켠다(전원 제어를 지원하는 차에서만).
        val powerArea = cap?.powerArea
        if (powerArea != null) {
            val power = awaitSdk<Boolean?>("HVAC.getHvacPowerState()") { ok, fail ->
                hvac.getHvacPowerState(powerArea, ok, fail)
            }
            logResult("HVAC.getHvacPowerState()", power) { if (it == true) "켜짐" else "꺼짐" }
            if (power.getOrNull() == false) {
                val turnedOn = awaitSdk<Boolean?>("HVAC.setHvacPower()") { ok, fail ->
                    hvac.setHvacPower(powerArea, true, { _, value -> ok(value) }, fail)
                }
                logResult("HVAC.setHvacPower(ON)", turnedOn) { "켜짐" }
            }
        }

        if (tempC != null && (cap == null || cap.tempArea != null)) {
            val area = cap?.tempArea ?: driverArea()
            val min = cap?.tempMin
            val max = cap?.tempMax
            // 0.5℃ 단위로 맞추고, 차가 알려 준 범위를 넘지 않게 한다.
            val rounded = (tempC * 2f).roundToInt() / 2f
            val value = if (min != null && max != null && min <= max) rounded.coerceIn(min, max) else rounded
            val result = awaitSdk<Float?>("HVAC.setHvacTemperature()") { ok, fail ->
                hvac.setHvacTemperature(area, value, TemperatureUnit.CELSIUS, { _, applied -> ok(applied) }, fail)
            }
            logResult("HVAC.setHvacTemperature($value℃)", result) { "적용" }
            if (result.isSuccess) _state.update { it.copy(cabinTempC = value) } else firstError = result.exceptionOrNull()
        }

        if (fanLevel != null && (cap == null || cap.fanArea != null)) {
            val area = cap?.fanArea ?: driverArea()
            val min = cap?.fanMin
            val max = cap?.fanMax
            val value = if (min != null && max != null && min <= max) fanLevel.coerceIn(min, max) else fanLevel.coerceAtLeast(0)
            val result = awaitSdk<Int?>("HVAC.setHvacFanSpeed()") { ok, fail ->
                hvac.setHvacFanSpeed(area, value, { _, applied -> ok(applied) }, fail)
            }
            logResult("HVAC.setHvacFanSpeed(${value}단)", result) { "적용" }
            if (result.isSuccess) _state.update { it.copy(fanLevel = value) } else firstError = firstError ?: result.exceptionOrNull()
        }

        val error = firstError
        return if (error == null) Result.success(Unit) else Result.failure(error)
    }

    // ───────── 지원 기능 확인(check…Capabilities) ─────────

    private class AmbientCap(
        val colorAreas: List<SeatArea>,
        val brightness: Map<SeatArea, AreaBounds<Int>>,
        val switchAreas: List<SeatArea>,
    ) {
        val supported: Boolean get() = colorAreas.isNotEmpty() || brightness.isNotEmpty()
    }

    private class LevelCap(val area: SeatArea?, val max: Int, val supported: Boolean)

    private class ClimateCap(
        val tempArea: SeatArea?,
        val tempMin: Float?,
        val tempMax: Float?,
        val fanArea: SeatArea?,
        val fanMin: Int?,
        val fanMax: Int?,
        val powerArea: SeatArea?,
    ) {
        val supported: Boolean get() = tempArea != null || fanArea != null
    }

    private suspend fun loadAmbientCap(): AmbientCap? = coroutineScope {
        val light = session.vehicle.getLight()
        val color = async {
            awaitSdk<Pair<CapabilityConfig?, List<SeatArea>?>>("Light.checkAmbientLightBrightnessColorCapabilities()") { ok, fail ->
                light.checkAmbientLightBrightnessColorCapabilities({ config, areas -> ok(config to areas) }, fail)
            }
        }
        val brightness = async {
            awaitSdk<Pair<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?>>("Light.checkAmbientLightBrightnessCapabilities()") { ok, fail ->
                light.checkAmbientLightBrightnessCapabilities({ config, bounds -> ok(config to bounds) }, fail)
            }
        }
        val switch = async {
            awaitSdk<Pair<CapabilityConfig?, Map<SeatArea, List<LightSwitch>>?>>("Light.checkAmbientLightSwitchCapabilities()") { ok, fail ->
                light.checkAmbientLightSwitchCapabilities({ config, switches -> ok(config to switches) }, fail)
            }
        }
        val colorResult = color.await()
        val brightnessResult = brightness.await()
        val switchResult = switch.await()
        logResult("Light.checkAmbientLightBrightnessColorCapabilities()", colorResult) { describe(it.first, it.second?.size) }
        logResult("Light.checkAmbientLightBrightnessCapabilities()", brightnessResult) { describe(it.first, it.second?.size) }
        // 색·밝기 확인이 둘 다 답이 없으면 '모름'으로 두고 다음에 다시 묻는다.
        if (colorResult.isFailure && brightnessResult.isFailure) return@coroutineScope null

        val colorPair = colorResult.getOrNull()
        val brightnessPair = brightnessResult.getOrNull()
        val switchPair = switchResult.getOrNull()
        AmbientCap(
            colorAreas = if (colorPair?.first.writable()) colorPair?.second.orEmpty() else emptyList(),
            brightness = if (brightnessPair?.first.writable()) brightnessPair?.second.orEmpty() else emptyMap(),
            switchAreas = if (switchPair?.first.writable()) {
                switchPair?.second.orEmpty().filterValues { LightSwitch.ON in it }.keys.toList()
            } else {
                emptyList()
            },
        )
    }

    private suspend fun loadSeatHeatCap(): LevelCap? {
        val result = awaitSdk<Pair<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?>>("Seat.checkSeatTemperatureCapabilities()") { ok, fail ->
            session.vehicle.getSeat().checkSeatTemperatureCapabilities({ config, bounds -> ok(config to bounds) }, fail)
        }
        logResult("Seat.checkSeatTemperatureCapabilities()", result) { describe(it.first, it.second?.size) }
        val pair = result.getOrNull() ?: return null
        val entry = pair.second.orEmpty().entries.firstOrNull { SeatValue.ROW_1_LEFT in it.key.values }
        val max = entry?.value?.maxValue ?: 0
        return LevelCap(area = entry?.key, max = max, supported = pair.first.writable() && entry != null && max > 0)
    }

    private suspend fun loadSteeringCap(): LevelCap? {
        val result = awaitSdk<Pair<CapabilityConfig?, AreaBounds<Int>?>>("SteeringWheel.checkHvacSteeringWheelHeatCapabilities()") { ok, fail ->
            session.vehicle.getSteeringWheel().checkHvacSteeringWheelHeatCapabilities({ config, bounds -> ok(config to bounds) }, fail)
        }
        logResult("SteeringWheel.checkHvacSteeringWheelHeatCapabilities()", result) {
            describe(it.first, null) + (it.second?.let { b -> " · ${b.minValue}~${b.maxValue}단" } ?: "")
        }
        val pair = result.getOrNull() ?: return null
        val max = pair.second?.maxValue ?: 0
        return LevelCap(area = null, max = max, supported = pair.first.writable() && max > 0)
    }

    private suspend fun loadClimateCap(): ClimateCap? = coroutineScope {
        val hvac = session.vehicle.getHVAC()
        val temp = async {
            awaitSdk<Pair<CapabilityConfig?, Map<SeatArea, AreaBounds<Float>>?>>("HVAC.checkHvacTemperatureCapabilities()") { ok, fail ->
                hvac.checkHvacTemperatureCapabilities({ config, bounds, _ -> ok(config to bounds) }, fail)
            }
        }
        val fan = async {
            awaitSdk<Pair<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?>>("HVAC.checkHvacFanSpeedCapabilities()") { ok, fail ->
                hvac.checkHvacFanSpeedCapabilities({ config, bounds -> ok(config to bounds) }, fail)
            }
        }
        val power = async {
            awaitSdk<Pair<CapabilityConfig?, List<SeatArea>?>>("HVAC.checkHvacPowerCapabilities()") { ok, fail ->
                hvac.checkHvacPowerCapabilities({ config, areas -> ok(config to areas) }, fail)
            }
        }
        val tempResult = temp.await()
        val fanResult = fan.await()
        val powerResult = power.await()
        logResult("HVAC.checkHvacTemperatureCapabilities()", tempResult) { describe(it.first, it.second?.size) }
        logResult("HVAC.checkHvacFanSpeedCapabilities()", fanResult) { describe(it.first, it.second?.size) }
        if (tempResult.isFailure && fanResult.isFailure) return@coroutineScope null

        val tempPair = tempResult.getOrNull()
        val fanPair = fanResult.getOrNull()
        val powerPair = powerResult.getOrNull()
        val tempEntry = if (tempPair?.first.writable()) driverZone(tempPair?.second.orEmpty()) else null
        val fanEntry = if (fanPair?.first.writable()) driverZone(fanPair?.second.orEmpty()) else null
        val powerAreas = if (powerPair?.first.writable()) powerPair?.second.orEmpty() else emptyList()
        ClimateCap(
            tempArea = tempEntry?.key,
            tempMin = tempEntry?.value?.minValue,
            tempMax = tempEntry?.value?.maxValue,
            fanArea = fanEntry?.key,
            fanMin = fanEntry?.value?.minValue,
            fanMax = fanEntry?.value?.maxValue,
            powerArea = powerAreas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: powerAreas.firstOrNull(),
        )
    }

    // ───────── 현재 상태 읽기(화면 표시용, 되는 만큼만) ─────────

    private suspend fun readCurrentState() {
        val vehicle = session.vehicle
        val ambient = ambientCap.get()
        ambient?.brightness?.entries?.firstOrNull()?.let { (area, bounds) ->
            val result = awaitSdk<Int?>("Light.getAmbientLightBrightness()") { ok, fail ->
                vehicle.getLight().getAmbientLightBrightness(area, ok, fail)
            }
            val percent = result.getOrNull()?.let { toPercent(it, bounds) }
            logResult("Light.getAmbientLightBrightness()", result) { "${percent ?: "값 없음"}%" }
            if (percent != null) _state.update { it.copy(ambientBrightness = percent) }
        }
        ambient?.colorAreas?.firstOrNull()?.let { area ->
            val result = awaitSdk<Int?>("Light.getAmbientLightBrightnessColor()") { ok, fail ->
                vehicle.getLight().getAmbientLightBrightnessColor(area, ok, fail)
            }
            val nearest = result.getOrNull()?.let { nearestColor(it) }
            logResult("Light.getAmbientLightBrightnessColor()", result) { argb ->
                if (argb == null) "값 없음" else "${formatArgb(argb)} (${nearest?.label})"
            }
            if (nearest != null) _state.update { it.copy(ambientColor = nearest) }
        }
        if (ambient != null) watchAmbient(ambient)
        seatHeatCap.get()?.area?.let { area ->
            val result = awaitSdk<Int?>("Seat.getHvacSeatTemperature()") { ok, fail ->
                vehicle.getSeat().getHvacSeatTemperature(area, ok, fail)
            }
            logResult("Seat.getHvacSeatTemperature(운전석)", result) { "${it ?: "값 없음"}단" }
            // 음수(통풍)는 열선 0단으로 본다.
            result.getOrNull()?.let { raw -> _state.update { it.copy(seatHeatLevel = raw.coerceIn(0, MAX_HEAT_LEVEL)) } }
        }
        if (steeringCap.get()?.supported == true) {
            val result = awaitSdk<Int?>("SteeringWheel.getHvacSteeringWheelHeatState()") { ok, fail ->
                vehicle.getSteeringWheel().getHvacSteeringWheelHeatState(ok, fail)
            }
            logResult("SteeringWheel.getHvacSteeringWheelHeatState()", result) { "${it ?: "값 없음"}단" }
            result.getOrNull()?.let { raw -> _state.update { it.copy(steeringHeat = raw > 0) } }
        }
        val climate = climateCap.get()
        climate?.tempArea?.let { area ->
            val result = awaitSdk<Float?>("HVAC.getHvacTemperature()") { ok, fail ->
                vehicle.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail)
            }
            logResult("HVAC.getHvacTemperature()", result) { "${it ?: "값 없음"}℃" }
            result.getOrNull()?.let { temp -> _state.update { it.copy(cabinTempC = temp) } }
        }
        climate?.fanArea?.let { area ->
            val result = awaitSdk<Int?>("HVAC.getHvacFanSpeed()") { ok, fail ->
                vehicle.getHVAC().getHvacFanSpeed(area, ok, fail)
            }
            logResult("HVAC.getHvacFanSpeed()", result) { "${it ?: "값 없음"}단" }
            result.getOrNull()?.let { fan -> _state.update { it.copy(fanLevel = fan) } }
        }
    }

    /**
     * 차에서 바뀐 앰비언트 밝기·색을 state 에 반영한다.
     * 앱은 조명을 바꾸기 전에 state 를 기억했다가 되돌리므로, 연결 때 한 번 읽은 값만 두면
     * 그 뒤 사용자가 차량 설정에서 바꾼 조명이 아니라 앱을 켰을 때의 조명으로 되돌아간다.
     * (연결당 한 번 등록하고, 해제는 Vehicle.release() 가 한꺼번에 한다.)
     */
    private fun watchAmbient(ambient: AmbientCap) {
        val light = session.vehicle.getLight()
        ambient.brightness.entries.firstOrNull()?.let { (area, bounds) ->
            val listener = object : AmbientLightBrightnessListener {
                override fun onBrightnessUpdated(value: Int?) {
                    if (value == null) return
                    val percent = toPercent(value, bounds)
                    SdkLog.event("Light.onBrightnessUpdated → $percent%")
                    _state.update { it.copy(ambientBrightness = percent) }
                }

                override fun onFailed(e: Exception) {
                    SdkLog.sys("앰비언트 밝기 리스너 오류 · ${e.brief()}")
                }
            }
            safeSdk("Light.registerAmbientLightBrightnessListener()") {
                light.registerAmbientLightBrightnessListener(area, listener)
                SdkLog.api("Light.registerAmbientLightBrightnessListener(listener)")
            }
        }
        ambient.colorAreas.firstOrNull()?.let { area ->
            val listener = object : AmbientLightBrightnessColorListener {
                override fun onBrightnessColorUpdated(value: Int?) {
                    if (value == null) return
                    val nearest = nearestColor(value) ?: return
                    SdkLog.event("Light.onBrightnessColorUpdated → ${formatArgb(value)} (${nearest.label})")
                    _state.update { it.copy(ambientColor = nearest) }
                }

                override fun onFailed(e: Exception) {
                    SdkLog.sys("앰비언트 색 리스너 오류 · ${e.brief()}")
                }
            }
            safeSdk("Light.registerAmbientLightBrightnessColorListener()") {
                light.registerAmbientLightBrightnessColorListener(area, listener)
                SdkLog.api("Light.registerAmbientLightBrightnessColorListener(listener)")
            }
        }
    }

    // ───────── 도우미 ─────────

    /** 기능 확인 결과를 답이 왔을 때만 기억한다. 답이 없었으면(null) 다음 호출에서 다시 묻는다. */
    private class Cached<T : Any>(private val load: suspend () -> T?) {
        private val mutex = Mutex()

        @Volatile
        private var value: T? = null

        suspend fun get(): T? = value ?: mutex.withLock {
            value ?: load()?.also { value = it }
        }
    }

    private fun driverArea(): SeatArea = SeatArea(SeatValue.ROW_1_LEFT)

    /** 운전석이 들어 있는 공조 구역. 없으면 첫 구역. */
    private fun <B> driverZone(map: Map<SeatArea, B>): Map.Entry<SeatArea, B>? =
        map.entries.firstOrNull { SeatValue.ROW_1_LEFT in it.key.values } ?: map.entries.firstOrNull()

    private fun CapabilityConfig?.writable(): Boolean =
        this?.accessType == AccessType.WRITE || this?.accessType == AccessType.READ_WRITE

    private fun describe(config: CapabilityConfig?, areaCount: Int?): String {
        val access = config?.accessType?.name ?: "접근 정보 없음"
        return if (areaCount == null) access else "$access · 영역 ${areaCount}곳"
    }

    /** 0~100 을 차가 알려 준 밝기 범위로 옮긴다(범위를 모르면 그대로). */
    private fun scaleBrightness(percent: Int, bounds: AreaBounds<Int>?): Int {
        val min = bounds?.minValue ?: 0
        val max = bounds?.maxValue ?: 100
        if (max <= min) return percent
        return min + ((max - min) * percent / 100f).roundToInt()
    }

    private fun toPercent(raw: Int, bounds: AreaBounds<Int>?): Int {
        val min = bounds?.minValue ?: 0
        val max = bounds?.maxValue ?: 100
        if (max <= min) return raw.coerceIn(0, 100)
        return ((raw - min) * 100f / (max - min)).roundToInt().coerceIn(0, 100)
    }

    /** 앱의 0~3단을 차의 최대 단계에 맞춘다(3단 차면 그대로). */
    private fun scaleLevel(level: Int, max: Int): Int =
        if (level <= 0 || max <= 0) 0 else ((level * max + 2) / MAX_HEAT_LEVEL).coerceIn(1, max)

    /** 차에서 읽은 ARGB 를 앱의 네 색 가운데 가장 가까운 색으로 옮긴다. */
    private fun nearestColor(argb: Int): AmbientColor? =
        AmbientColor.values().minByOrNull { candidate ->
            val other = candidate.argb.toInt()
            val dr = ((argb shr 16) and 0xFF) - ((other shr 16) and 0xFF)
            val dg = ((argb shr 8) and 0xFF) - ((other shr 8) and 0xFF)
            val db = (argb and 0xFF) - (other and 0xFF)
            dr * dr + dg * dg + db * db
        }

    /** set 호출의 "적용"은 SDK 의 성공 콜백을 받았다는 뜻이다. 앰비언트는 뒤따르는 리스너 이벤트(Light.on…Updated) 줄로 실제 반영을 확인할 수 있다. */
    private fun <T> logResult(call: String, result: Result<T>, describeValue: (T) -> String) {
        SdkLog.api("$call → " + result.fold({ describeValue(it) }, { "실패 · ${it.brief()}" }))
    }

    private fun notConnected(): Result<Unit> =
        Result.failure(IllegalStateException("Vehicle 서비스에 아직 연결되지 않음"))

    private fun unsupported(what: String): Result<Unit> {
        SdkLog.sys("$what: 이 차에서 지원하지 않음")
        return Result.failure(UnsupportedOperationException("$what: 이 차에서 지원하지 않음"))
    }

    private companion object {
        const val MAX_HEAT_LEVEL = 3
        const val COLOR_RESEND_MS = 10_000L
    }
}
