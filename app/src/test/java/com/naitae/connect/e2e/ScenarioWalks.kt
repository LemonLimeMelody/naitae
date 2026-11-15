package com.naitae.connect.e2e

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.data.SeedData
import com.naitae.connect.domain.BriefingEngine
import com.naitae.connect.domain.BriefingKind
import com.naitae.connect.domain.LetterPhase
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.ScenarioDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * ARCHITECTURE 5절 '기대 동작'을 날짜별로 처음부터 끝까지 걷는다.
 * 차 쪽 입력은 가상 콘솔로만, 사람의 입력은 NaitaeActions 로만 넣고, 화면 순서와 차·데이터의 결과를 확인한다.
 * 같은 걸음을 ScenarioE2eTest(화면 없이)와 ScreenshotE2eTest(실제 화면 + 캡처)가 함께 쓴다.
 */
internal object ScenarioWalks {

    /** 시뮬레이터에서 시동을 걸면 실내는 차주의 평소 설정으로 시작한다(SimCabin). */
    val USUAL_CABIN = CabinState(
        ambientColor = AmbientColor.WHITE,
        ambientBrightness = 30,
        seatHeatLevel = 0,
        steeringHeat = false,
        cabinTempC = 22f,
        fanLevel = 0,
    )

    private const val MEMO_SAID = "내일 세차 예약"
    private const val FISHING_SAID = "올해 첫 감성돔"
    private const val JIWOO_SAID = "아빠, 내년에 또 오자!"

    // ───────── 평일: 브리핑 → 말로 메모 → 조용한 도착 → 퇴근길 메모 ─────────

