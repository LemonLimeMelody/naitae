package com.naitae.connect.data

import com.naitae.connect.core.model.MemberRole
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.vehicle.ScenarioDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** 가상 데이터가 ARCHITECTURE 5절의 표와 맞는지 */
class SeedDataTest {

    @Test
    fun `사람과 주제는 모든 날에 같다`() {
        for (day in ScenarioDay.values()) {
            val data = SeedData.forDay(day)
            assertEquals("아빠", data.owner.name)
            assertEquals(MemberRole.OWNER, data.owner.role)
            assertEquals(listOf("엄마", "지우", "도윤"), data.members.map { it.name })
            assertEquals(listOf("본업", "낚시", "러닝", "골프", "우리 가족 여행"), data.topics.map { it.name })
            assertEquals(
                listOf(SceneKind.WORK, SceneKind.FISHING, SceneKind.RUNNING, SceneKind.GOLF, SceneKind.FAMILY_TRIP),
                data.topics.map { it.scene },
            )
            assertEquals(listOf(SeedData.OFFICE), data.topic(SeedData.TOPIC_WORK)?.places)
            assertEquals(listOf(SeedData.PIER), data.topic(SeedData.TOPIC_FISH)?.places)
            assertEquals(listOf(SeedData.ARBORETUM), data.topic(SeedData.TOPIC_FAMILY)?.places)
            assertEquals(listOf("mom", "jiwoo", "doyun"), data.topic(SeedData.TOPIC_FAMILY)?.sharedWith)
            // 그랜저가 현재 차로 등록되어 있다.
            assertEquals(SeedData.VIN_FIRST_CAR, data.currentCar?.vin)
            assertEquals("그랜저", data.currentCar?.model)
            // 기록은 모두 오늘 전의 것이고 하루에 하나, id 가 겹치지 않는다.
            val today = SeedData.dateTimeOf(day).toLocalDate().toEpochDay()
            assertTrue(data.records.all { it.epochDay < today })
            assertEquals(data.records.size, data.records.map { it.id }.distinct().size)
            assertEquals(data.records.size, data.records.map { it.epochDay }.distinct().size)
            assertTrue(data.records.all { data.topic(it.topicId) != null })
        }
    }

    @Test
    fun `같은 날짜에는 늘 같은 데이터가 나온다`() {
        for (day in ScenarioDay.values()) {
            assertEquals(SeedData.forDay(day), SeedData.forDay(day))
        }
        assertEquals(SeedData.forDay(ScenarioDay.FAMILY_SUNDAY), SeedData.base(LocalDate.of(2026, 11, 15)))
    }

    @Test
    fun `평일에는 일정 둘과 엄마 메모와 다가오는 주제별 일정 둘이 있다`() {
        val data = SeedData.forDay(ScenarioDay.WEEKDAY)
        val thursday = LocalDate.of(2026, 11, 12)
        assertEquals(DayOfWeek.THURSDAY, thursday.dayOfWeek)

        val today = data.schedules.filter { it.epochDay == thursday.toEpochDay() }
        assertEquals(listOf("거래처 미팅" to "10:00", "지우 피아노 학원 픽업" to "19:00"), today.map { it.title to it.time })
        // 평일 일정에는 장소가 없다(길 안내를 묻지 않는 날).
        assertTrue(today.all { it.place == null && it.topicId == null })

        val fishing = data.schedules.first { it.id == SeedData.SCHEDULE_FISHING }
        assertEquals(LocalDate.of(2026, 11, 14).toEpochDay(), fishing.epochDay)
        assertEquals("05:00", fishing.time)
        assertEquals(SeedData.PIER, fishing.place)
        assertEquals(SeedData.TOPIC_FISH, fishing.topicId)
        val family = data.schedules.first { it.id == SeedData.SCHEDULE_FAMILY }
        assertEquals(LocalDate.of(2026, 11, 15).toEpochDay(), family.epochDay)
        assertEquals(SeedData.ARBORETUM, family.place)
        assertEquals(SeedData.TOPIC_FAMILY, family.topicId)

        val memo = data.memos.single()
        assertEquals("퇴근길에 우유 사 오기", memo.text)
        assertEquals(SeedData.MOM_ID, memo.authorId)
        assertEquals(MemoRemind.EveningIgnition(17), memo.remind)
        assertNull(memo.briefedEpochDay)
        assertFalse(memo.done)

        assertNull(data.lastBriefingEpochDay)
        assertTrue(data.letters.isEmpty())

        // 올해의 기록: 낚시 여섯 번(낚시터는 아직), 러닝, 골프, 가족 나들이 몇 번(수목원은 아직), 회사 기록은 없음
        assertEquals(6, data.records.count { it.topicId == SeedData.TOPIC_FISH })
        assertTrue(data.records.count { it.topicId == SeedData.TOPIC_RUN } >= 3)
        assertTrue(data.records.count { it.topicId == SeedData.TOPIC_GOLF } >= 3)
        assertTrue(data.records.count { it.topicId == SeedData.TOPIC_FAMILY } >= 3)
        assertFalse(data.records.any { it.placeName in listOf("낚시터", "수목원", "회사", "마트") })
        assertTrue(data.records.all { LocalDate.ofEpochDay(it.epochDay).year == 2026 })
    }

