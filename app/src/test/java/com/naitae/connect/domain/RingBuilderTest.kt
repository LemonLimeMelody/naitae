package com.naitae.connect.domain

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.data.SeedData
import com.naitae.connect.vehicle.ScenarioDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 나이테의 모양: 1년 = 한 겹, 기록 = 그해 겹의 점 */
class RingBuilderTest {

    @Test
    fun `첫해에는 주제마다 자라는 겹이 하나 있다`() {
        val today = SeedData.dateTimeOf(ScenarioDay.WEEKDAY).toLocalDate()
        val data = SeedData.forDay(ScenarioDay.WEEKDAY)

        for (topic in data.topics) {
            val ring = RingBuilder.rings(data, topic.id, today).single()
            val count = data.records.count { it.topicId == topic.id }
            assertEquals(2026, ring.year)
            assertEquals(0, ring.index)
            assertFalse(ring.complete)
            // 11월 12일은 한 해의 316번째 날
            assertEquals(316f / 365f, ring.progress, 0.0001f)
            assertTrue(ring.thickness in 0.5f..1.5f)
            assertEquals(SeedData.VIN_FIRST_CAR, ring.vin)
            assertEquals(count, ring.scenes.size)
            assertTrue(count >= 3)
            assertTrue(ring.scenes.all { it.angleDeg >= 0f && it.angleDeg < 360f })
            assertEquals(ring.scenes.map { it.angleDeg }.sorted(), ring.scenes.map { it.angleDeg })
            assertFalse(ring.scenes.any { it.isNew })
        }

        // 색은 가족이 고른 주제에만 있다. 같은 수면 가장 최근에 고른 색.
        assertEquals(AmbientColor.PURPLE, RingBuilder.rings(data, SeedData.TOPIC_FAMILY, today).single().color)
        assertNull(RingBuilder.rings(data, SeedData.TOPIC_FISH, today).single().color)
    }

    @Test
    fun `기록이 많고 멀리 다닌 해의 겹이 더 두껍고 두께는 범위를 넘지 않는다`() {
        val today = SeedData.dateTimeOf(ScenarioDay.WEEKDAY).toLocalDate()
        val data = SeedData.forDay(ScenarioDay.WEEKDAY)
        val fish = RingBuilder.rings(data, SeedData.TOPIC_FISH, today).single()
        val work = RingBuilder.rings(data, SeedData.TOPIC_WORK, today).single()
        assertTrue(fish.thickness > work.thickness)

        // 기록이 아주 많아도 1.5 를 넘지 않는다.
        val sample = data.records.first { it.topicId == SeedData.TOPIC_FISH }
        val many = (0 until 40).map { sample.copy(id = "rec-many-$it", epochDay = sample.epochDay + it) }
        val crowded = RingBuilder.rings(data.copy(records = many), SeedData.TOPIC_FISH, today).single()
        assertEquals(1.5f, crowded.thickness, 0.0001f)
    }

    @Test
    fun `기록이 없는 주제도 올해 겹 하나를 가진다`() {
        val today = LocalDate.of(2026, 11, 12)
        val data = SeedData.forDay(ScenarioDay.WEEKDAY).copy(records = emptyList())

        val ring = RingBuilder.rings(data, SeedData.TOPIC_RUN, today).single()
        assertEquals(2026, ring.year)
        assertEquals(0.5f, ring.thickness, 0.0001f)
        assertNull(ring.color)
        assertTrue(ring.scenes.isEmpty())
        // 기록이 없는 해는 그해에 타던 차의 겹이다.
        assertEquals(SeedData.VIN_FIRST_CAR, ring.vin)

        val summary = RingBuilder.summary(data, SeedData.TOPIC_RUN, today)
        assertEquals(listOf("다녀온 날", "처음 간 곳", "함께한 날"), summary.stats.map { it.label })
        assertEquals(listOf("0일", "0곳", "0일"), summary.stats.map { it.value })
        assertNull(summary.latest)
        assertEquals(SeedData.TOPIC_RUN, summary.topic.id)
    }

    @Test
    fun `해가 바뀌면 지난해 겹은 다 자란 겹이 되고 올해 겹이 바깥에 생긴다`() {
        val today = SeedData.dateTimeOf(ScenarioDay.ONE_YEAR_LATER).toLocalDate()
        val data = SeedData.forDay(ScenarioDay.ONE_YEAR_LATER)

        val rings = RingBuilder.rings(data, SeedData.TOPIC_FAMILY, today)
        assertEquals(listOf(2026, 2027), rings.map { it.year })
        assertEquals(listOf(0, 1), rings.map { it.index })
        assertEquals(listOf(true, false), rings.map { it.complete })
        assertEquals(1f, rings[0].progress, 0.0001f)
        assertEquals(AmbientColor.PURPLE, rings[0].color)
        // 2026년 겹: 나들이 네 번 + 수목원
        assertEquals(5, rings[0].scenes.size)
        // 수목원의 점은 11월 15일 자리(319번째 날)
        assertEquals(318 * 360f / 365f, rings[0].scenes.last().angleDeg, 0.001f)
        assertTrue(rings[0].scenes.last().firstVisit)
    }

    @Test
    fun `20년 뒤 가족 나이테는 스물한 겹이고 해마다 점이 있다`() {
        val today = SeedData.dateTimeOf(ScenarioDay.TWENTY_YEARS_LATER).toLocalDate()
        val data = SeedData.forDay(ScenarioDay.TWENTY_YEARS_LATER)

        val summary = RingBuilder.summary(data, SeedData.TOPIC_FAMILY, today)
        val rings = summary.rings
        assertEquals(21, rings.size)
        assertEquals((0..20).toList(), rings.map { it.index })
        assertTrue(rings.all { it.thickness in 0.5f..1.5f })
        assertTrue(rings.dropLast(1).all { it.complete && it.progress == 1f && it.scenes.size >= 3 && it.color != null })
        assertFalse(rings.last().complete)
        // 두께와 색이 해마다 같지는 않다(나이테가 살아 보이게).
        assertTrue(rings.dropLast(1).map { it.thickness }.distinct().size > 3)
        assertTrue(rings.dropLast(1).mapNotNull { it.color }.distinct().size > 1)
        // 마지막 기록은 2045년 11월 15일의 수목원
        assertEquals(LocalDate.of(2045, 11, 15).toEpochDay(), summary.latest?.epochDay)
        assertEquals("수목원", summary.latest?.placeName)
    }
}
