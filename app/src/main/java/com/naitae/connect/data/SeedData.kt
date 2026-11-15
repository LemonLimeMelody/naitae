package com.naitae.connect.data

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.CarProfile
import com.naitae.connect.core.model.DayRecord
import com.naitae.connect.core.model.FutureLetter
import com.naitae.connect.core.model.LetterEffect
import com.naitae.connect.core.model.Member
import com.naitae.connect.core.model.MemberRole
import com.naitae.connect.core.model.Memo
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Photo
import com.naitae.connect.core.model.Place
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.core.model.Schedule
import com.naitae.connect.core.model.ScheduleSource
import com.naitae.connect.core.model.Topic
import com.naitae.connect.core.model.VoiceNote
import com.naitae.connect.domain.NAITAE_ZONE
import com.naitae.connect.domain.RecordEngine
import com.naitae.connect.vehicle.ScenarioDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 가상 페르소나 데이터(실제 인물 아님): 40대 맞벌이 부부, 딸 지우(10세), 아들 도윤(6세), 그랜저.
 * 시나리오 날짜와 장소는 시뮬레이터와 함께 쓰므로 이 객체가 기준이다.
 *
 * base(today) 는 'today 아침, 시동을 걸기 전'의 계정 원본이다. 날짜가 뒤로 갈수록 앞선 날에 쌓였을 것이 더해진다.
 * 난수를 쓰지 않으므로 같은 날짜에는 늘 같은 데이터가 나온다.
 */
object SeedData {
    const val VIN_FIRST_CAR = "KMHGRANDEUR000001"
    const val VIN_NEW_CAR = "KMHNEWCAR00000021"

    val OFFICE = Place("회사", 37.4021, 127.1086)
    val PIER = Place("낚시터", 37.2453, 126.5672)
    val ARBORETUM = Place("수목원", 37.7513, 127.1672)
    val MART = Place("마트", 37.3948, 127.1112)
    val places: List<Place> = listOf(OFFICE, PIER, ARBORETUM, MART)

    // ───────── 다른 담당이 기대어 쓸 수 있는 id (같은 모듈 안에서만) ─────────

    internal const val OWNER_ID = "owner"
    internal const val MOM_ID = "mom"
    internal const val JIWOO_ID = "jiwoo"
    internal const val DOYUN_ID = "doyun"

    internal const val TOPIC_WORK = "work"
    internal const val TOPIC_FISH = "fish"
    internal const val TOPIC_RUN = "run"
    internal const val TOPIC_GOLF = "golf"
    internal const val TOPIC_FAMILY = "family"

    internal const val SCHEDULE_MEETING = "sch-meeting"
    internal const val SCHEDULE_PIANO = "sch-piano"
    internal const val SCHEDULE_FISHING = "sch-fishing"
    internal const val SCHEDULE_FAMILY = "sch-family"

    internal const val MEMO_MILK = "memo-milk"
    internal const val MEMO_CAR_WASH = "memo-carwash"

    internal const val LETTER_BIRTHDAY = "letter-birthday"
    internal const val LETTER_NEW_CAR = "letter-new-car"

    /** 녹음한 목소리 파일(assets). 파일이 없으면 앱이 TTS 로 대신 읽는다. */
    internal const val VOICE_JIWOO_LAST_YEAR = "voice/jiwoo_last_year.mp3"
    internal const val VOICE_BIRTHDAY_LETTER = "voice/birthday_letter.mp3"
    internal const val VOICE_UMBRELLA_LETTER = "voice/umbrella_letter.mp3"

    internal const val CAR_MODEL = "그랜저"
    internal const val NOTE_FISHING = "올해 첫 감성돔"
    internal const val NOTE_JIWOO = "아빠, 내년에 또 오자!"
    internal const val LETTER_BIRTHDAY_TEXT = "아빠, 생일 축하해! 오늘 출근길도 조심히 다녀와요."
    internal const val LETTER_NEW_CAR_TEXT =
        "늘 나를 지켜 주는 우산인 줄 알았던 아빠가, 이제는 빗길 없이 따뜻하게만 달렸으면 좋겠습니다."

