package com.naitae.connect.vehicle

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime

/*
 * 차량 연결 계약 (계약 파일)
 *
 * 앱의 나머지 부분은 이 인터페이스만 본다. 구현은 둘이다.
 *  - vehicle/pleos : 실제 Pleos Connect SDK (Vehicle · NaviHelper · TextToSpeech · SpeechToText · FusedLocation)
 *  - vehicle/sim   : Pleos 서비스가 없는 기기(일반 에뮬레이터·휴대폰)에서 쓰는 시뮬레이터 + 가상 콘솔
 */

enum class PlatformKind { PLEOS, SIMULATOR }

enum class GearState { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 좌석 착석. 누가 앉았는지는 다루지 않는다. */
data class SeatOccupancy(
    val driver: Boolean = true,
    val frontPassenger: Boolean = false,
    val rearLeft: Boolean = false,
    val rearRight: Boolean = false,
) {
    val count: Int get() = listOf(driver, frontPassenger, rearLeft, rearRight).count { it }
    val hasPassenger: Boolean get() = count > 1
}

/** 차가 알려 주는 신호 (읽기) */
interface VehicleSignals {
    /** 시동(전원) 상태. Pleos 구현은 SDK 연결·기어 이벤트로 판단한다. */
    val ignitionOn: StateFlow<Boolean>
    val gear: StateFlow<GearState>
    /** 누적 주행거리(km). 아직 모르면 null */
    val odometerKm: StateFlow<Float?>
    val seats: StateFlow<SeatOccupancy>
    /** 차대번호. 못 읽으면 null */
    suspend fun vin(): String?
    fun start()
    fun stop()
}

data class CabinState(
    val ambientColor: AmbientColor? = null,
    /** 0~100 */
    val ambientBrightness: Int? = null,
    /** 운전석 시트 열선 0~3 */
    val seatHeatLevel: Int? = null,
    val steeringHeat: Boolean? = null,
    val cabinTempC: Float? = null,
    val fanLevel: Int? = null,
)

data class CabinCapabilities(
    val ambientLight: Boolean = false,
    val ambientColors: List<AmbientColor> = emptyList(),
    val seatHeat: Boolean = false,
    val steeringHeat: Boolean = false,
    val climate: Boolean = false,
)

/** 실내 제어 (쓰기). 모든 호출은 정차(P) 상태에서만 부른다. */
interface CabinControl {
    /** 화면 표시용 현재 상태(알고 있는 범위) */
    val state: StateFlow<CabinState>
    suspend fun capabilities(): CabinCapabilities
    suspend fun setAmbient(color: AmbientColor, brightness: Int): Result<Unit>
    suspend fun setSeatHeat(level: Int): Result<Unit>
    suspend fun setSteeringHeat(on: Boolean): Result<Unit>
    suspend fun setClimate(tempC: Float?, fanLevel: Int?): Result<Unit>
}

sealed interface NaviEvent {
    /** 길 안내 중 목적지 도착 (NaviHelper onDestinationArrived) */
    data class Arrived(
        val name: String,
        val lat: Double? = null,
        val lon: Double? = null,
        /** 이번 안내로 달린 거리(km) */
        val distanceKm: Float? = null,
        val durationMin: Int? = null,
    ) : NaviEvent

