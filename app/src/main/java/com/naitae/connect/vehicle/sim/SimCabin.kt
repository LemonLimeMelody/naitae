package com.naitae.connect.vehicle.sim

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.vehicle.CabinCapabilities
import com.naitae.connect.vehicle.CabinControl
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 실내 제어 시뮬레이터. 값을 들고 있다가 화면(실내 조명 표현)에 그대로 보여 준다. */
internal class SimCabin : CabinControl {

    private val _state = MutableStateFlow(USUAL)
    override val state: StateFlow<CabinState> = _state.asStateFlow()

    override suspend fun capabilities(): CabinCapabilities {
        val colors = AmbientColor.values().toList()
        SdkLog.api(
            "Vehicle.light.checkAmbientLightBrightnessColorCapabilities() → " +
                colors.joinToString("·") { it.label },
        )
        return CabinCapabilities(
            ambientLight = true,
            ambientColors = colors,
            seatHeat = true,
            steeringHeat = true,
            climate = true,
        )
    }

    override suspend fun setAmbient(color: AmbientColor, brightness: Int): Result<Unit> {
        val level = brightness.coerceIn(0, 100)
        // 서서히 밝아지는 연출은 밝기만 여러 번 부르므로, 색은 바뀔 때만 남긴다
        if (_state.value.ambientColor != color) {
            SdkLog.api("Vehicle.light.setAmbientLightBrightnessColor(${color.name})")
        }
        SdkLog.api("Vehicle.light.setAmbientLightBrightness($level)")
        _state.update { it.copy(ambientColor = color, ambientBrightness = level) }
        return Result.success(Unit)
    }

    override suspend fun setSeatHeat(level: Int): Result<Unit> {
        val step = level.coerceIn(0, 3)
        SdkLog.api("Vehicle.seat.setHvacSeatTemperature(DRIVER, $step)")
        _state.update { it.copy(seatHeatLevel = step) }
        return Result.success(Unit)
    }

    override suspend fun setSteeringHeat(on: Boolean): Result<Unit> {
        SdkLog.api("Vehicle.steeringWheel.setHvacSteeringWheelHeat(${if (on) 1 else 0})")
        _state.update { it.copy(steeringHeat = on) }
        return Result.success(Unit)
    }

    override suspend fun setClimate(tempC: Float?, fanLevel: Int?): Result<Unit> {
        val fan = fanLevel?.coerceIn(0, MAX_FAN_LEVEL)
        if (tempC != null) SdkLog.api("Vehicle.HVAC.setHvacTemperature(DRIVER, $tempC℃)")
        if (fan != null) SdkLog.api("Vehicle.HVAC.setHvacFanSpeed(DRIVER, $fan)")
        _state.update {
            it.copy(cabinTempC = tempC ?: it.cabinTempC, fanLevel = fan ?: it.fanLevel)
        }
        return Result.success(Unit)
    }

    /** 시동을 걸면 차주가 평소 쓰는 설정에서 시작한다. */
    fun resetToUsual() {
        _state.value = USUAL
    }

    private companion object {
        const val MAX_FAN_LEVEL = 8

        /** 차주의 평소 설정: 흰색 30, 열선 끔, 22℃, 바람 0 */
        val USUAL = CabinState(
            ambientColor = AmbientColor.WHITE,
            ambientBrightness = 30,
            seatHeatLevel = 0,
            steeringHeat = false,
            cabinTempC = 22f,
            fanLevel = 0,
        )
    }
}