    /** 기록이 쌓이는 첫해와 마지막 해(20년치: 2026~2045). 2046년은 새 차의 해라 기록이 없다. */
    internal const val FIRST_YEAR = 2026
    internal const val LAST_YEAR = 2045

    /** 시나리오 날짜별 시작 시각 */
    fun dateTimeOf(day: ScenarioDay): LocalDateTime = when (day) {
        ScenarioDay.WEEKDAY -> LocalDateTime.of(2026, 11, 12, 7, 42)            // 목요일
        ScenarioDay.FISHING_SATURDAY -> LocalDateTime.of(2026, 11, 14, 5, 3)   // 토요일
        ScenarioDay.FAMILY_SUNDAY -> LocalDateTime.of(2026, 11, 15, 10, 20)    // 일요일
        ScenarioDay.ONE_YEAR_LATER -> LocalDateTime.of(2027, 11, 15, 10, 31)
        ScenarioDay.BIRTHDAY -> LocalDateTime.of(2027, 11, 23, 7, 38)
        ScenarioDay.TWENTY_YEARS_LATER -> LocalDateTime.of(2046, 11, 15, 11, 5)
    }

    /** 시연에서 '목적지 도착'을 넣을 때 쓰는, 그 장소까지 달린 거리(km) */
    fun demoDistanceKm(place: Place): Float = when (place.name) {
        OFFICE.name -> 18f
        PIER.name -> 32f
        ARBORETUM.name -> 41f
        MART.name -> 6f
        else -> 10f
    }

    /**
     * 가상 데이터의 첫 차(그랜저)를 지금 차의 VIN 으로 바꾼다(차 목록과 그 차로 남긴 기록 모두).
     * Pleos 에뮬레이터처럼 차가 알려 주는 VIN 이 가상 데이터와 다른 환경에서, 평소의 날에 이어받기가 뜨지 않게 한다.
     */
    fun withFirstCarVin(data: NaitaeData, vin: String): NaitaeData {
        if (vin.isBlank() || vin == VIN_FIRST_CAR) return data
        return data.copy(
            cars = data.cars.map { if (it.vin == VIN_FIRST_CAR) it.copy(vin = vin) else it },
            records = data.records.map { if (it.vin == VIN_FIRST_CAR) it.copy(vin = vin) else it },
        )
    }

    /** 그날 시동을 걸기 전까지 쌓여 있어야 할 데이터 */
    fun forDay(day: ScenarioDay): NaitaeData = base(dateTimeOf(day).toLocalDate())

    /** today 시점의 가상 페르소나 데이터 */
    fun base(today: LocalDate): NaitaeData {
        val day = today.toEpochDay()
        return NaitaeData(
            owner = OWNER,
            members = listOf(MOM, JIWOO, DOYUN),
            topics = TOPICS,
            schedules = schedules(today),
            memos = memos(today),
            // 오늘의 기록은 오늘 차에서 남기는 것이므로 어제까지만 넣는다.
            records = allRecords.filter { it.epochDay < day },
            letters = letters(today),
            cars = listOf(CarProfile(vin = VIN_FIRST_CAR, model = CAR_MODEL, startEpochDay = CAR_START.toEpochDay())),
            // 첫 시나리오 날(목요일) 뒤로는 날마다 차를 탔으므로 어제 브리핑까지 끝나 있다.
            lastBriefingEpochDay = if (today.isAfter(THURSDAY)) day - 1 else null,
        )
    }

    // ───────── 사람·주제 ─────────

    private val OWNER = Member(OWNER_ID, "아빠", MemberRole.OWNER)
    private val MOM = Member(MOM_ID, "엄마", MemberRole.FAMILY)
    private val JIWOO = Member(JIWOO_ID, "지우", MemberRole.CHILD)
    private val DOYUN = Member(DOYUN_ID, "도윤", MemberRole.CHILD)