    fun weekday(h: E2eHarness) {
        h.startDay(ScenarioDay.WEEKDAY)
        h.shot("01_weekday_engine_off")
        val today = h.coordinator.today().toEpochDay()
        val memosBefore = h.data.memos.size

        // 하루 첫 시동 → 브리핑
        var mark = h.mark()
        h.ignition(true)
        val first = h.screenAs<Screen.Briefing>()
        assertEquals("인사말부터", -1, first.activeIndex)
        assertEquals("좋은 아침이에요.", h.ui.voiceLine)
        val items = first.briefing.items
        assertEquals(
            "미팅 · 픽업 · 엄마 메모 · 낚시 D-2 · 가족 나들이 D-3",
            listOf(
                BriefingKind.SCHEDULE to "일정",
                BriefingKind.SCHEDULE to "일정",
                BriefingKind.MEMO to "메모",
                BriefingKind.DDAY to "D-2",
                BriefingKind.DDAY to "D-3",
            ),
            items.map { it.kind to it.tag },
        )
        assertEquals("10:00 거래처 미팅", items[0].title)
        assertEquals("19:00 지우 피아노 학원 픽업", items[1].title)
        assertEquals("엄마: 퇴근길에 우유 사 오기", items[2].title)
        assertTrue(items[3].title, items[3].title.contains("낚시"))
        assertTrue(items[4].title, items[4].title.contains("가족 나들이"))

        h.awaitThat("엄마 메모 줄을 읽음") { (h.screen as? Screen.Briefing)?.activeIndex == 2 }
        h.shot("02_weekday_briefing")
        h.awaitThat("메모할 것 있으세요? 뒤에 들음") { (h.screen as? Screen.Briefing)?.listening == true }
        h.shot("03_weekday_briefing_listening", settleMs = 1_300)
        h.awaitThat("말한 메모를 남김") { (h.screen as? Screen.Briefing)?.savedMemo != null }
        assertEquals(MEMO_SAID, h.screenAs<Screen.Briefing>().savedMemo?.text)
        h.awaitScreen<Screen.Home>()

        assertEquals(listOf("Briefing", "Home"), h.screensSince(mark))
        assertEquals(
            "읽는 줄이 하나씩 넘어감(-1 인사 … 5 메모 질문)",
            listOf(-1, 0, 1, 2, 3, 4, 5),
            h.seenSince(mark) { (it.screen as? Screen.Briefing)?.activeIndex },
        )
        assertEquals(
            listOf("좋은 아침이에요.") + items.map { it.spoken } + listOf(BriefingEngine.ASK_MEMO, "메모했어요."),
            h.voiceLinesSince(mark),
        )
        val heard = h.seenSince(mark) { (it.screen as? Screen.Briefing)?.heardText?.takeIf { text -> text.isNotEmpty() } }
        assertTrue("받아 적는 글이 한 자씩 늘어남: $heard", heard.size > 3 && heard.zipWithNext().all { (a, b) -> b.startsWith(a) })
        assertEquals(MEMO_SAID, heard.last())

        assertEquals(memosBefore + 1, h.data.memos.size)
        val memo = h.data.memos.last()
        assertEquals(MEMO_SAID, memo.text)
        assertEquals(h.data.owner.id, memo.authorId)
        assertEquals(MemoRemind.NextIgnition, memo.remind)
        assertEquals("오늘 브리핑을 마쳤다고 기록", today, h.data.lastBriefingEpochDay)
        assertEquals("브리핑에서 읽은 메모로 기록", today, h.data.memos.first { it.id == SeedData.MEMO_MILK }.briefedEpochDay)
        assertNull(h.ui.memoToast)
        assertNull(h.ui.voiceLine)
        h.shot("04_weekday_home")

        // 출발 → 회사 도착: 평소 도착이라 화면이 바뀌지 않는다
        val records = h.data.records
        mark = h.mark()
        h.shift(GearState.DRIVE)
        assertTrue(h.ui.driving)
        h.shot("05_weekday_driving", settleMs = 1_000)
        h.driveTo("회사")
        h.advance(3_000)
        assertEquals("평소 도착은 조용히", listOf("Home"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertNull(h.ui.memoToast)
        assertTrue(h.logged("회사 평소 도착 → 화면 없음"))
        assertNull("평소 도착은 기록 초안도 만들지 않음", h.coordinator.tracker.pendingDraft)
        assertEquals(records, h.data.records)

        // 퇴근길 18:40 시동 → 엄마 메모 카드 + 낭독, 브리핑은 다시 하지 않는다
        mark = h.restartAt(18, 40)
        h.awaitThat("메모 카드") { h.ui.memoToast != null }
        assertEquals("퇴근길에 우유 사 오기", h.ui.memoToast?.text)
        assertEquals("엄마", h.ui.memoToastAuthor)
        assertEquals("엄마 메모예요. 우유 사기.", h.ui.voiceLine)
        assertEquals("18:40", h.ui.clockLabel)
        h.shot("06_weekday_evening_memo")
        h.awaitThat("메모 낭독 끝") { h.ui.voiceLine == null }
        h.advance(1_000)
        assertEquals("두 번째 브리핑은 없음", listOf("Home"), h.screensSince(mark))
        assertEquals(listOf("엄마 메모예요. 우유 사기."), h.voiceLinesSince(mark))
        assertEquals(today, h.data.memos.first { it.id == SeedData.MEMO_MILK }.remindedEpochDay)
        h.advance(13_000)
        assertNull("메모 카드는 잠깐 떴다 사라짐", h.ui.memoToast)

        // 같은 저녁에 다시 시동을 걸어도 한 번 알린 메모는 다시 읽지 않는다
        h.ignition(false)
        h.advance(1_000)
        mark = h.mark()
        h.ignition(true)
        h.advance(5_000)
        assertEquals(listOf("Home"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertNull(h.ui.memoToast)
        h.ignition(false)
    }

    // ───────── 토요일: 브리핑 → 길 안내를 묻고 → 처음 간 곳 → 돌아가는 시동에 남기기 ─────────

    fun fishingSaturday(h: E2eHarness) {
        h.startDay(ScenarioDay.FISHING_SATURDAY)
        val recordsBefore = h.data.records.size
        val memosBefore = h.data.memos

        var mark = h.mark()
        h.ignition(true)
        val briefing = h.screenAs<Screen.Briefing>().briefing
        assertEquals("05:00 낚시 · 낚시터", briefing.items.first().title)
        assertEquals(SeedData.SCHEDULE_FISHING, briefing.items.first().scheduleId)
        h.awaitThat("오늘 낚시 일정을 읽음") { (h.screen as? Screen.Briefing)?.activeIndex == 0 }
        h.shot("07_fishing_briefing")

        // 브리핑이 끝나면 길 안내를 '묻기만' 한다
        val ask = h.awaitScreen<Screen.RouteAsk>()
        assertEquals(listOf("Briefing", "RouteAsk"), h.screensSince(mark).filter { it != "Home" })
        assertEquals(SeedData.SCHEDULE_FISHING, ask.schedule.id)
        assertEquals("낚시터", ask.schedule.place?.name)
        assertEquals(SeedData.TOPIC_FISH, ask.topic?.id)
        assertEquals("오늘은 낚시예요. 낚시터로 안내할까요?", h.ui.voiceLine)
        assertEquals("아침에는 남길 말이 없어 메모가 늘지 않음", memosBefore, h.data.memos)
        h.shot("08_fishing_route_ask")
        h.advance(8_000)
        h.screenAs<Screen.RouteAsk>()
        assertNull("답하기 전에는 길 안내를 시작하지 않음", h.console.state.value.guidanceTo)
        assertFalse(h.logged("NaviHelper.requestRoute"))

        // 안내해 줘
        h.tap { acceptRoute() }
        h.screenAs<Screen.Home>()
        assertEquals("낚시터", h.console.state.value.guidanceTo?.name)
        assertTrue(h.logged("NaviHelper.requestRoute(낚시터)"))

        // 낚시터 도착: 처음 간 곳이지만 도착할 때는 묻지 않는다
        mark = h.mark()
        h.driveTo("낚시터")
        h.advance(3_000)
        assertEquals("도착할 때는 묻지 않음", listOf("Home"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertNull(h.console.state.value.guidanceTo)
        val draft = h.coordinator.tracker.pendingDraft
        assertNotNull("기록 초안만 조용히 준비", draft)
        assertEquals(recordsBefore, h.data.records.size)
        h.shot("09_fishing_arrived_quiet")

        // 11:40 돌아가려 시동 → "오늘을 남길까요?"
        h.restartAt(11, 40)
        val recordAsk = h.screenAs<Screen.RecordAsk>()
        assertEquals("오늘 낚시터, 어땠어요? 오늘을 남길까요?", h.ui.voiceLine)
        assertEquals("낚시터", recordAsk.draft.placeName)
        assertEquals(SeedData.TOPIC_FISH, recordAsk.draft.topicId)
        assertEquals(SeedData.TOPIC_FISH, recordAsk.topic?.id)
        assertTrue("처음 간 곳", recordAsk.draft.firstVisit)
        assertEquals(32f, recordAsk.draft.distanceKm, 0.5f)
        assertEquals(1, recordAsk.draft.seatCount)
        h.shot("10_fishing_record_ask")

        // 남길게요 → 남긴 기록
        h.tap { acceptRecord(SeedData.TOPIC_FISH) }
        val edit = h.screenAs<Screen.RecordEdit>()
        assertEquals(recordsBefore + 1, h.data.records.size)
        assertEquals("낚시터", edit.record.placeName)
        assertEquals(SeedData.TOPIC_FISH, edit.record.topicId)
        assertEquals(SeedData.VIN_FIRST_CAR, edit.record.vin)
        assertTrue(edit.record.notes.isEmpty())
        assertTrue("방금 남긴 점이 나이테에 강조됨", edit.summary.rings.any { ring -> ring.scenes.any { it.recordId == edit.record.id && it.isNew } })
        h.shot("11_fishing_record_edit")

        // 한마디를 말로 더한다
        mark = h.mark()
        h.tap { startVoiceNote() }
        assertTrue(h.screenAs<Screen.RecordEdit>().listening)
        h.shot("12_fishing_record_listening", settleMs = 1_100)
        h.awaitThat("한마디가 기록에 붙음") { (h.screen as? Screen.RecordEdit)?.record?.notes?.isNotEmpty() == true }
        h.advance(200)
        val noted = h.screenAs<Screen.RecordEdit>()
        assertEquals(listOf(FISHING_SAID), noted.record.notes.map { it.text })
        assertNull("누가 말했는지는 저장하지 않음", noted.record.notes.single().authorId)
        assertFalse(noted.listening)
        assertEquals("", noted.heardText)
        assertEquals(FISHING_SAID, h.seenSince(mark) { (it.screen as? Screen.RecordEdit)?.heardText?.takeIf { t -> t.isNotEmpty() } }.last())
        h.shot("13_fishing_record_noted")

        // 마치면 낚시 나이테가 보이는 홈
        h.tap { finishRecord() }
        val home = h.screenAs<Screen.Home>()
        assertEquals(SeedData.TOPIC_FISH, home.selected?.topic?.id)
        assertEquals(noted.record.id, home.selected?.latest?.id)
        assertEquals(recordsBefore + 1, h.data.records.size)
        assertNull(h.coordinator.tracker.pendingDraft)
        h.shot("14_fishing_home")
        h.ignition(false)
    }

    // ───────── 일요일: 가족 나들이 → 남기기 → 지우의 한마디 → 함께 꾸미기(실내 조명) ─────────

    fun familySunday(h: E2eHarness) {
        h.startDay(ScenarioDay.FAMILY_SUNDAY)
        assertEquals("가족 넷이 탑승", 4, h.console.state.value.seats.count)
        val recordsBefore = h.data.records.size

        var mark = h.mark()
        h.ignition(true)
        h.screenAs<Screen.Briefing>()
        val ask = h.awaitScreen<Screen.RouteAsk>()
        assertEquals(listOf("Briefing", "RouteAsk"), h.screensSince(mark).filter { it != "Home" })
        assertEquals("수목원", ask.schedule.place?.name)
        assertEquals("오늘은 가족 나들이예요. 수목원으로 안내할까요?", h.ui.voiceLine)
        assertNull(h.console.state.value.guidanceTo)
        h.shot("15_family_route_ask")
        h.tap { acceptRoute() }
        assertEquals("수목원", h.console.state.value.guidanceTo?.name)

        mark = h.mark()
        h.driveTo("수목원")
        h.advance(3_000)
        assertEquals("도착할 때는 묻지 않음", listOf("Home"), h.screensSince(mark))

        // 16:30 돌아가려 시동 → 남기기
        h.restartAt(16, 30)
        val recordAsk = h.screenAs<Screen.RecordAsk>()
        assertEquals("수목원", recordAsk.draft.placeName)
        assertEquals(SeedData.TOPIC_FAMILY, recordAsk.draft.topicId)
        assertEquals("좌석 수만 남김", 4, recordAsk.draft.seatCount)
        assertEquals(41f, recordAsk.draft.distanceKm, 0.5f)
        h.shot("16_family_record_ask")
        h.tap { acceptRecord(SeedData.TOPIC_FAMILY) }
        val recordId = h.screenAs<Screen.RecordEdit>().record.id
        assertEquals(recordsBefore + 1, h.data.records.size)

        // 지우의 한마디
        h.tap { startVoiceNote() }
        h.awaitThat("한마디가 기록에 붙음") { (h.screen as? Screen.RecordEdit)?.record?.notes?.isNotEmpty() == true }
        h.advance(200)
        assertEquals(listOf(JIWOO_SAID), h.screenAs<Screen.RecordEdit>().record.notes.map { it.text })
        h.shot("17_family_record_noted")

        // 함께 꾸미기: 사진, 색 → 실내 조명
        assertEquals("꾸미기 전 실내 조명", USUAL_CABIN, h.cabin)
        h.tap { openDecorate() }
        val decorate = h.screenAs<Screen.Decorate>()
        assertEquals(recordId, decorate.record.id)
        assertEquals(listOf("엄마", "지우", "도윤"), decorate.members.map { it.name })
        assertEquals(AmbientColor.values().toSet(), decorate.colors.toSet())
        h.shot("18_family_decorate")

        h.tap { addPhoto() }
        assertEquals(1, h.screenAs<Screen.Decorate>().record.photos.size)
        h.tap { pickColor(AmbientColor.PURPLE) }
        assertEquals(AmbientColor.PURPLE, h.screenAs<Screen.Decorate>().record.color)
        assertEquals("고른 색이 실내 조명이 됨", AmbientColor.PURPLE, h.cabin.ambientColor)
        assertEquals(AmbientColor.PURPLE, h.ui.cabin.ambientColor)
        assertTrue("실내 조명이 밝아짐", (h.cabin.ambientBrightness ?: 0) > 30)
        h.shot("19_family_decorate_purple")

        // 꾸미기를 마치면 조명은 원래대로, 남긴 기록 화면으로
        h.tap { finishDecorate() }
        assertEquals("실내 조명만 원래대로", USUAL_CABIN, h.cabin)
        val edited = h.screenAs<Screen.RecordEdit>()
        assertEquals(AmbientColor.PURPLE, edited.record.color)
        assertEquals(1, edited.record.photos.size)
        h.shot("20_family_record_decorated")

        h.tap { finishRecord() }
        val home = h.screenAs<Screen.Home>()
        assertEquals(SeedData.TOPIC_FAMILY, home.selected?.topic?.id)
        assertEquals(recordId, home.selected?.latest?.id)
        val saved = h.data.records.first { it.id == recordId }
        assertEquals(AmbientColor.PURPLE, saved.color)
        assertEquals(listOf(JIWOO_SAID), saved.notes.map { it.text })
        assertEquals(4, saved.seatCount)
        h.shot("21_family_home")
        h.ignition(false)
    }

    // ───────── 1년 뒤: 같은 곳에 다시 오면 "1년 전 오늘, 여기서" ─────────

    fun oneYearLater(h: E2eHarness) {
        h.startDay(ScenarioDay.ONE_YEAR_LATER)
        val lastYear = h.data.records.single { it.placeName == "수목원" }
        assertEquals(AmbientColor.PURPLE, lastYear.color)
        val recordsBefore = h.data.records

        var mark = h.mark()
        h.ignition(true)
        h.screenAs<Screen.Briefing>()
        val ask = h.awaitScreen<Screen.RouteAsk>()
        assertEquals(listOf("Briefing", "RouteAsk"), h.screensSince(mark).filter { it != "Home" })
        assertEquals("수목원", ask.schedule.place?.name)
        h.tap { acceptRoute() }
        assertEquals(USUAL_CABIN, h.cabin)

        // 수목원에 다시 도착 → 다시 보기가 열리고 그날의 색·목소리
        mark = h.mark()
        h.driveTo("수목원")
        val view = h.screenAs<Screen.RecallView>()
        assertEquals(listOf("Home", "RecallView"), h.screensSince(mark))
        assertEquals(lastYear.id, view.recall.record.id)
        assertEquals(1, view.recall.yearsAgo)
        assertEquals("1년 전 오늘, 여기서", view.recall.title)
        assertEquals(SeedData.TOPIC_FAMILY, view.recall.topic.id)
        h.awaitThat("그날의 색으로 실내 조명") { h.cabin.ambientColor == AmbientColor.PURPLE }
        assertTrue((h.cabin.ambientBrightness ?: 0) > 30)
        h.awaitThat("그날의 목소리") { h.ui.voiceLine == JIWOO_SAID }
        assertTrue(h.screenAs<Screen.RecallView>().playingVoice)
        h.shot("22_oneyear_recall")
        h.awaitThat("목소리 끝") { h.ui.voiceLine == null }
        h.advance(300)
        assertFalse(h.screenAs<Screen.RecallView>().playingVoice)
        assertEquals("다시 보기는 닫기 전까지 그대로", AmbientColor.PURPLE, h.cabin.ambientColor)

        // 다시 듣기
        h.tap { playRecallVoice() }
        assertEquals(JIWOO_SAID, h.ui.voiceLine)
        assertTrue(h.screenAs<Screen.RecallView>().playingVoice)

        // 닫으면 조명은 원래대로
        h.tap { closeRecall() }
        val home = h.screenAs<Screen.Home>()
        assertNull("닫으면 낭독도 멈춤", h.ui.voiceLine)
        assertEquals(USUAL_CABIN, h.cabin)
        assertEquals(SeedData.TOPIC_FAMILY, home.selected?.topic?.id)
        assertEquals("다시 보기는 기록을 바꾸지 않음", recordsBefore, h.data.records)
        assertNull("다시 보기가 열린 도착은 초안을 만들지 않음", h.coordinator.tracker.pendingDraft)
        h.shot("23_oneyear_home")
        h.ignition(false)
    }

    // ───────── 생일 아침: 편지 도착 → 열기 → 온기·조명·낭독 → 출발하면 조명만 원래대로 ─────────

    fun birthday(h: E2eHarness) {
        h.startDay(ScenarioDay.BIRTHDAY)
        val letter = h.data.letters.single { it.id == SeedData.LETTER_BIRTHDAY }
        assertNull(letter.openedEpochDay)

        var mark = h.mark()
        h.ignition(true)
        val arrive = h.screenAs<Screen.LetterArrive>()
        assertEquals(letter.id, arrive.letter.id)
        assertEquals("엄마 · 지우 · 도윤", arrive.fromNames)
        assertEquals("편지가 도착했어요.", h.ui.voiceLine)
        assertEquals("열기 전에는 실내를 건드리지 않음", USUAL_CABIN, h.cabin)
        h.shot("24_birthday_letter_arrive")
        h.advance(5_000)
        h.screenAs<Screen.LetterArrive>()
        assertEquals(USUAL_CABIN, h.cabin)
        assertEquals(listOf("LetterArrive"), h.screensSince(mark))

        // 열기 → 온기, 조명이 서서히, 낭독
        mark = h.mark()
        val openedAt = h.nowMs()
        h.tap { openLetter() }
        val open = h.screenAs<Screen.LetterOpen>()
        assertEquals(LetterPhase.WARMING, open.phase)
        assertEquals("", open.shownText)
        assertNull(open.rings)
        assertEquals(3, h.cabin.seatHeatLevel)
        assertEquals(true, h.cabin.steeringHeat)
        assertEquals(24f, h.cabin.cabinTempC)
        assertEquals(1, h.cabin.fanLevel)
        assertEquals(AmbientColor.WHITE, h.cabin.ambientColor)
        assertEquals("조명은 꺼진 밝기에서 시작", 0, h.cabin.ambientBrightness)
        h.shot("25_birthday_letter_warming", settleMs = 1_500)
        h.awaitThat("낭독 시작") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.READING }
        assertEquals("3초에 걸쳐 밝아진 뒤 낭독", 3_000f, (h.nowMs() - openedAt).toFloat(), 100f)
        assertEquals(100, h.cabin.ambientBrightness)
        h.shot("26_birthday_letter_reading", settleMs = 1_200)
        h.awaitThat("낭독 끝") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.DONE }
        h.advance(500)
        val done = h.screenAs<Screen.LetterOpen>()
        assertEquals(letter.text, done.shownText)

        assertEquals(listOf("LetterOpen"), h.screensSince(mark))
        assertEquals(
            listOf(LetterPhase.WARMING, LetterPhase.READING, LetterPhase.DONE),
            h.seenSince(mark) { (it.screen as? Screen.LetterOpen)?.phase },
        )
        assertEquals(
            "조명이 0 에서 100 까지 열 단계로 밝아짐",
            (0..100 step 10).toList(),
            h.seenSince(mark) { it.cabin.ambientBrightness },
        )
        val shown = h.seenSince(mark) { (it.screen as? Screen.LetterOpen)?.shownText }
        assertTrue("글자가 한 자씩 늘어남", shown.size > 10 && shown.zipWithNext().all { (a, b) -> b.length > a.length && b.startsWith(a) })
        assertEquals(letter.text, shown.last())
        assertTrue("WARMING 동안에는 글자를 보여 주지 않음", h.history.drop(mark).none {
            val s = it.screen as? Screen.LetterOpen
            s != null && s.phase == LetterPhase.WARMING && s.shownText.isNotEmpty()
        })
        h.shot("27_birthday_letter_done")
        h.advance(4_000)
        h.screenAs<Screen.LetterOpen>()

        // 출발: 실내 조명만 원래대로, 온기와 공조는 그대로
        h.shift(GearState.DRIVE)
        h.advance(500)
        assertTrue(h.ui.driving)
        h.screenAs<Screen.Home>()
        assertEquals(AmbientColor.WHITE, h.cabin.ambientColor)
        assertEquals("조명은 편지 전 밝기로", 30, h.cabin.ambientBrightness)
        assertEquals("시트 온기는 그대로", 3, h.cabin.seatHeatLevel)
        assertEquals("핸들 온기는 그대로", true, h.cabin.steeringHeat)
        assertEquals(24f, h.cabin.cabinTempC)
        assertEquals(1, h.cabin.fanLevel)
        assertEquals("열어 본 편지로 기록", h.coordinator.today().toEpochDay(), h.data.letters.single { it.id == letter.id }.openedEpochDay)

        // 한 번 연 편지는 다음 시동에 다시 오지 않는다(대신 그날의 브리핑이 이어진다)
        h.shift(GearState.PARK)
        h.restartAt(7, 50)
        assertFalse(h.screen is Screen.LetterArrive || h.screen is Screen.LetterOpen)
        h.ignition(false)
    }

    // ───────── 20년 뒤: 새 차 첫 시동 → 이어받기 → 편지 → "새 차 0km · 나이테 스무 겹" ─────────

    fun twentyYearsLater(h: E2eHarness) {
        h.startDay(ScenarioDay.TWENTY_YEARS_LATER)
        assertEquals(SeedData.VIN_NEW_CAR, h.console.state.value.vin)
        assertEquals(0f, h.console.state.value.odometerKm)
        assertEquals("계정에는 아직 그랜저가 현재 차", SeedData.VIN_FIRST_CAR, h.data.currentCar?.vin)
        val before = h.data
        val letter = before.letters.single { it.id == SeedData.LETTER_NEW_CAR }

        val mark = h.mark()
        val startedAt = h.nowMs()
        h.ignition(true)
        // 이어받기는 조용히 끝나고, 바로 편지 연출이 시작된다
        val open = h.screenAs<Screen.LetterOpen>()
        assertEquals(SeedData.VIN_NEW_CAR, h.data.currentCar?.vin)
        assertEquals(2, h.data.cars.size)
        assertNotNull("그랜저는 보낸 차로", h.data.cars.first { it.vin == SeedData.VIN_FIRST_CAR }.retiredEpochDay)
        assertEquals("기록·일정·메모는 그대로 이어짐", before.records, h.data.records)
        assertEquals(before.schedules, h.data.schedules)
        assertEquals(before.memos, h.data.memos)
        assertEquals("새 현대차", h.ui.carLabel)
        assertEquals(letter.id, open.letter.id)
        assertEquals("지우 · 도윤", open.fromNames)
        assertEquals(LetterPhase.WARMING, open.phase)
        assertNotNull("이어받는 나이테와 함께", open.rings)
        assertEquals(true, h.cabin.steeringHeat)
        assertEquals("시트 열선은 켜지 않는 편지", 0, h.cabin.seatHeatLevel)
        assertEquals(AmbientColor.PURPLE, h.cabin.ambientColor)
        assertEquals(0, h.cabin.ambientBrightness)
        h.shot("28_twenty_letter_warming", settleMs = 2_400)
        h.awaitThat("낭독 시작") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.READING }
        assertEquals("4초에 걸쳐 보랏빛이 밝아진 뒤 낭독", 4_000f, (h.nowMs() - startedAt).toFloat(), 100f)
        assertEquals(100, h.cabin.ambientBrightness)
        h.shot("29_twenty_letter_reading", settleMs = 3_000)
        h.awaitThat("낭독 끝") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.DONE }
        h.advance(500)
        assertEquals(letter.text, h.screenAs<Screen.LetterOpen>().shownText)
        assertEquals((0..100 step 10).toList(), h.seenSince(mark) { it.cabin.ambientBrightness })
        assertEquals(
            "편지 내내 보랏빛",
            listOf(AmbientColor.PURPLE),
            h.seenSince(mark) { state -> state.cabin.ambientColor.takeIf { state.screen is Screen.LetterOpen } },
        )
        h.shot("30_twenty_letter_done")

        // 편지를 닫으면 이어받기 화면
        h.tap { closeLetter() }
        val handover = h.screenAs<Screen.Handover>()
        assertEquals("나이테 스무 겹", 20, handover.summary.ringCount)
        assertEquals("새 차 0km", 0f, handover.newCarOdometerKm)
        assertEquals(SeedData.VIN_NEW_CAR, handover.summary.toVin)
        assertEquals(SeedData.VIN_FIRST_CAR, handover.summary.fromCar?.vin)
        assertEquals(before.records.size, handover.summary.recordCount)
        assertNotNull(handover.rings)
        assertEquals(20, handover.rings?.rings?.count { it.complete })
        assertEquals("새 차 첫 시동의 보랏빛은 그대로", AmbientColor.PURPLE, h.cabin.ambientColor)
        assertEquals(h.coordinator.today().toEpochDay(), h.data.letters.single { it.id == letter.id }.openedEpochDay)
        h.shot("31_twenty_handover")

        h.tap { dismissHandover() }
        h.screenAs<Screen.Home>()
        assertEquals(listOf("LetterOpen", "Handover", "Home"), h.screensSince(mark))
        assertEquals("이어받기 화면을 닫으면 조명만 편지 전으로", AmbientColor.WHITE, h.cabin.ambientColor)
        assertEquals(30, h.cabin.ambientBrightness)
        assertEquals("핸들 온기는 그대로", true, h.cabin.steeringHeat)
        h.shot("32_twenty_home")

        // 다시 시동을 걸어도 이어받기와 편지는 한 번뿐이다
        h.restartAt(11, 30)
        h.advance(500)
        assertFalse(h.screen is Screen.Handover || h.screen is Screen.LetterOpen || h.screen is Screen.LetterArrive)
        assertEquals(2, h.data.cars.size)
        h.ignition(false)
    }

    // ───────── 곁길: 주제가 없는 처음 간 곳(마트) → 주제를 골라 남기기 ─────────

    fun firstVisitWithoutTopic(h: E2eHarness) {
        h.startDay(ScenarioDay.WEEKDAY)
        val recordsBefore = h.data.records.size
        h.ignition(true)
        h.awaitScreen<Screen.Home>()

        // 마트: 어느 주제의 장소도 아니고 처음 간 곳
        var mark = h.mark()
        h.driveTo("마트")
        h.advance(2_000)
        assertEquals("도착할 때는 묻지 않음", listOf("Home"), h.screensSince(mark))
        h.restartAt(8, 40)
        val ask = h.screenAs<Screen.RecordAsk>()
        h.shot("33_weekday_record_ask_topic_choice")
        assertEquals("마트", ask.draft.placeName)
        assertNull("차가 주제를 정하지 못함", ask.draft.topicId)
        assertNull(ask.topic)
        assertNull(ask.summary)
        assertEquals("차주의 주제 가운데 고름", h.data.topics.map { it.id }, ask.topicChoices.map { it.id })
        assertEquals(6f, ask.draft.distanceKm, 0.5f)

        // 없는 주제는 무시, 같은 주제를 연달아 눌러도 한 번만
        h.tap { acceptRecord("no-such-topic") }
        h.screenAs<Screen.RecordAsk>()
        assertEquals(recordsBefore, h.data.records.size)
        h.actions.acceptRecord(SeedData.TOPIC_FAMILY)
        h.actions.acceptRecord(SeedData.TOPIC_FAMILY)
        h.actions.acceptRecord(SeedData.TOPIC_RUN)
        h.settle()
        val edit = h.screenAs<Screen.RecordEdit>()
        assertEquals(recordsBefore + 1, h.data.records.size)
        assertEquals(SeedData.TOPIC_FAMILY, edit.record.topicId)
        assertEquals(SeedData.TOPIC_FAMILY, edit.topic.id)

        // 콘솔에서 넣어 둔 말이 한마디가 된다
        h.console.sayNext("장 보러 온 날")
        h.tap { startVoiceNote() }
        h.awaitThat("한마디") { (h.screen as? Screen.RecordEdit)?.record?.notes?.isNotEmpty() == true }
        h.advance(4_000)
        val noted = h.screenAs<Screen.RecordEdit>()
        assertFalse(noted.listening)
        assertEquals("", noted.heardText)

        // 한마디는 여러 개 붙일 수 있다(말이 없으면 붙지 않는다)
        val notesNow = noted.record.notes.map { it.text }
        assertTrue("넣어 둔 말이 들어감: $notesNow", notesNow.contains("장 보러 온 날"))
        mark = h.mark()
        h.tap { startVoiceNote() }
        h.awaitThat("말이 없어 듣기가 끝남", 10_000) { !h.screenAs<Screen.RecordEdit>().listening }
        h.advance(500)
        assertEquals(notesNow, h.screenAs<Screen.RecordEdit>().record.notes.map { it.text })

        h.tap { finishRecord() }
        assertEquals(SeedData.TOPIC_FAMILY, h.screenAs<Screen.Home>().selected?.topic?.id)

        // 홈에서 다른 주제를 고르면 그 주제의 나이테로 바뀐다
        h.tap { selectTopic(SeedData.TOPIC_GOLF) }
        assertEquals(SeedData.TOPIC_GOLF, h.screenAs<Screen.Home>().selected?.topic?.id)
        h.shot("34_weekday_home_golf")
        h.ignition(false)
    }
}
