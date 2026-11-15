package com.naitae.connect

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.Place
import com.naitae.connect.vehicle.CabinCapabilities
import com.naitae.connect.vehicle.CabinControl
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.RecordedVoicePlayer
import com.naitae.connect.vehicle.SeatOccupancy
import com.naitae.connect.vehicle.VoiceOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/* 단위 테스트용 가짜 차량 연결. 불린 순서를 하나의 기록(log)에 남겨 순서를 검사한다. */

class FakeCabin(
    initial: CabinState = CabinState(),
    private val log: MutableList<String> = ArrayList(),
) : CabinControl {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<CabinState> = _state

    override suspend fun capabilities(): CabinCapabilities = CabinCapabilities(
        ambientLight = true,
        ambientColors = AmbientColor.values().toList(),
        seatHeat = true,
        steeringHeat = true,
        climate = true,
    )

    override suspend fun setAmbient(color: AmbientColor, brightness: Int): Result<Unit> {
        log.add("ambient:${color.name}:$brightness")
        _state.value = _state.value.copy(ambientColor = color, ambientBrightness = brightness)
        return Result.success(Unit)
    }

    override suspend fun setSeatHeat(level: Int): Result<Unit> {
        log.add("seat:$level")
        _state.value = _state.value.copy(seatHeatLevel = level)
        return Result.success(Unit)
    }

    override suspend fun setSteeringHeat(on: Boolean): Result<Unit> {
        log.add("steering:$on")
        _state.value = _state.value.copy(steeringHeat = on)
        return Result.success(Unit)
    }

    override suspend fun setClimate(tempC: Float?, fanLevel: Int?): Result<Unit> {
        log.add("climate:$tempC:$fanLevel")
        _state.value = _state.value.copy(cabinTempC = tempC, fanLevel = fanLevel)
        return Result.success(Unit)
    }
}

class FakeVoice(private val log: MutableList<String> = ArrayList()) : VoiceOutput {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking

    override suspend fun speak(text: String): Result<Unit> {
        log.add("speak:$text")
        return Result.success(Unit)
    }

    override fun stop() {
        log.add("voice-stop")
    }
}

/** available 이 false 면 녹음 파일이 없는 것처럼 false 를 돌려준다. */
class FakeRecordedVoice(
    private val available: Boolean,
    private val log: MutableList<String> = ArrayList(),
) : RecordedVoicePlayer {
    override suspend fun play(asset: String): Boolean {
        log.add("play:$asset")
        return available
    }

    override fun stop() {
        log.add("recorded-stop")
    }
}

/** 운전자를 포함해 count 명이 탄 좌석 */
fun seatsOf(count: Int): SeatOccupancy = SeatOccupancy(
    driver = true,
    frontPassenger = count >= 2,
    rearLeft = count >= 3,
    rearRight = count >= 4,
)

fun arrivedAt(place: Place, distanceKm: Float? = null): NaviEvent.Arrived =
    NaviEvent.Arrived(name = place.name, lat = place.lat, lon = place.lon, distanceKm = distanceKm)