    private val TOPICS: List<Topic> = listOf(
        Topic(TOPIC_WORK, "본업", OWNER_ID, SceneKind.WORK, places = listOf(OFFICE)),
        Topic(TOPIC_FISH, "낚시", OWNER_ID, SceneKind.FISHING, places = listOf(PIER)),
        Topic(TOPIC_RUN, "러닝", OWNER_ID, SceneKind.RUNNING),
        Topic(TOPIC_GOLF, "골프", OWNER_ID, SceneKind.GOLF),
        Topic(
            TOPIC_FAMILY, "우리 가족 여행", OWNER_ID, SceneKind.FAMILY_TRIP,
            places = listOf(ARBORETUM),
            sharedWith = listOf(MOM_ID, JIWOO_ID, DOYUN_ID),
        ),
    )

    // ───────── 날짜 ─────────

    private val CAR_START: LocalDate = LocalDate.of(2026, 1, 3)
    private val THURSDAY: LocalDate = LocalDate.of(2026, 11, 12)
    private val FRIDAY: LocalDate = LocalDate.of(2026, 11, 13)
    private val SATURDAY: LocalDate = LocalDate.of(2026, 11, 14)
    private val SUNDAY: LocalDate = LocalDate.of(2026, 11, 15)
    private val BIRTHDAY: LocalDate = LocalDate.of(2027, 11, 23)
    private val BIRTHDAY_LETTER_WRITTEN: LocalDate = LocalDate.of(2027, 11, 1)
    private val NEW_CAR_DAY: LocalDate = LocalDate.of(2046, 11, 15)
    private val NEW_CAR_LETTER_WRITTEN: LocalDate = LocalDate.of(2046, 11, 1)

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(NAITAE_ZONE).toInstant().toEpochMilli()

    // ───────── 일정·메모·편지 ─────────

    private fun schedules(today: LocalDate): List<Schedule> {
        val list = arrayListOf(
            Schedule(
                id = SCHEDULE_MEETING, title = "거래처 미팅", epochDay = THURSDAY.toEpochDay(), time = "10:00",
                createdBy = OWNER_ID, source = ScheduleSource.PHONE_CALENDAR,
            ),
            Schedule(
                id = SCHEDULE_PIANO, title = "지우 피아노 학원 픽업", epochDay = THURSDAY.toEpochDay(), time = "19:00",
                createdBy = MOM_ID, source = ScheduleSource.SHARED,
            ),
            Schedule(
                id = SCHEDULE_FISHING, title = "낚시", epochDay = SATURDAY.toEpochDay(), time = "05:00",
                topicId = TOPIC_FISH, place = PIER, createdBy = OWNER_ID, source = ScheduleSource.PHONE_CALENDAR,
            ),
            Schedule(
                id = SCHEDULE_FAMILY, title = "가족 나들이", epochDay = SUNDAY.toEpochDay(), time = null,
                topicId = TOPIC_FAMILY, place = ARBORETUM, createdBy = MOM_ID, source = ScheduleSource.SHARED,
            ),
        )
        // 이듬해부터는 해마다 같은 날 수목원에 간다("아빠, 내년에 또 오자!").
        if (today.year in (FIRST_YEAR + 1)..LAST_YEAR) {
            list += Schedule(
                id = "$SCHEDULE_FAMILY-${today.year}", title = "가족 나들이",
                epochDay = LocalDate.of(today.year, 11, 15).toEpochDay(), time = null,
                topicId = TOPIC_FAMILY, place = ARBORETUM, createdBy = MOM_ID, source = ScheduleSource.SHARED,
            )
        }
        return list
    }