    data class GuidanceStarted(val name: String) : NaviEvent
    data object GuidanceStopped : NaviEvent
}

interface NaviGateway {
    val events: SharedFlow<NaviEvent>
    /** 사용자가 원할 때만 부른다. 자동으로 길 안내를 시작하지 않는다. */
    suspend fun requestRoute(place: Place): Result<Unit>
    /** 내비 즐겨찾기(주제 장소로 고를 수 있는 곳) */
    suspend fun bookmarks(): List<Place>
    fun start()
    fun stop()
}

/** 음성 출력 (TextToSpeech SDK) */
interface VoiceOutput {
    val speaking: StateFlow<Boolean>
    /** 다 읽을 때까지 기다린다. stop() 이 불리면 바로 끝난다. */
    suspend fun speak(text: String): Result<Unit>
    fun stop()
}

sealed interface VoiceResult {
    data class Text(val text: String) : VoiceResult
    data object NoSpeech : VoiceResult
    data class Failed(val reason: String) : VoiceResult
}

/** 음성 입력 (SpeechToText SDK, ko-KR). 화면 입력 없이 쓴다. */
interface VoiceInput {
    /** 듣는 중 실시간으로 인식된 글 */
    val partial: StateFlow<String>
    suspend fun listen(timeoutMs: Long = 8000): VoiceResult
    fun cancel()
}

data class ProximityEvent(val placeName: String, val entered: Boolean)

/** 장소 근접 알림 (FusedLocation SDK addProximityAlert) */
interface ProximityGateway {
    val events: SharedFlow<ProximityEvent>
    suspend fun isSupported(): Boolean
    suspend fun watch(place: Place, radiusM: Int): Result<Unit>
    suspend fun unwatch(placeName: String)
}

/** 녹음한 목소리 재생(미디어 재생). 파일이 없으면 false 를 돌려주고, 호출한 쪽이 TTS 로 대신한다. */
interface RecordedVoicePlayer {
    suspend fun play(asset: String): Boolean
    fun stop()
}

/** 앱이 쓰는 시계. 시뮬레이터는 날짜·시각을 바꿀 수 있다. */
fun interface AppClock {
    fun now(): LocalDateTime
}

class VehiclePlatform(
    val kind: PlatformKind,
    val signals: VehicleSignals,
    val cabin: CabinControl,
    val navi: NaviGateway,
    val voiceOut: VoiceOutput,
    val voiceIn: VoiceInput,
    val proximity: ProximityGateway,
    val clock: AppClock,
    /** 시뮬레이터일 때만 있음 */
    val console: SimulatorConsole? = null,
)

// ───────── SDK 호출 로그 (가상 콘솔·디버그 화면에 표시) ─────────

enum class SdkLogKind { API, EVENT, SYS }

data class SdkLogLine(val kind: SdkLogKind, val text: String, val atMs: Long = System.currentTimeMillis())

/** Pleos 구현과 시뮬레이터가 '어떤 SDK 를 불렀는지'를 한 줄씩 남긴다. */
object SdkLog {
    private const val MAX = 200
    private val _lines = MutableStateFlow<List<SdkLogLine>>(emptyList())
    val lines: StateFlow<List<SdkLogLine>> = _lines.asStateFlow()

    fun api(text: String) = add(SdkLogKind.API, text)
    fun event(text: String) = add(SdkLogKind.EVENT, text)
    fun sys(text: String) = add(SdkLogKind.SYS, text)
    fun clear() { _lines.value = emptyList() }

    private fun add(kind: SdkLogKind, text: String) {
        _lines.value = (_lines.value + SdkLogLine(kind, text)).takeLast(MAX)
    }
}

// ───────── 시뮬레이터 가상 콘솔 (Virtual Console 을 흉내 냄) ─────────

/** 발표 시나리오의 날짜 */
enum class ScenarioDay(val label: String) {
    WEEKDAY("평일 · 출근길"),
    FISHING_SATURDAY("토요일 새벽 · 낚시 일정"),
    FAMILY_SUNDAY("일요일 · 가족 나들이"),
    ONE_YEAR_LATER("1년 뒤 같은 날"),
    BIRTHDAY("아빠 생일 아침"),
    TWENTY_YEARS_LATER("20년 뒤 · 선물 받은 새 차"),
}

data class SimState(
    val day: ScenarioDay = ScenarioDay.WEEKDAY,
    val now: LocalDateTime,
    val ignitionOn: Boolean = false,
    val gear: GearState = GearState.PARK,
    val odometerKm: Float = 0f,
    val seats: SeatOccupancy = SeatOccupancy(),
    val vin: String = "",
    val carModel: String = "",
    val guidanceTo: Place? = null,
)

interface SimulatorConsole {
    val state: StateFlow<SimState>
    /** 목적지 버튼으로 쓸 장소(회사·낚시터·수목원 등) */
    val places: List<Place>
    /** 날짜를 바꾼다. 시동은 꺼지고, 날짜에 맞는 시각·좌석·주행거리·차(VIN)가 정해진다. */
    fun setDay(day: ScenarioDay)
    fun setClock(hour: Int, minute: Int)
    fun ignition(on: Boolean)
    fun gear(gear: GearState)
    /** 그 장소까지 달려 도착한다(주행거리가 늘고 도착 이벤트가 온 뒤 P). */
    fun arrive(place: Place)
    fun setSeat(index: Int, occupied: Boolean)
    /** STT 시뮬레이션: 다음 listen() 이 돌려줄 말 */
    fun sayNext(text: String)
}
