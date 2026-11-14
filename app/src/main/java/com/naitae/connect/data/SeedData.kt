package com.naitae.connect.data

import com.naitae.connect.core.model.Member
import com.naitae.connect.core.model.MemberRole
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Place
import com.naitae.connect.vehicle.ScenarioDay
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 가상 페르소나 데이터(실제 인물 아님): 40대 맞벌이 부부, 딸 지우(10세), 아들 도윤(6세), 그랜저.
 * 구현 예정(data 담당). 시나리오 날짜와 장소는 시뮬레이터와 함께 쓰므로 이 객체가 기준이다.
 */
object SeedData {
    const val VIN_FIRST_CAR = "KMHGRANDEUR000001"
    const val VIN_NEW_CAR = "KMHNEWCAR00000021"

    val OFFICE = Place("회사", 37.4021, 127.1086)
    val PIER = Place("낚시터", 37.2453, 126.5672)
    val ARBORETUM = Place("수목원", 37.7513, 127.1672)
    val MART = Place("마트", 37.3948, 127.1112)
    val places: List<Place> = listOf(OFFICE, PIER, ARBORETUM, MART)

    /** 시나리오 날짜별 시작 시각 */
    fun dateTimeOf(day: ScenarioDay): LocalDateTime = when (day) {
        ScenarioDay.WEEKDAY -> LocalDateTime.of(2026, 11, 12, 7, 42)            // 목요일
        ScenarioDay.FISHING_SATURDAY -> LocalDateTime.of(2026, 11, 14, 5, 3)   // 토요일
        ScenarioDay.FAMILY_SUNDAY -> LocalDateTime.of(2026, 11, 15, 10, 20)    // 일요일
        ScenarioDay.ONE_YEAR_LATER -> LocalDateTime.of(2027, 11, 15, 10, 31)
        ScenarioDay.BIRTHDAY -> LocalDateTime.of(2027, 11, 23, 7, 38)
        ScenarioDay.TWENTY_YEARS_LATER -> LocalDateTime.of(2046, 11, 15, 11, 5)
    }

    /** 그날 시동을 걸기 전까지 쌓여 있어야 할 데이터 */
    fun forDay(day: ScenarioDay): NaitaeData = base(dateTimeOf(day).toLocalDate())

    /** today 시점의 가상 페르소나 데이터 */
    fun base(today: LocalDate): NaitaeData =
        NaitaeData(owner = Member("owner", "아빠", MemberRole.OWNER))
}