    private fun memos(today: LocalDate): List<Memo> {
        val afterThursday = today.isAfter(THURSDAY)
        val list = arrayListOf(
            // 목요일 아침 브리핑에서 읽고, 퇴근길 시동(17시 이후)에 한 번 더 알려 주는 메모
            Memo(
                id = MEMO_MILK, text = "퇴근길에 우유 사 오기", authorId = MOM_ID,
                createdAtEpochMs = epochMs(2026, 11, 11, 21, 10),
                remind = MemoRemind.EveningIgnition(afterHour = 17),
                briefedEpochDay = if (afterThursday) THURSDAY.toEpochDay() else null,
                remindedEpochDay = if (afterThursday) THURSDAY.toEpochDay() else null,
                done = afterThursday,
            ),
        )
        if (afterThursday) {
            // 목요일 브리핑 끝에 말로 남긴 메모. 금요일 아침 브리핑에서 읽고 끝났다.
            list += Memo(
                id = MEMO_CAR_WASH, text = "내일 세차 예약", authorId = OWNER_ID,
                createdAtEpochMs = epochMs(2026, 11, 12, 7, 44),
                remind = MemoRemind.NextIgnition,
                briefedEpochDay = FRIDAY.toEpochDay(),
                done = true,
            )
        }
        return list
    }

    private fun letters(today: LocalDate): List<FutureLetter> {
        val list = ArrayList<FutureLetter>()
        if (!today.isBefore(BIRTHDAY_LETTER_WRITTEN)) {
            list += FutureLetter(
                id = LETTER_BIRTHDAY,
                fromIds = listOf(MOM_ID, JIWOO_ID, DOYUN_ID),
                text = LETTER_BIRTHDAY_TEXT,
                openEpochDay = BIRTHDAY.toEpochDay(),
                effect = LetterEffect(
                    color = AmbientColor.WHITE, brightness = 100, rampSeconds = 3,
                    seatHeatLevel = 3, steeringHeat = true, cabinTempC = 24f, fanLevel = 1,
                ),
                audioAsset = VOICE_BIRTHDAY_LETTER,
                openedEpochDay = if (today.isAfter(BIRTHDAY)) BIRTHDAY.toEpochDay() else null,
            )
        }
        if (!today.isBefore(NEW_CAR_LETTER_WRITTEN)) {
            list += FutureLetter(
                id = LETTER_NEW_CAR,
                fromIds = listOf(JIWOO_ID, DOYUN_ID),
                text = LETTER_NEW_CAR_TEXT,
                openEpochDay = NEW_CAR_DAY.toEpochDay(),
                effect = LetterEffect(
                    color = AmbientColor.PURPLE, brightness = 100, rampSeconds = 4,
                    seatHeatLevel = 0, steeringHeat = true,
                ),
                audioAsset = VOICE_UMBRELLA_LETTER,
                openOnNewCar = true,
            )
        }
        return list
    }

    // ───────── 기록 ─────────

    /** 기록 장소와 그곳까지의 거리(km) */
    private data class Spot(val place: Place, val km: Float)

    private val FISH_SPOTS = listOf(
        Spot(PIER, 32f),
        Spot(Place("저수지", 37.0512, 127.2231), 58f),
        Spot(Place("방파제", 37.0034, 126.4418), 96f),
        Spot(Place("갯바위", 36.7421, 126.1296), 142f),
        Spot(Place("선착장", 37.2011, 126.5123), 120f),
        Spot(Place("강 하구", 37.6102, 126.7015), 44f),
        Spot(Place("바다 좌대", 36.9105, 126.2504), 118f),
        Spot(Place("섬 방파제", 37.2507, 126.1003), 163f),
    )
    private val RUN_SPOTS = listOf(
        Spot(Place("강변", 37.5172, 127.0473), 12f),
        Spot(Place("호수공원", 37.6583, 126.7662), 18f),
        Spot(Place("숲길", 37.5621, 127.3104), 34f),
        Spot(Place("운동장 트랙", 37.4207, 127.1265), 9f),
        Spot(Place("해안 산책로", 37.3803, 126.6508), 64f),
        Spot(Place("마라톤 대회장", 37.5205, 126.9901), 27f),
    )
    private val GOLF_SPOTS = listOf(
        Spot(Place("골프장", 37.2816, 127.4429), 74f),
        Spot(Place("산 아래 골프장", 37.4902, 127.6217), 88f),
        Spot(Place("골프 연습장", 37.4113, 127.1408), 11f),
        Spot(Place("바닷가 골프장", 36.9907, 126.3711), 131f),
    )
    private val FAMILY_SPOTS = listOf(
        Spot(Place("눈썰매장", 37.6411, 127.6805), 67f),
        Spot(Place("동물원", 37.4275, 127.0170), 38f),
        Spot(Place("바닷가", 37.7906, 128.9184), 212f),
        Spot(Place("캠핑장", 37.8312, 127.5201), 85f),
        Spot(Place("계곡", 37.9408, 127.7106), 96f),
        Spot(Place("온천", 36.9902, 127.9301), 143f),
        Spot(Place("놀이공원", 37.2931, 127.2025), 52f),
        Spot(Place("박물관", 37.5238, 126.9804), 29f),
        Spot(Place("섬마을", 37.2409, 126.2713), 187f),
        Spot(Place("전망대", 37.7104, 127.0506), 73f),
        Spot(Place("목장", 37.6907, 128.7302), 158f),
    )
    private val WORK_SPOTS = listOf(
        Spot(Place("거래처", 37.5013, 127.0396), 46f),
        Spot(Place("현장 사무소", 36.8151, 127.1139), 132f),
        Spot(Place("전시장", 37.5125, 127.0588), 28f),
        Spot(Place("연수원", 37.1498, 127.9203), 104f),
        Spot(Place("지방 지사", 36.3504, 127.3845), 171f),
    )
    private val ARBORETUM_SPOT = Spot(ARBORETUM, 41f)

