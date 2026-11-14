package com.naitae.connect.ui

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.DayRecord
import com.naitae.connect.core.model.FutureLetter
import com.naitae.connect.core.model.Member
import com.naitae.connect.core.model.Memo
import com.naitae.connect.core.model.Schedule
import com.naitae.connect.core.model.Topic
import com.naitae.connect.core.model.TopicSummary
import com.naitae.connect.domain.Dday
import com.naitae.connect.domain.HandoverSummary
import com.naitae.connect.domain.LetterPhase
import com.naitae.connect.domain.Recall
import com.naitae.connect.domain.RecordDraft
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.PlatformKind

/*
 * 화면 계약 (계약 파일)
 *
 * 화면은 상태(Screen)를 그리고, 사용자의 조작은 NaitaeActions 로만 알린다.
 * 어떤 화면을 언제 띄울지는 app/NaitaeCoordinator 가 차의 신호를 보고 정한다.
 * 화면 크기는 가로 16:9 안팎의 차량 디스플레이를 기준으로 한다.
 */

sealed interface Screen {
    /** 시동이 꺼져 있음 */
    data object EngineOff : Screen

    /** 나이테 홈: 주제 칩, 선택한 주제의 나이테, 통계, 최근 장면, 다가오는 주제별 일정 */
    data class Home(
        val topics: List<Topic>,
        val selected: TopicSummary?,
        val upcoming: List<Dday>,
    ) : Screen

    /** 하루 첫 시동의 일정·메모 브리핑. activeIndex = 지금 읽는 줄(-1 이면 인사말, items.size 면 메모 질문) */
    data class Briefing(
        val briefing: com.naitae.connect.domain.Briefing,
        val activeIndex: Int,
        /** "메모할 것 있으세요?" 뒤에 듣는 중 */
        val listening: Boolean = false,
        val heardText: String = "",
        /** 방금 말로 남긴 메모 */
        val savedMemo: Memo? = null,
    ) : Screen

    /** 일정에 장소가 있는 날: "낚시터로 안내할까요?" */
    data class RouteAsk(val schedule: Schedule, val topic: Topic?) : Screen

    /** 돌아가려 시동을 걸었을 때: "오늘 낚시터, 어땠어요? 오늘을 남길까요?" */
    data class RecordAsk(
        val draft: RecordDraft,
        val topic: Topic?,
        /** draft.topicId 가 없을 때 고를 주제 */
        val topicChoices: List<Topic>,
        val summary: TopicSummary?,
    ) : Screen

    /** 남긴 기록: 차가 채운 정보 + 한마디·사진 더하기 */
    data class RecordEdit(
        val record: DayRecord,
        val topic: Topic,
        val summary: TopicSummary,
        val listening: Boolean = false,
        val heardText: String = "",
    ) : Screen

    /** 함께 꾸미기: 사진, 이 장면의 색(= 실내 조명 색) */
    data class Decorate(
        val record: DayRecord,
        val topic: Topic,
        /** 이 주제를 공유받은 사람들 */
        val members: List<Member>,
        val colors: List<AmbientColor>,
    ) : Screen

    /** 차에서 다시 보기: "1년 전 오늘, 여기서" */
    data class RecallView(val recall: Recall, val playingVoice: Boolean = false) : Screen

    /** 편지가 도착했어요: 열기 / 도착해서 보기 */
    data class LetterArrive(val letter: FutureLetter, val fromNames: String) : Screen

    /** 편지 연출 중·낭독 중. shownText = 지금까지 보여 준 글자 */
    data class LetterOpen(
        val letter: FutureLetter,
        val fromNames: String,
        val phase: LetterPhase,
        val shownText: String,
        /** 새 차 첫 시동의 편지일 때 함께 보여 줄 나이테 */
        val rings: TopicSummary? = null,
    ) : Screen

    /** 이어받기: 새 차 0km · 나이테 N겹 */
    data class Handover(
        val summary: HandoverSummary,
        val rings: TopicSummary?,
        val newCarOdometerKm: Float?,
    ) : Screen
}

data class UiState(
    val screen: Screen = Screen.EngineOff,
    /** 장소·퇴근길 메모 다시 알림(화면 위에 잠깐 뜨는 카드) */
    val memoToast: Memo? = null,
    val memoToastAuthor: String? = null,
    /** 지금 음성으로 읽고 있는 문장(화면 아래 자막). 없으면 null */
    val voiceLine: String? = null,
    /** 주행 중(기어가 P 가 아님): 화면 입력을 받지 않는다 */
    val driving: Boolean = false,
    val gear: GearState = GearState.PARK,
    val platform: PlatformKind = PlatformKind.SIMULATOR,
    /** "07:42" */
    val clockLabel: String = "",
    /** "그랜저" / "새 현대차" */
    val carLabel: String = "",
    val cabin: CabinState = CabinState(),
    val odometerKm: Float? = null,
)

/** 사용자의 조작. 화면은 이것만 부른다. */
interface NaitaeActions {
    fun selectTopic(topicId: String)
    fun openHome()

    // 브리핑
    fun stopBriefing()

    // 길 안내 질문
    fun acceptRoute()
    fun declineRoute()

    // 기록
    /** topicId = 초안의 주제 또는 사용자가 고른 주제 */
    fun acceptRecord(topicId: String)
    fun declineRecord()
    fun startVoiceNote()
    fun openDecorate()
    fun openDecorateFor(recordId: String)
    fun pickColor(color: AmbientColor)
    fun addPhoto()
    fun finishDecorate()
    fun finishRecord()

    // 다시 보기
    fun playRecallVoice()
    fun closeRecall()

    // 미래 편지
    fun openLetter()
    fun postponeLetter()
    fun closeLetter()

    // 이어받기
    fun dismissHandover()

    fun dismissMemoToast()
}
