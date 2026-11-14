package com.naitae.connect.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 「나이테」 데이터 모델 (계약 파일: 구조를 바꾸려면 docs/ARCHITECTURE.md 의 변경 절차를 따른다)
 *
 * 나이테 단위: 1년 = 한 겹(ring). 하루의 기록(DayRecord)은 그해 겹에 새겨지는 장면(점) 하나.
 * 날짜는 java.time.LocalDate.toEpochDay() 값(Long)으로 저장한다.
 */

/** 실내 조명 색. Pleos Connect Virtual Console 이 지원하는 4색만 쓴다. */
@Serializable
enum class AmbientColor(val argb: Long, val label: String) {
    PURPLE(0xFF8E6CC0, "보라"),
    BLUE(0xFF3E7BD6, "파랑"),
    CYAN(0xFF2DB7C4, "청록"),
    WHITE(0xFFF4EBDD, "흰색"),
}

@Serializable
enum class MemberRole { OWNER, FAMILY, PARTNER, FRIEND, CHILD }

/** 차주와 공유받은 사람. 가상 페르소나 데이터만 쓴다. */
@Serializable
data class Member(val id: String, val name: String, val role: MemberRole)

@Serializable
data class Place(val name: String, val lat: Double, val lon: Double)

/** 주제 그림(일러스트) 종류. 프로토타입은 사진 대신 그림을 쓴다. */
@Serializable
enum class SceneKind { WORK, FISHING, RUNNING, GOLF, FAMILY_TRIP, GENERIC }

/** 주제: 본업·낚시·러닝·골프·우리 가족 여행처럼 차주가 만드는 삶의 주제. 주제마다 나이테가 따로 자란다. */
@Serializable
data class Topic(
    val id: String,
    val name: String,
    val ownerId: String,
    val scene: SceneKind = SceneKind.GENERIC,
    /** 이 주제의 장소(내비 즐겨찾기와 연결). 여기에 도착하면 이 주제의 기록으로 본다. */
    val places: List<Place> = emptyList(),
    /** 이 주제를 공유받은 사람의 id. 공유받은 사람은 공유받은 주제에만 들어간다. */
    val sharedWith: List<String> = emptyList(),
)

@Serializable
enum class ScheduleSource { PHONE_CALENDAR, CAR, SHARED }

/** 일정. topicId 가 있으면 '주제별 일정'(D-day 로 알려 줌). place 가 있으면 출발 전에 길 안내를 물어본다. */
@Serializable
data class Schedule(
    val id: String,
    val title: String,
    val epochDay: Long,
    /** "HH:mm" 또는 null(종일) */
    val time: String? = null,
    val topicId: String? = null,
    val place: Place? = null,
    val createdBy: String,
    val source: ScheduleSource = ScheduleSource.CAR,
)

/** 메모를 다시 알려 줄 때 */
@Serializable
sealed interface MemoRemind {
    /** 다음 하루 첫 시동의 브리핑에서 */
    @Serializable
    @SerialName("next")
    data object NextIgnition : MemoRemind

    /** 브리핑에서 한 번, 그리고 afterHour 시 이후의 시동(퇴근길)에서 한 번 더 */
    @Serializable
    @SerialName("evening")
    data class EveningIgnition(val afterHour: Int = 17) : MemoRemind

    /** 장소에 가까워지면 (FusedLocation 근접 알림) */
    @Serializable
    @SerialName("place")
    data class AtPlace(val place: Place, val radiusM: Int = 300) : MemoRemind
}

@Serializable
data class Memo(
    val id: String,
    val text: String,
    val authorId: String,
    val createdAtEpochMs: Long,
    val remind: MemoRemind = MemoRemind.NextIgnition,
    /** 개인 메모: 동승자가 있으면 읽지 않고 미룬다 */
    val personal: Boolean = false,
    /** 브리핑에서 읽어 준 날 */
    val briefedEpochDay: Long? = null,
    /** 다시 알림(퇴근길·장소)까지 끝난 날 */
    val remindedEpochDay: Long? = null,
    val done: Boolean = false,
)

/** 한마디(목소리와 글). audioAsset 은 녹음 파일 경로(없으면 TTS 로 읽음). */
@Serializable
data class VoiceNote(val text: String, val authorId: String? = null, val audioAsset: String? = null)

/** 사진. 프로토타입은 실제 사진 대신 장면 그림 종류로 대신한다. */
@Serializable
data class Photo(val id: String, val scene: SceneKind, val addedBy: String)