    private val FAMILY_IDS = listOf(MOM_ID, JIWOO_ID, DOYUN_ID, OWNER_ID)
    private val COLORS = listOf(AmbientColor.BLUE, AmbientColor.CYAN, AmbientColor.WHITE, AmbientColor.PURPLE)

    private val FISH_MONTHS = listOf(3, 4, 5, 6, 7, 8, 9, 10)
    private val RUN_MONTHS = listOf(2, 3, 4, 6, 9, 10, 11)
    private val GOLF_MONTHS = listOf(4, 5, 6, 8, 9, 10)
    private val FAMILY_MONTHS = listOf(1, 2, 5, 7, 8, 10, 12)
    private val WORK_MONTHS = listOf(2, 6, 9, 11)

    private val ARBORETUM_NOTES = listOf("올해도 왔어요", "단풍이 작년보다 곱다", "내년에도 같이 오자", "여기 오면 그날이 생각나")
    private val FAMILY_NOTES = listOf("오길 잘했다", "다음에 또 오자", "사진 많이 찍었어", "오늘 날씨 최고", "여기 음식이 맛있다", "하루가 금방 갔네")
    private val FISH_NOTES = listOf("물때가 좋았다", "입질이 뜸한 날", "손맛 제대로 봤다", "바람이 차도 좋았다", "오늘은 빈손")
    private val RUN_NOTES = listOf("가볍게 10km", "숨이 덜 찼다", "새 코스가 마음에 든다", "끝까지 걷지 않았다")
    private val GOLF_NOTES = listOf("오늘은 공이 잘 맞았다", "후반에 무너졌다", "날씨가 다 했다")
    private val WORK_NOTES = listOf("멀리 다녀온 날", "일이 잘 풀렸다", "돌아오는 길이 가벼웠다")

    /**
     * 2026~2045년의 모든 기록(날짜순). 하루에 한 건만 두고(겹치면 먼저 만든 쪽), '처음 간 곳'은 장소 이름이 처음 나온 기록에 표시한다.
     * base(today) 는 여기서 today 전의 것만 가져간다.
     */
    private val allRecords: List<DayRecord> by lazy {
        val raw = history2026() + scenarioRecords() + ((FIRST_YEAR + 1)..LAST_YEAR).flatMap { historyOfYear(it) }
        val seenPlaces = HashSet<String>()
        raw.distinctBy { it.epochDay }
            .sortedBy { it.epochDay }
            .map { it.copy(firstVisit = seenPlaces.add(it.placeName)) }
    }

