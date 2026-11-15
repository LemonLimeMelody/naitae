package com.naitae.connect.domain

import com.naitae.connect.FakeCabin
import com.naitae.connect.FakeRecordedVoice
import com.naitae.connect.FakeVoice
import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.FutureLetter
import com.naitae.connect.core.model.LetterEffect
import com.naitae.connect.data.SeedData
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.ScenarioDay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 편지 연출의 순서와 '실내 조명만 되돌리기' */
class LetterPerformerTest {
    private val parked = CabinState(
        ambientColor = AmbientColor.BLUE,
        ambientBrightness = 40,
        seatHeatLevel = 0,
        steeringHeat = false,
        cabinTempC = 22f,
        fanLevel = 2,
    )

    private fun birthdayLetter(): FutureLetter =
        SeedData.forDay(ScenarioDay.BIRTHDAY).letters.first { it.id == SeedData.LETTER_BIRTHDAY }

    @Test
    fun `온기와 공조를 먼저 켜고 조명을 단계적으로 밝힌 뒤 녹음한 목소리를 튼다`() = runTest {
        val log = ArrayList<String>()
        val cabin = FakeCabin(parked, log)
        val performer = LetterPerformer(cabin, FakeVoice(log), FakeRecordedVoice(available = true, log = log))
        val letter = birthdayLetter()

        performer.perform(letter) { log.add("phase:${it.name}") }

        val expected = listOf("phase:WARMING", "seat:3", "steering:true", "climate:24.0:1", "ambient:WHITE:0") +
            (1..10).map { "ambient:WHITE:${it * 10}" } +
            listOf("phase:READING", "play:${SeedData.VOICE_BIRTHDAY_LETTER}", "phase:DONE")
        assertEquals(expected, log)
    }

    @Test
    fun `녹음 파일이 없으면 TTS 로 읽는다`() = runTest {
        val log = ArrayList<String>()
        val performer = LetterPerformer(FakeCabin(parked, log), FakeVoice(log), FakeRecordedVoice(available = false, log = log))
        val letter = birthdayLetter()

        performer.perform(letter) { log.add("phase:${it.name}") }

        val reading = log.indexOf("phase:READING")
        assertEquals(
            listOf("phase:READING", "play:${SeedData.VOICE_BIRTHDAY_LETTER}", "speak:${letter.text}", "phase:DONE"),
            log.subList(reading, log.size),
        )
    }

    @Test
    fun `녹음이 없는 편지는 재생을 시도하지 않고 바로 읽는다`() = runTest {
        val log = ArrayList<String>()
        val performer = LetterPerformer(FakeCabin(parked, log), FakeVoice(log), FakeRecordedVoice(available = true, log = log))
        val letter = FutureLetter(
            id = "letter-test",
            fromIds = listOf(SeedData.JIWOO_ID),
            text = "고마워요.",
            openEpochDay = 0,
            effect = LetterEffect(color = AmbientColor.CYAN, brightness = 55, rampSeconds = 1),
        )

        performer.perform(letter) { log.add("phase:${it.name}") }

        assertFalse(log.any { it.startsWith("play:") })
        assertTrue(log.contains("speak:고마워요."))
        // 온기·공조를 고르지 않은 편지는 시트·핸들·공조를 건드리지 않는다.
        assertFalse(log.any { it.startsWith("seat:") || it.startsWith("steering:") || it.startsWith("climate:") })
        // 마지막 단계의 밝기가 고른 밝기다.
        assertEquals("ambient:CYAN:55", log.last { it.startsWith("ambient:") })
        assertEquals("ambient:CYAN:0", log.first { it.startsWith("ambient:") })
    }

    @Test
    fun `끝내면 실내 조명만 되돌리고 온기와 공조는 그대로 둔다`() = runTest {
        val log = ArrayList<String>()
        val cabin = FakeCabin(parked, log)
        val performer = LetterPerformer(cabin, FakeVoice(log), FakeRecordedVoice(available = true, log = log))
        performer.perform(birthdayLetter()) { }
        log.clear()

        performer.finish()

        val cabinCalls = log.filter { it.contains(":") }
        assertEquals(listOf("ambient:BLUE:40"), cabinCalls)
        assertEquals(AmbientColor.BLUE, cabin.state.value.ambientColor)
        assertEquals(40, cabin.state.value.ambientBrightness)
        assertEquals(3, cabin.state.value.seatHeatLevel)
        assertEquals(true, cabin.state.value.steeringHeat)
        assertEquals(24f, cabin.state.value.cabinTempC)
        assertEquals(1, cabin.state.value.fanLevel)

        // 두 번 불러도 더 하는 일이 없다.
        log.clear()
        performer.finish()
        assertTrue(log.isEmpty())
    }

    @Test
    fun `keepLight 면 조명을 그대로 두고 그 뒤의 finish 도 건드리지 않는다`() = runTest {
        val log = ArrayList<String>()
        val cabin = FakeCabin(parked, log)
        val performer = LetterPerformer(cabin, FakeVoice(log), FakeRecordedVoice(available = false, log = log))
        val letter = SeedData.forDay(ScenarioDay.TWENTY_YEARS_LATER).letters.first { it.id == SeedData.LETTER_NEW_CAR }
        performer.perform(letter) { }
        log.clear()

        performer.finish(keepLight = true)
        performer.finish()

        assertFalse(log.any { it.startsWith("ambient:") })
        assertEquals(AmbientColor.PURPLE, cabin.state.value.ambientColor)
        assertEquals(100, cabin.state.value.ambientBrightness)
        // 이 편지는 핸들 열선만 켠다.
        assertEquals(true, cabin.state.value.steeringHeat)
        assertEquals(0, cabin.state.value.seatHeatLevel)
    }

    @Test
    fun `연출 전의 조명 색을 모르면 편지 색으로 끈다`() = runTest {
        val log = ArrayList<String>()
        val cabin = FakeCabin(CabinState(), log)
        val performer = LetterPerformer(cabin, FakeVoice(log), FakeRecordedVoice(available = true, log = log))
        performer.perform(birthdayLetter()) { }
        log.clear()

        performer.finish()

        assertEquals(listOf("ambient:WHITE:0"), log.filter { it.startsWith("ambient:") })
    }

    @Test
    fun `연출한 적이 없으면 finish 는 아무것도 하지 않는다`() = runTest {
        val log = ArrayList<String>()
        val performer = LetterPerformer(FakeCabin(parked, log), FakeVoice(log), FakeRecordedVoice(available = true, log = log))

        performer.finish()

        assertTrue(log.isEmpty())
    }
}