    @Test
    fun `뒤의 날은 앞의 날에 쌓였을 것을 품는다`() {
        val weekday = SeedData.forDay(ScenarioDay.WEEKDAY)
        val saturday = SeedData.forDay(ScenarioDay.FISHING_SATURDAY)
        val sunday = SeedData.forDay(ScenarioDay.FAMILY_SUNDAY)
        val nextYear = SeedData.forDay(ScenarioDay.ONE_YEAR_LATER)
        val birthday = SeedData.forDay(ScenarioDay.BIRTHDAY)
        val twenty = SeedData.forDay(ScenarioDay.TWENTY_YEARS_LATER)

        // 토요일: 목요일 브리핑이 끝났고 우유 메모도 끝났다. 기록은 그대로.
        assertEquals(weekday.records, saturday.records)
        assertTrue(saturday.lastBriefingEpochDay!! >= LocalDate.of(2026, 11, 12).toEpochDay())
        val milk = saturday.memos.first { it.id == SeedData.MEMO_MILK }
        assertTrue(milk.done)
        assertEquals(LocalDate.of(2026, 11, 12).toEpochDay(), milk.remindedEpochDay)
        assertEquals("내일 세차 예약", saturday.memos.first { it.id == SeedData.MEMO_CAR_WASH }.text)

        // 일요일: 토요일 낚시 기록 하나가 더해진다.
        assertEquals(weekday.records.size + 1, sunday.records.size)
        val fishing = sunday.records.last()
        assertEquals("낚시터", fishing.placeName)
        assertEquals(listOf("올해 첫 감성돔"), fishing.notes.map { it.text })

        // 1년 뒤: 수목원 기록이 있고(지우의 한마디·보라·사진 3장·4석), 오늘 일정이 수목원이다.
        assertTrue(nextYear.records.containsAll(sunday.records))
        val arboretum = nextYear.records.single { it.placeName == "수목원" }
        assertEquals(LocalDate.of(2026, 11, 15).toEpochDay(), arboretum.epochDay)
        assertEquals("아빠, 내년에 또 오자!", arboretum.notes.single().text)
        assertEquals("voice/jiwoo_last_year.mp3", arboretum.notes.single().audioAsset)
        assertEquals(3, arboretum.photos.size)
        assertEquals(4, arboretum.seatCount)
        assertTrue(arboretum.firstVisit)
        assertEquals(
            listOf(SeedData.ARBORETUM),
            nextYear.schedules.filter { it.epochDay == LocalDate.of(2027, 11, 15).toEpochDay() }.map { it.place },
        )

        // 생일: 편지가 오늘 열린다. 1년 뒤의 날에는 아직 열 날이 아니다.
        assertEquals(LocalDate.of(2027, 11, 23).toEpochDay(), birthday.letters.single().openEpochDay)
        assertNull(birthday.letters.single().openedEpochDay)
        assertTrue(birthday.records.containsAll(nextYear.records))

        // 20년 뒤: 생일 편지는 열렸고 새 차 편지가 기다린다. 앞선 기록을 모두 품는다.
        assertEquals(listOf(SeedData.LETTER_BIRTHDAY, SeedData.LETTER_NEW_CAR), twenty.letters.map { it.id })
        assertEquals(LocalDate.of(2027, 11, 23).toEpochDay(), twenty.letters[0].openedEpochDay)
        assertTrue(twenty.letters[1].openOnNewCar)
        assertNull(twenty.letters[1].openedEpochDay)
        assertTrue(twenty.records.containsAll(birthday.records))
        assertTrue(twenty.schedules.none { it.epochDay == LocalDate.of(2046, 11, 15).toEpochDay() })
    }

    @Test
    fun `처음 간 곳은 장소마다 가장 이른 기록 하나뿐이다`() {
        val data = SeedData.forDay(ScenarioDay.TWENTY_YEARS_LATER)
        for ((_, records) in data.records.groupBy { it.placeName }) {
            val sorted = records.sortedBy { it.epochDay }
            assertTrue(sorted.first().firstVisit)
            assertTrue(sorted.drop(1).none { it.firstVisit })
        }
        // 20년 동안 주말·화요일에 다닌 기록이다.
        assertTrue(data.records.size > 200)
    }
}