    private fun sceneOf(topicId: String): SceneKind = when (topicId) {
        TOPIC_WORK -> SceneKind.WORK
        TOPIC_FISH -> SceneKind.FISHING
        TOPIC_RUN -> SceneKind.RUNNING
        TOPIC_GOLF -> SceneKind.GOLF
        TOPIC_FAMILY -> SceneKind.FAMILY_TRIP
        else -> SceneKind.GENERIC
    }

    private fun record(
        date: LocalDate,
        topicId: String,
        spot: Spot,
        seats: Int,
        note: VoiceNote? = null,
        photoBy: List<String> = emptyList(),
        color: AmbientColor? = null,
        scheduleId: String? = null,
        km: Float = spot.km,
    ): DayRecord {
        val id = RecordEngine.recordId(date.toEpochDay(), spot.place.name)
        return DayRecord(
            id = id,
            topicId = topicId,
            epochDay = date.toEpochDay(),
            placeName = spot.place.name,
            place = spot.place,
            distanceKm = km,
            firstVisit = false,   // allRecords 에서 날짜순으로 다시 매긴다
            seatCount = seats,
            scheduleId = scheduleId,
            notes = listOfNotNull(note),
            photos = photoBy.mapIndexed { index, memberId -> Photo("ph-$id-${index + 1}", sceneOf(topicId), memberId) },
            color = color,
            vin = VIN_FIRST_CAR,
        )
    }

    private fun own(text: String): VoiceNote = VoiceNote(text, OWNER_ID)

    /** 그랜저와 함께한 첫해. 시나리오 첫날(11-12) 전까지의 기록. 낚시터·수목원·회사는 아직 간 적이 없다. */
    private fun history2026(): List<DayRecord> = listOf(
        // 낚시: 여섯 번, 네 곳
        record(LocalDate.of(2026, 3, 14), TOPIC_FISH, FISH_SPOTS[1], 1, own("올해 첫 출조, 손맛은 다음에")),
        record(LocalDate.of(2026, 4, 11), TOPIC_FISH, FISH_SPOTS[2], 2, own("밤바다가 조용했다")),
        record(LocalDate.of(2026, 5, 23), TOPIC_FISH, FISH_SPOTS[1], 1),
        record(LocalDate.of(2026, 7, 18), TOPIC_FISH, FISH_SPOTS[3], 1, own("새벽 물때 맞춰 도착")),
        record(LocalDate.of(2026, 9, 5), TOPIC_FISH, FISH_SPOTS[2], 3, own("도윤이 첫 낚시")),
        record(LocalDate.of(2026, 10, 17), TOPIC_FISH, FISH_SPOTS[4], 1, own("빈손이어도 좋은 날")),
        // 러닝
        record(LocalDate.of(2026, 2, 8), TOPIC_RUN, RUN_SPOTS[0], 1, own("올해 첫 10km")),
        record(LocalDate.of(2026, 3, 29), TOPIC_RUN, RUN_SPOTS[1], 1),
        record(LocalDate.of(2026, 4, 26), TOPIC_RUN, RUN_SPOTS[0], 1),
        record(LocalDate.of(2026, 6, 7), TOPIC_RUN, RUN_SPOTS[2], 2, own("같이 뛰니 덜 힘들다")),
        record(LocalDate.of(2026, 8, 30), TOPIC_RUN, RUN_SPOTS[0], 1),
        record(LocalDate.of(2026, 10, 4), TOPIC_RUN, RUN_SPOTS[1], 1, own("하프 완주")),
        record(LocalDate.of(2026, 11, 1), TOPIC_RUN, RUN_SPOTS[0], 1, own("오늘 기록 갱신!")),
        // 골프
        record(LocalDate.of(2026, 4, 18), TOPIC_GOLF, GOLF_SPOTS[0], 4, own("친구들과 첫 라운드")),
        record(LocalDate.of(2026, 6, 20), TOPIC_GOLF, GOLF_SPOTS[0], 3),
        record(LocalDate.of(2026, 8, 22), TOPIC_GOLF, GOLF_SPOTS[1], 2, own("비 온 뒤라 공이 안 굴렀다")),
        record(LocalDate.of(2026, 10, 24), TOPIC_GOLF, GOLF_SPOTS[0], 4, own("오늘은 버디 하나")),
        // 우리 가족 여행: 가족이 한마디·사진·색을 더했다
        record(
            LocalDate.of(2026, 1, 24), TOPIC_FAMILY, FAMILY_SPOTS[0], 4,
            VoiceNote("또 타고 싶어!", DOYUN_ID), listOf(MOM_ID, JIWOO_ID), AmbientColor.WHITE,
        ),
        record(
            LocalDate.of(2026, 5, 5), TOPIC_FAMILY, FAMILY_SPOTS[1], 4,
            VoiceNote("기린이 제일 좋아", JIWOO_ID), listOf(MOM_ID, JIWOO_ID, DOYUN_ID), AmbientColor.CYAN,
        ),
        record(
            LocalDate.of(2026, 8, 1), TOPIC_FAMILY, FAMILY_SPOTS[2], 4,
            VoiceNote("여름휴가 첫날", MOM_ID), listOf(MOM_ID, MOM_ID, JIWOO_ID, DOYUN_ID), AmbientColor.BLUE,
        ),
        record(
            LocalDate.of(2026, 10, 3), TOPIC_FAMILY, FAMILY_SPOTS[3], 4,
            VoiceNote("별이 엄청 많아", DOYUN_ID), listOf(MOM_ID, JIWOO_ID), AmbientColor.PURPLE,
        ),
        // 본업: 회사가 아닌 곳으로 다녀온 날
        record(LocalDate.of(2026, 2, 3), TOPIC_WORK, WORK_SPOTS[0], 1, own("첫 계약을 따낸 날")),
        record(LocalDate.of(2026, 6, 16), TOPIC_WORK, WORK_SPOTS[1], 2, own("당일치기 출장")),
        record(LocalDate.of(2026, 9, 22), TOPIC_WORK, WORK_SPOTS[2], 1, own("신제품 발표 날")),
    )

