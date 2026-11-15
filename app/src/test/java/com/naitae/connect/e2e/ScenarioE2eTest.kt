package com.naitae.connect.e2e

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 시나리오 날짜별 e2e 검증(ARCHITECTURE 5절 '기대 동작'). 화면 없이 실제 객체 그래프만 돌린다.
 * 걸음의 내용은 ScenarioWalks 에 있다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScenarioE2eTest {

    private lateinit var h: E2eHarness

    @Before
    fun setUp() {
        h = E2eHarness()
    }

    @After
    fun tearDown() {
        h.close()
    }

    @Test
    fun `평일 - 브리핑, 말로 메모, 조용한 도착, 퇴근길 메모`() = ScenarioWalks.weekday(h)

    @Test
    fun `토요일 - 길 안내를 묻고, 도착은 조용히, 돌아가는 시동에 남기기`() = ScenarioWalks.fishingSaturday(h)

    @Test
    fun `일요일 - 가족 나들이를 남기고 함께 꾸미면 실내 조명이 보라`() = ScenarioWalks.familySunday(h)

    @Test
    fun `1년 뒤 - 수목원에 다시 오면 다시 보기와 그날의 색`() = ScenarioWalks.oneYearLater(h)

    @Test
    fun `생일 아침 - 편지 연출과 낭독, 출발하면 조명만 원래대로`() = ScenarioWalks.birthday(h)

    @Test
    fun `20년 뒤 - 새 차 첫 시동의 이어받기와 편지`() = ScenarioWalks.twentyYearsLater(h)

    @Test
    fun `발표 순서대로 여섯 날을 이어서 걸어도 날마다 처음부터 시작한다`() {
        ScenarioWalks.weekday(h)
        ScenarioWalks.fishingSaturday(h)
        ScenarioWalks.familySunday(h)
        ScenarioWalks.oneYearLater(h)
        ScenarioWalks.birthday(h)
        ScenarioWalks.twentyYearsLater(h)
    }
}