/** 특별한 날의 기록 = 그해 겹에 새겨지는 장면 하나 */
@Serializable
data class DayRecord(
    val id: String,
    val topicId: String,
    val epochDay: Long,
    val placeName: String,
    val place: Place? = null,
    /** 차가 채운 정보: 주행거리(이번 이동), 처음 간 곳 여부, 착석 좌석 수(누구인지는 저장하지 않음) */
    val distanceKm: Float,
    val firstVisit: Boolean,
    val seatCount: Int,
    val scheduleId: String? = null,
    val notes: List<VoiceNote> = emptyList(),
    val photos: List<Photo> = emptyList(),
    /** 공유받은 사람이 고른 이 장면의 색 = 실내 조명 색 */
    val color: AmbientColor? = null,
    /** 이 기록을 함께한 차 */
    val vin: String,
) {
    /** 함께한 날(동승 여부) */
    val together: Boolean get() = seatCount > 1
}

/** 미래 편지 연출: 보내는 사람이 고르는 차의 맞이 */
@Serializable
data class LetterEffect(
    val color: AmbientColor = AmbientColor.WHITE,
    /** 0~100 */
    val brightness: Int = 100,
    /** 서서히 밝아지는 시간(초). 앱이 단계적으로 밝기를 올린다. */
    val rampSeconds: Int = 3,
    /** 운전석 시트 열선 0(끔)~3 */
    val seatHeatLevel: Int = 0,
    val steeringHeat: Boolean = false,
    val cabinTempC: Float? = null,
    val fanLevel: Int? = null,
)

/** 미래 편지: 공유받은 사람이 정한 날짜에 열리도록 예약해 보내는 비공개 메시지 */
@Serializable
data class FutureLetter(
    val id: String,
    val fromIds: List<String>,
    val text: String,
    /** 열리는 날. 그날 차를 타지 않으면 다음 시동 때 열린다. */
    val openEpochDay: Long,
    val effect: LetterEffect = LetterEffect(),
    /** 보낸 사람이 녹음한 목소리(없으면 TTS) */
    val audioAsset: String? = null,
    /** 새 차의 첫 시동에 열리는 편지 */
    val openOnNewCar: Boolean = false,
    val openedEpochDay: Long? = null,
)

@Serializable
data class CarProfile(
    val vin: String,
    val model: String,
    val startEpochDay: Long,
    /** 차를 매각·양도한 날 (차 안 기록은 지우고 계정 원본만 남김) */
    val retiredEpochDay: Long? = null,
)

/** 「나이테」 계정 원본 전체 */
@Serializable
data class NaitaeData(
    val owner: Member,
    val members: List<Member> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val memos: List<Memo> = emptyList(),
    val records: List<DayRecord> = emptyList(),
    val letters: List<FutureLetter> = emptyList(),
    val cars: List<CarProfile> = emptyList(),
    /** 하루 첫 시동 브리핑을 한 마지막 날 */
    val lastBriefingEpochDay: Long? = null,
    val schemaVersion: Int = 1,
) {
    fun member(id: String?): Member? = if (id == owner.id) owner else members.firstOrNull { it.id == id }
    fun topic(id: String?): Topic? = topics.firstOrNull { it.id == id }
    val currentCar: CarProfile? get() = cars.lastOrNull { it.retiredEpochDay == null }
}

// ───────── 화면용 계산 결과 (저장하지 않음) ─────────

/** 겹 위의 점 = 특별한 날 */
data class SceneDot(val recordId: String, val angleDeg: Float, val firstVisit: Boolean, val isNew: Boolean = false)

/** 나이테 한 겹 = 1년 */
data class RingView(
    val year: Int,
    /** 0 = 가장 안쪽(첫해) */
    val index: Int,
    /** 지난해면 true, 올해 자라는 중이면 false */
    val complete: Boolean,
    /** 올해 겹의 진행(0~1). complete 면 1 */
    val progress: Float,
    /** 그해 함께한 시간에 비례한 두께(0.5~1.5) */
    val thickness: Float,
    val color: AmbientColor?,
    val vin: String?,
    val scenes: List<SceneDot>,
)

data class TopicStat(val label: String, val value: String)

data class TopicSummary(
    val topic: Topic,
    val rings: List<RingView>,
    val stats: List<TopicStat>,
    val latest: DayRecord?,
)