    /** 발표 시나리오에서 차에 남긴 두 기록 */
    private fun scenarioRecords(): List<DayRecord> = listOf(
        // 토요일: 낚시터(처음 간 곳), 혼자, "올해 첫 감성돔"
        record(SATURDAY, TOPIC_FISH, FISH_SPOTS[0], 1, own(NOTE_FISHING), scheduleId = SCHEDULE_FISHING),
        // 일요일: 수목원(처음 간 곳), 넷이 함께, 지우의 한마디(녹음), 사진 3장, 보라
        record(
            SUNDAY, TOPIC_FAMILY, ARBORETUM_SPOT, 4,
            VoiceNote(NOTE_JIWOO, JIWOO_ID, VOICE_JIWOO_LAST_YEAR),
            listOf(MOM_ID, JIWOO_ID, DOYUN_ID), AmbientColor.PURPLE, SCHEDULE_FAMILY,
        ),
    )

    /** 그달의 nth 번째(1~4) 요일 */
    private fun nthWeekday(year: Int, month: Int, dayOfWeek: DayOfWeek, nth: Int): LocalDate =
        LocalDate.of(year, month, 1)
            .with(TemporalAdjusters.firstInMonth(dayOfWeek))
            .plusWeeks((nth - 1).toLong())

    /** 해마다 다른 달이 뽑히도록 목록에서 count 개를 고른다. */
    private fun pickMonths(months: List<Int>, yearIndex: Int, count: Int): List<Int> =
        months.filterIndexed { index, _ -> (index + yearIndex) % months.size < count }

    /** 해가 갈수록 갈 수 있는 곳이 늘어난다(처음 간 곳이 여러 해에 걸쳐 나오게). */
    private fun pickSpot(spots: List<Spot>, yearIndex: Int, month: Int): Spot =
        spots[(yearIndex * 3 + month) % minOf(spots.size, 4 + yearIndex / 2)]

    /** 아이들이 자라면서 함께 타는 자리가 줄어든다. */
    private fun familySeats(yearIndex: Int, month: Int): Int = when {
        yearIndex < 10 -> 4
        yearIndex < 15 -> 3
        (yearIndex + month) % 2 == 0 -> 4
        else -> 2
    }

    /** 2027~2045년 한 해의 기록. 날짜·장소·한마디는 해와 달로만 정해진다(난수 없음). */
    private fun historyOfYear(year: Int): List<DayRecord> {
        val i = year - FIRST_YEAR
        val out = ArrayList<DayRecord>()

        // 우리 가족 여행: 해마다 같은 날 수목원(그날의 색은 늘 보라)
        out += record(
            LocalDate.of(year, 11, 15), TOPIC_FAMILY, ARBORETUM_SPOT, familySeats(i, 11),
            VoiceNote(ARBORETUM_NOTES[i % ARBORETUM_NOTES.size], FAMILY_IDS[i % FAMILY_IDS.size]),
            FAMILY_IDS.take(2 + i % 3), AmbientColor.PURPLE,
        )
        for (month in pickMonths(FAMILY_MONTHS, i, 2 + i % 3)) {
            val k = i + month
            val spot = pickSpot(FAMILY_SPOTS, i, month)
            out += record(
                nthWeekday(year, month, DayOfWeek.SATURDAY, 1 + k % 4), TOPIC_FAMILY, spot, familySeats(i, month),
                if (k % 3 != 0) VoiceNote(FAMILY_NOTES[k % FAMILY_NOTES.size], FAMILY_IDS[k % FAMILY_IDS.size]) else null,
                FAMILY_IDS.take(1 + k % 4), COLORS[k % COLORS.size],
                km = spot.km + (i * 7 + month * 3) % 9,
            )
        }
        // 낚시: 토요일(같은 달의 가족 나들이와 겹치지 않게 한 주 뒤)
        for (month in pickMonths(FISH_MONTHS, i, 4 + i % 4)) {
            val k = i + month
            val spot = pickSpot(FISH_SPOTS, i, month)
            out += record(
                nthWeekday(year, month, DayOfWeek.SATURDAY, 1 + (k + 1) % 4), TOPIC_FISH, spot, if (k % 5 == 0) 2 else 1,
                if (k % 2 == 0) own(FISH_NOTES[k % FISH_NOTES.size]) else null,
                km = spot.km + (i * 7 + month * 3) % 9,
            )
        }
        // 골프: 일요일
        for (month in pickMonths(GOLF_MONTHS, i, 2 + i % 3)) {
            val k = i + month
            val spot = pickSpot(GOLF_SPOTS, i, month)
            out += record(
                nthWeekday(year, month, DayOfWeek.SUNDAY, 1 + (k + 2) % 4), TOPIC_GOLF, spot, 2 + k % 3,
                if (k % 3 == 0) own(GOLF_NOTES[k % GOLF_NOTES.size]) else null,
                km = spot.km + (i * 7 + month * 3) % 9,
            )
        }
        // 러닝: 일요일
        for (month in pickMonths(RUN_MONTHS, i, 3 + i % 3)) {
            val k = i + month
            val spot = pickSpot(RUN_SPOTS, i, month)
            out += record(
                nthWeekday(year, month, DayOfWeek.SUNDAY, 1 + k % 4), TOPIC_RUN, spot, if (k % 6 == 0) 2 else 1,
                if (k % 2 == 1) own(RUN_NOTES[k % RUN_NOTES.size]) else null,
                km = spot.km + (i * 7 + month * 3) % 9,
            )
        }
        // 본업: 회사가 아닌 곳으로 다녀온 화요일
        for (month in pickMonths(WORK_MONTHS, i, 1 + i % 2)) {
            val k = i + month
            val spot = pickSpot(WORK_SPOTS, i, month)
            out += record(
                nthWeekday(year, month, DayOfWeek.TUESDAY, 1 + k % 4), TOPIC_WORK, spot, if (k % 4 == 0) 2 else 1,
                if (k % 2 == 0) own(WORK_NOTES[k % WORK_NOTES.size]) else null,
                km = spot.km + (i * 7 + month * 3) % 9,
            )
        }
        return out
    }
}
