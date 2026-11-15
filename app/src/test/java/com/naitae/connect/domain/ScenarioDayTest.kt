package com.naitae.connect.domain

import com.naitae.connect.arrivedAt
import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Photo
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.core.model.VoiceNote
import com.naitae.connect.data.SeedData
import com.naitae.connect.seatsOf
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SeatOccupancy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** ARCHITECTURE 5절의 시나리오 날짜를 엔진 수준에서 처음부터 끝까지 따라간다. */
class ScenarioDayTest {

    /** 저장소 대신 쓰는 계정 원본 */
    private class Account(var data: NaitaeData)

    private fun trackerOf(account: Account, vin: String = SeedData.VIN_FIRST_CAR): TripTracker =
        TripTracker({ account.data }, { vin })

    // ───────── 평일 · 출근길 ─────────

    @Test
    fun `평일 첫 시동의 브리핑은 일정 메모 디데이 순으로 읽는다`() {
        val now = SeedData.dateTimeOf(ScenarioDay.WEEKDAY)
        val data = SeedData.forDay(ScenarioDay.WEEKDAY)

        assertTrue(BriefingEngine.shouldBrief(data, now.toLocalDate()))
        val briefing = BriefingEngine.build(data, now, SeatOccupancy())

        assertEquals("11월 12일 목요일", briefing.dateLabel)
        assertEquals("좋은 아침이에요.", briefing.greeting)
        assertTrue(briefing.askMemo)
        assertEquals(listOf("일정", "일정", "메모", "D-2", "D-3"), briefing.items.map { it.tag })
        assertEquals(
            listOf(BriefingKind.SCHEDULE, BriefingKind.SCHEDULE, BriefingKind.MEMO, BriefingKind.DDAY, BriefingKind.DDAY),
            briefing.items.map { it.kind },
        )
        assertEquals(
            listOf(
                "오전 10시, 거래처 미팅이 있어요.",
                "저녁 7시, 지우 피아노 학원 픽업이 있어요.",
                "엄마 메모, 퇴근길에 우유 사 오기.",
                "토요일 낚시까지 이틀 남았어요.",
                "일요일 가족 나들이까지 사흘 남았어요.",
            ),
            briefing.items.map { it.spoken },
        )
        assertEquals(
            listOf(
                "10:00 거래처 미팅",
                "19:00 지우 피아노 학원 픽업",
                "엄마: 퇴근길에 우유 사 오기",
                "토요일 낚시 · 05:00 낚시터",
                "일요일 가족 나들이 · 수목원",
            ),
            briefing.items.map { it.title },
        )
        assertEquals(
            listOf(null, null, "퇴근길 시동 때 다시 알려 줌", "그날 시동 때 길 안내를 물어봄", "그날 시동 때 길 안내를 물어봄"),
            briefing.items.map { it.sub },
        )
        assertEquals(SeedData.MEMO_MILK, briefing.items[2].memoId)
        assertEquals(SeedData.SCHEDULE_FISHING, briefing.items[3].scheduleId)
    }

    @Test
    fun `브리핑은 하루에 한 번이고 말로 남긴 메모는 다음 날 브리핑에서 읽는다`() {
        val now = SeedData.dateTimeOf(ScenarioDay.WEEKDAY)
        val today = now.toLocalDate()
        var data = SeedData.forDay(ScenarioDay.WEEKDAY)
        val briefing = BriefingEngine.build(data, now, SeatOccupancy())

        // "메모할 것 있으세요?" → "내일 세차 예약"
        val (withMemo, memo) = MemoEngine.add(data, " 내일 세차 예약 ", data.owner.id, now.plusMinutes(2))
        assertEquals("내일 세차 예약", memo.text)
        data = BriefingEngine.markDone(withMemo, briefing, today)

        assertFalse(BriefingEngine.shouldBrief(data, today))
        assertTrue(BriefingEngine.shouldBrief(data, today.plusDays(1)))
        assertEquals(today.toEpochDay(), data.lastBriefingEpochDay)
        assertEquals(today.toEpochDay(), data.memos.first { it.id == SeedData.MEMO_MILK }.briefedEpochDay)
        assertNull(data.memos.first { it.id == memo.id }.briefedEpochDay)

        // 퇴근길 알림까지 끝낸 뒤의 다음 날 아침
        data = MemoEngine.markReminded(data, listOf(SeedData.MEMO_MILK), now.withHour(18).withMinute(40))
        val next = BriefingEngine.build(data, now.plusDays(1), SeatOccupancy())
        assertEquals(listOf("메모", "D-1", "D-2"), next.items.map { it.tag })
        assertEquals("내 메모, 내일 세차 예약.", next.items[0].spoken)
        assertEquals("내일 세차 예약", next.items[0].title)
        assertEquals("토요일 낚시까지 하루 남았어요.", next.items[1].spoken)
    }

    @Test
    fun `회사 도착은 평소 도착이고 퇴근길 시동에 엄마 메모를 다시 알려 준다`() {
        val morning = SeedData.dateTimeOf(ScenarioDay.WEEKDAY)
        val today = morning.toLocalDate()
        val account = Account(SeedData.forDay(ScenarioDay.WEEKDAY))
        val tracker = trackerOf(account)
        val solo = SeatOccupancy()

        // 아침 시동: 장소가 있는 일정이 없으므로 길 안내를 묻지 않는다.
        tracker.onSignal(TripSignal.Seats(solo))
        tracker.onSignal(TripSignal.Odometer(12_840f))
        assertEquals(emptyList<TripEffect>(), tracker.onSignal(TripSignal.IgnitionOn(morning)))
        val briefing = BriefingEngine.build(account.data, morning, solo)
        account.data = BriefingEngine.markDone(account.data, briefing, today)
        // 아침에는 아직 퇴근길 알림 시각이 아니다.
        assertTrue(MemoEngine.dueAtIgnition(account.data, morning, solo).isEmpty())

        // 회사 도착: 화면 없이 지나가고 초안도 만들지 않는다.
        tracker.onSignal(TripSignal.Gear(GearState.DRIVE))
        tracker.onSignal(TripSignal.Odometer(12_858f))
        val arrival = tracker.onSignal(TripSignal.Arrived(arrivedAt(SeedData.OFFICE), morning.withHour(8).withMinute(25)))
        assertEquals(listOf<TripEffect>(TripEffect.QuietArrival(SeedData.TOPIC_WORK, "회사", 18f)), arrival)
        assertNull(tracker.pendingDraft)
        tracker.onSignal(TripSignal.Gear(GearState.PARK))
        tracker.onSignal(TripSignal.IgnitionOff(morning.withHour(8).withMinute(26)))

        // 18:40 시동: 기록을 묻지 않고, 브리핑도 다시 하지 않고, 엄마 메모를 다시 알려 준다.
        val evening = morning.withHour(18).withMinute(40)
        assertEquals(emptyList<TripEffect>(), tracker.onSignal(TripSignal.IgnitionOn(evening)))
        assertFalse(BriefingEngine.shouldBrief(account.data, today))
        val due = MemoEngine.dueAtIgnition(account.data, evening, solo)
        assertEquals(listOf(SeedData.MEMO_MILK), due.map { it.id })
        assertEquals("엄마 메모예요. 우유 사기.", MemoEngine.spoken(account.data, due.first()))

        // 한 번 알려 주면 끝이다.
        account.data = MemoEngine.markReminded(account.data, due.map { it.id }, evening)
        assertTrue(MemoEngine.dueAtIgnition(account.data, evening.plusMinutes(30), solo).isEmpty())
        assertEquals(today.toEpochDay(), account.data.memos.first { it.id == SeedData.MEMO_MILK }.remindedEpochDay)
    }

    // ───────── 토요일 새벽 · 낚시 ─────────

    @Test
    fun `낚시 가는 날은 길 안내를 묻고 도착할 때는 묻지 않으며 돌아갈 때 오늘을 남길지 묻는다`() {
        val dawn = SeedData.dateTimeOf(ScenarioDay.FISHING_SATURDAY)
        val today = dawn.toLocalDate()
        val account = Account(SeedData.forDay(ScenarioDay.FISHING_SATURDAY))
        val tracker = trackerOf(account)
        val solo = SeatOccupancy()

        // 목요일 브리핑은 끝나 있고 오늘은 아직이다.
        assertTrue(BriefingEngine.shouldBrief(account.data, today))
        val briefing = BriefingEngine.build(account.data, dawn, solo)
        assertEquals("11월 14일 토요일", briefing.dateLabel)
        assertEquals(listOf("일정", "D-1"), briefing.items.map { it.tag })
        assertEquals("오늘은 낚시 가는 날이에요. 새벽 5시, 낚시터예요.", briefing.items[0].spoken)
        assertEquals("05:00 낚시 · 낚시터", briefing.items[0].title)
        assertEquals("일요일 가족 나들이까지 하루 남았어요.", briefing.items[1].spoken)
        account.data = BriefingEngine.markDone(account.data, briefing, today)

        // 첫 시동: "낚시터로 안내할까요?" (하루에 한 번만)
        tracker.onSignal(TripSignal.Seats(solo))
        tracker.onSignal(TripSignal.Odometer(12_900f))
        val ask = tracker.onSignal(TripSignal.IgnitionOn(dawn))
        assertEquals(1, ask.size)
        val askRoute = ask.single() as TripEffect.AskRoute
        assertEquals(SeedData.SCHEDULE_FISHING, askRoute.schedule.id)
        assertEquals(SeedData.PIER, askRoute.schedule.place)
        assertEquals(emptyList<TripEffect>(), tracker.onSignal(TripSignal.IgnitionOn(dawn.plusMinutes(1))))

        // 안내를 받아 도착: 초안만 조용히 준비한다. 거리는 누적 주행거리의 차이.
        tracker.onSignal(TripSignal.RouteAccepted(SeedData.PIER))
        tracker.onSignal(TripSignal.Gear(GearState.DRIVE))
        tracker.onSignal(TripSignal.Odometer(12_932f))
        val arrival = tracker.onSignal(TripSignal.Arrived(arrivedAt(SeedData.PIER), dawn.withMinute(50)))
        assertEquals(1, arrival.size)
        val draft = (arrival.single() as TripEffect.DraftReady).draft
        assertEquals(draft, tracker.pendingDraft)
        assertEquals(SeedData.TOPIC_FISH, draft.topicId)
        assertEquals("낚시터", draft.placeName)
        assertEquals(SeedData.PIER, draft.place)
        assertEquals(today.toEpochDay(), draft.epochDay)
        assertEquals(32f, draft.distanceKm, 0.001f)
        assertTrue(draft.firstVisit)
        assertEquals(1, draft.seatCount)
        assertEquals(SeedData.SCHEDULE_FISHING, draft.scheduleId)
        assertEquals(SeedData.VIN_FIRST_CAR, draft.vin)
        tracker.onSignal(TripSignal.Gear(GearState.PARK))
        tracker.onSignal(TripSignal.IgnitionOff(dawn.withMinute(52)))

        // 11:40 돌아가려는 시동: "오늘을 남길까요?" 하나만 낸다.
        val back = dawn.withHour(11).withMinute(40)
        assertEquals(listOf<TripEffect>(TripEffect.AskRecord(draft)), tracker.onSignal(TripSignal.IgnitionOn(back)))

        // 남기기 + 한마디
        val (committed, record) = RecordEngine.commit(account.data, draft, draft.topicId ?: SeedData.TOPIC_FISH)
        account.data = RecordEngine.addNote(committed, record.id, VoiceNote(SeedData.NOTE_FISHING, account.data.owner.id))
        tracker.clearDraft()
        assertNull(tracker.pendingDraft)
        val saved = account.data.records.first { it.id == record.id }
        assertEquals(RecordEngine.recordId(today.toEpochDay(), "낚시터"), saved.id)
        assertEquals(listOf(SeedData.NOTE_FISHING), saved.notes.map { it.text })
        assertFalse(saved.together)

        // 같은 날 같은 곳은 두 번 남기지 않는다.
        val (again, sameRecord) = RecordEngine.commit(account.data, draft, SeedData.TOPIC_FISH)
        assertEquals(account.data.records.size, again.records.size)
        assertEquals(saved, sameRecord)

        // 다녀온 뒤에는 길 안내도 기록도 다시 묻지 않는다.
        assertNull(ScheduleEngine.routeCandidate(account.data, today))
        assertEquals(emptyList<TripEffect>(), tracker.onSignal(TripSignal.IgnitionOn(back.plusMinutes(5))))

        // 이렇게 남긴 기록이 다음 날(일요일) 가상 데이터의 토요일 기록과 같다.
        val seeded = SeedData.forDay(ScenarioDay.FAMILY_SUNDAY).records.first { it.epochDay == today.toEpochDay() }
        assertEquals(seeded, saved)

        // 낚시 나이테: 올해 겹에 점이 하나 늘고, 방금 남긴 점이 새 점이다.
        val summary = RingBuilder.summary(account.data, SeedData.TOPIC_FISH, today, highlightRecordId = saved.id)
        assertEquals(1, summary.rings.size)
        assertEquals(7, summary.rings[0].scenes.size)
        assertEquals(listOf(saved.id), summary.rings[0].scenes.filter { it.isNew }.map { it.recordId })
        assertEquals(listOf("7일", "5곳", "2일"), summary.stats.map { it.value })
        assertEquals(saved, summary.latest)
    }

    @Test
    fun `물음에 답하지 않고 출발하면 다음 시동에 다시 묻지 않는다`() {
        val dawn = SeedData.dateTimeOf(ScenarioDay.FISHING_SATURDAY)
        val account = Account(SeedData.forDay(ScenarioDay.FISHING_SATURDAY))
        val tracker = trackerOf(account)

        tracker.onSignal(TripSignal.IgnitionOn(dawn))
        tracker.onSignal(TripSignal.Gear(GearState.DRIVE))
        tracker.onSignal(TripSignal.Arrived(arrivedAt(SeedData.PIER, distanceKm = 32f), dawn.withMinute(50)))
        tracker.onSignal(TripSignal.Gear(GearState.PARK))
        assertNotNull(tracker.pendingDraft)

        val back = dawn.withHour(11).withMinute(40)
        assertEquals(1, tracker.onSignal(TripSignal.IgnitionOn(back)).size)
        tracker.onSignal(TripSignal.Gear(GearState.DRIVE))
        assertNull(tracker.pendingDraft)
        assertEquals(emptyList<TripEffect>(), tracker.onSignal(TripSignal.IgnitionOn(back.plusHours(1))))
    }

    // ───────── 일요일 · 가족 나들이 ─────────

    @Test
    fun `가족 나들이는 토요일 기록 위에 넷이 함께한 날로 남고 고른 색이 겹의 색이 된다`() {
        val now = SeedData.dateTimeOf(ScenarioDay.FAMILY_SUNDAY)
        val today = now.toLocalDate()
        val account = Account(SeedData.forDay(ScenarioDay.FAMILY_SUNDAY))
        val tracker = trackerOf(account)
        val family = seatsOf(4)

        // 어제(토요일)의 낚시 기록이 쌓여 있다.
        val saturday = account.data.records.first { it.epochDay == today.toEpochDay() - 1 }
        assertEquals("낚시터", saturday.placeName)
        assertEquals(listOf(SeedData.NOTE_FISHING), saturday.notes.map { it.text })
        assertTrue(saturday.firstVisit)

        val briefing = BriefingEngine.build(account.data, now, family)
        assertEquals(listOf("일정"), briefing.items.map { it.tag })
        assertEquals("오늘은 가족 나들이 가는 날이에요.", briefing.items[0].spoken)
        assertEquals("가족 나들이 · 수목원", briefing.items[0].title)

        tracker.onSignal(TripSignal.Seats(family))
        val ask = tracker.onSignal(TripSignal.IgnitionOn(now)).single() as TripEffect.AskRoute
        assertEquals(SeedData.SCHEDULE_FAMILY, ask.schedule.id)

        // 도착 이벤트가 거리를 주면 그 값을 쓴다.
        tracker.onSignal(TripSignal.Gear(GearState.DRIVE))
        val arrival = tracker.onSignal(TripSignal.Arrived(arrivedAt(SeedData.ARBORETUM, distanceKm = 41f), now.plusMinutes(50)))
        val draft = (arrival.single() as TripEffect.DraftReady).draft
        assertEquals(SeedData.TOPIC_FAMILY, draft.topicId)
        assertTrue(draft.firstVisit)
        assertEquals(4, draft.seatCount)
        assertEquals(41f, draft.distanceKm, 0.001f)
        tracker.onSignal(TripSignal.Gear(GearState.PARK))

        // 16:30 다시 시동 → 남기기 → 지우의 한마디 → 꾸미기(사진 3장, 보라)
        val back = now.withHour(16).withMinute(30)
        assertEquals(listOf<TripEffect>(TripEffect.AskRecord(draft)), tracker.onSignal(TripSignal.IgnitionOn(back)))
        val (committed, record) = RecordEngine.commit(account.data, draft, SeedData.TOPIC_FAMILY)
        var data = RecordEngine.addNote(
            committed, record.id, VoiceNote(SeedData.NOTE_JIWOO, SeedData.JIWOO_ID, SeedData.VOICE_JIWOO_LAST_YEAR),
        )
        listOf(SeedData.MOM_ID, SeedData.JIWOO_ID, SeedData.DOYUN_ID).forEachIndexed { index, memberId ->
            data = RecordEngine.addPhoto(data, record.id, Photo("ph-${record.id}-${index + 1}", SceneKind.FAMILY_TRIP, memberId))
        }
        data = RecordEngine.setColor(data, record.id, AmbientColor.PURPLE)
        account.data = data
        tracker.clearDraft()

        val saved = account.data.records.first { it.id == record.id }
        assertTrue(saved.together)
        assertEquals(AmbientColor.PURPLE, saved.color)
        assertEquals(3, saved.photos.size)
        // 1년 뒤 가상 데이터의 수목원 기록과 같다.
        val seeded = SeedData.forDay(ScenarioDay.ONE_YEAR_LATER).records.first { it.epochDay == today.toEpochDay() }
        assertEquals(seeded, saved)

        // 가족 나이테의 올해 겹은 보라.
        val ring = RingBuilder.rings(account.data, SeedData.TOPIC_FAMILY, today, saved.id).single()
        assertEquals(AmbientColor.PURPLE, ring.color)
        assertTrue(ring.scenes.last().isNew)
        assertTrue(ring.scenes.last().firstVisit)

        // 없는 기록을 고치려 하면 그대로 둔다.
        assertEquals(account.data, RecordEngine.setColor(account.data, "없는-기록", AmbientColor.BLUE))
    }

    // ───────── 1년 뒤 같은 날 ─────────

    @Test
    fun `1년 뒤 수목원에 도착하면 초안 없이 다시 보기를 열고 작년 기록을 찾는다`() {
        val now = SeedData.dateTimeOf(ScenarioDay.ONE_YEAR_LATER)
        val today = now.toLocalDate()
        val account = Account(SeedData.forDay(ScenarioDay.ONE_YEAR_LATER))
        val tracker = trackerOf(account)

        // 앞선 날들의 브리핑은 끝나 있고, 오늘 일정은 수목원 가족 나들이다.
        assertEquals(today.toEpochDay() - 1, account.data.lastBriefingEpochDay)
        assertTrue(BriefingEngine.shouldBrief(account.data, today))
        val todaySchedule = ScheduleEngine.today(account.data, today).single()
        assertEquals(SeedData.ARBORETUM, todaySchedule.place)
        assertEquals(SeedData.TOPIC_FAMILY, todaySchedule.topicId)

        tracker.onSignal(TripSignal.Seats(seatsOf(4)))
        val ask = tracker.onSignal(TripSignal.IgnitionOn(now)).single() as TripEffect.AskRoute
        assertEquals(todaySchedule, ask.schedule)

        tracker.onSignal(TripSignal.Gear(GearState.DRIVE))
        val arrival = tracker.onSignal(TripSignal.Arrived(arrivedAt(SeedData.ARBORETUM, distanceKm = 41f), now.plusMinutes(50)))
        assertEquals(listOf<TripEffect>(TripEffect.RecallAt("수목원")), arrival)
        assertNull(tracker.pendingDraft)
        tracker.onSignal(TripSignal.Gear(GearState.PARK))
        // 다시 보기가 열린 날은 돌아갈 때 묻지 않는다.
        assertEquals(emptyList<TripEffect>(), tracker.onSignal(TripSignal.IgnitionOn(now.withHour(16))))

        val recall = RecallEngine.find(account.data, "수목원", today)
        assertNotNull(recall)
        recall!!
        assertEquals(1, recall.yearsAgo)
        assertEquals("1년 전 오늘, 여기서", recall.title)
        assertEquals(LocalDate.of(2026, 11, 15).toEpochDay(), recall.record.epochDay)
        assertEquals(SeedData.TOPIC_FAMILY, recall.topic.id)
        assertEquals(AmbientColor.PURPLE, recall.record.color)
        assertEquals(3, recall.record.photos.size)
        assertEquals(4, recall.record.seatCount)
        val note = recall.record.notes.single()
        assertEquals("아빠, 내년에 또 오자!", note.text)
        assertEquals(SeedData.JIWOO_ID, note.authorId)
        assertEquals("voice/jiwoo_last_year.mp3", note.audioAsset)

        // 간 적 없는 곳에는 다시 볼 기록이 없다.
        assertNull(RecallEngine.find(account.data, "마트", today))
    }

    @Test
    fun `다시 보기는 해가 바뀐 기록을 먼저 고르고 오늘 기록은 빼며 없으면 지난번을 보여 준다`() {
        val base = SeedData.forDay(ScenarioDay.ONE_YEAR_LATER)
        val today = LocalDate.of(2027, 11, 15)
        val lastYear = base.records.first { it.placeName == "수목원" }
        val lastMonth = lastYear.copy(id = "rec-last-month", epochDay = today.minusDays(30).toEpochDay())
        val sameDay = lastYear.copy(id = "rec-today", epochDay = today.toEpochDay())
        val data = base.copy(records = base.records + lastMonth + sameDay)

        assertEquals(lastYear.id, RecallEngine.find(data, "수목원", today)?.record?.id)

        val onlyRecent = base.copy(records = listOf(lastMonth, sameDay))
        val recent = RecallEngine.find(onlyRecent, "수목원", today)
        assertEquals("rec-last-month", recent?.record?.id)
        assertEquals(0, recent?.yearsAgo)
        assertEquals("지난번, 여기서", recent?.title)

        assertNull(RecallEngine.find(base.copy(records = listOf(sameDay)), "수목원", today))
    }

    // ───────── 아빠 생일 아침 ─────────

    @Test
    fun `생일 아침에 편지가 열릴 차례가 되고 동승자가 있으면 먼저 묻는다`() {
        val now = SeedData.dateTimeOf(ScenarioDay.BIRTHDAY)
        val today = now.toLocalDate()
        val data = SeedData.forDay(ScenarioDay.BIRTHDAY)

        val letter = LetterEngine.due(data, today, newCarFirstIgnition = false)
        assertNotNull(letter)
        letter!!
        assertEquals(SeedData.LETTER_BIRTHDAY, letter.id)
        assertEquals("아빠, 생일 축하해! 오늘 출근길도 조심히 다녀와요.", letter.text)
        assertEquals("엄마 · 지우 · 도윤", LetterEngine.fromNames(data, letter))
        assertEquals(AmbientColor.WHITE, letter.effect.color)
        assertEquals(100, letter.effect.brightness)
        assertEquals(3, letter.effect.rampSeconds)
        assertEquals(3, letter.effect.seatHeatLevel)
        assertTrue(letter.effect.steeringHeat)
        assertEquals(24f, letter.effect.cabinTempC)
        assertEquals(1, letter.effect.fanLevel)
        assertEquals("voice/birthday_letter.mp3", letter.audioAsset)
        assertFalse(letter.openOnNewCar)

        // 혼자면 바로 열고, 누가 함께 탔으면 먼저 묻는다.
        assertFalse(LetterEngine.shouldAskFirst(SeatOccupancy()))
        assertTrue(LetterEngine.shouldAskFirst(seatsOf(2)))

        // 그 전날까지는 열리지 않고, 그날 차를 타지 않았으면 다음 시동에 열린다.
        assertNull(LetterEngine.due(data, today.minusDays(1), newCarFirstIgnition = false))
        assertNull(LetterEngine.due(SeedData.forDay(ScenarioDay.ONE_YEAR_LATER), LocalDate.of(2027, 11, 15), false))
        assertEquals(letter, LetterEngine.due(data, today.plusDays(2), newCarFirstIgnition = false))

        // 열고 나면 다시 열리지 않는다.
        val opened = LetterEngine.markOpened(data, letter.id, today)
        assertEquals(today.toEpochDay(), opened.letters.first { it.id == letter.id }.openedEpochDay)
        assertNull(LetterEngine.due(opened, today, newCarFirstIgnition = false))
    }

    // ───────── 20년 뒤 · 선물 받은 새 차 ─────────

    @Test
    fun `20년 뒤 새 차의 첫 시동에 스무 겹을 이어받고 우산 편지가 열린다`() {
        val now = SeedData.dateTimeOf(ScenarioDay.TWENTY_YEARS_LATER)
        val today = now.toLocalDate()
        val data = SeedData.forDay(ScenarioDay.TWENTY_YEARS_LATER)

        // 그랜저가 현재 차이고, 2026~2045년 해마다 여러 건의 기록이 있다. 2046년 기록은 없다.
        assertEquals(SeedData.VIN_FIRST_CAR, data.currentCar?.vin)
        val perYear = data.records.groupBy { LocalDate.ofEpochDay(it.epochDay).year }
        assertEquals((2026..2045).toList(), perYear.keys.sorted())
        assertTrue(perYear.values.all { it.size >= 5 })
        assertTrue(data.records.all { it.vin == SeedData.VIN_FIRST_CAR })

        // 같은 차면 이어받기가 아니다.
        assertNull(HandoverEngine.detect(data, SeedData.VIN_FIRST_CAR, today))

        val summary = HandoverEngine.detect(data, SeedData.VIN_NEW_CAR, today)
        assertNotNull(summary)
        summary!!
        assertEquals(20, summary.ringCount)
        assertEquals(SeedData.VIN_FIRST_CAR, summary.fromCar?.vin)
        assertEquals(SeedData.VIN_NEW_CAR, summary.toVin)
        assertEquals(5, summary.topicCount)
        assertEquals(data.records.size, summary.recordCount)

        // RingBuilder 와 같은 규칙: 다 자란 스무 겹 + 새 차에서 자라기 시작하는 올해 겹(아직 점 없음)
        for (topic in data.topics) {
            val rings = RingBuilder.rings(data, topic.id, today)
            assertEquals((2026..2046).toList(), rings.map { it.year })
            assertEquals(20, rings.count { it.complete })
            assertEquals(20, RingBuilder.grownRingCount(rings))
            assertTrue(rings.last().scenes.isEmpty())
        }

        // 이어받기: 예전 차는 보낸 차가 되고 기록·일정·메모는 그대로다.
        val handed = HandoverEngine.apply(data, summary, "새 현대차", today)
        assertEquals(2, handed.cars.size)
        assertEquals(today.toEpochDay(), handed.cars[0].retiredEpochDay)
        assertEquals(SeedData.VIN_NEW_CAR, handed.currentCar?.vin)
        assertEquals("새 현대차", handed.currentCar?.model)
        assertEquals(data.records, handed.records)
        assertEquals(data.schedules, handed.schedules)
        assertEquals(data.memos, handed.memos)
        assertNull(HandoverEngine.detect(handed, SeedData.VIN_NEW_CAR, today))
        assertEquals(handed, HandoverEngine.apply(handed, summary, "새 현대차", today))

        // 올해 겹은 새 차의 것, 그 전의 겹은 그랜저의 것
        val familyRings = RingBuilder.rings(handed, SeedData.TOPIC_FAMILY, today)
        assertEquals(SeedData.VIN_NEW_CAR, familyRings.last().vin)
        assertTrue(familyRings.dropLast(1).all { it.vin == SeedData.VIN_FIRST_CAR })

        // 새 차 첫 시동의 편지
        val letter = LetterEngine.due(handed, today, newCarFirstIgnition = true)
        assertNotNull(letter)
        letter!!
        assertEquals(SeedData.LETTER_NEW_CAR, letter.id)
        assertEquals("늘 나를 지켜 주는 우산인 줄 알았던 아빠가, 이제는 빗길 없이 따뜻하게만 달렸으면 좋겠습니다.", letter.text)
        assertEquals("지우 · 도윤", LetterEngine.fromNames(handed, letter))
        assertTrue(letter.openOnNewCar)
        assertEquals(AmbientColor.PURPLE, letter.effect.color)
        assertEquals(100, letter.effect.brightness)
        assertEquals(4, letter.effect.rampSeconds)
        assertEquals(0, letter.effect.seatHeatLevel)
        assertTrue(letter.effect.steeringHeat)
        assertEquals("voice/umbrella_letter.mp3", letter.audioAsset)
        // 새 차 첫 시동이 아니면 이 편지는 고르지 않는다(생일 편지는 이미 열었다).
        assertNull(LetterEngine.due(handed, today, newCarFirstIgnition = false))
        assertNull(LetterEngine.due(LetterEngine.markOpened(handed, letter.id, today), today, newCarFirstIgnition = true))

        // 이어받는 날에는 길 안내도 기록도 묻지 않는다.
        val account = Account(handed)
        val tracker = trackerOf(account, SeedData.VIN_NEW_CAR)
        tracker.onSignal(TripSignal.Odometer(0f))
        assertEquals(emptyList<TripEffect>(), tracker.onSignal(TripSignal.IgnitionOn(now)))
    }

    @Test
    fun `등록된 차가 없을 때만 첫 차로 등록한다`() {
        val today = LocalDate.of(2026, 1, 3)
        val empty = SeedData.forDay(ScenarioDay.WEEKDAY).copy(cars = emptyList())

        // 첫 차 전에는 이어받기가 아니다.
        assertNull(HandoverEngine.detect(empty, SeedData.VIN_FIRST_CAR, today))

        val registered = HandoverEngine.registerFirstCar(empty, SeedData.VIN_FIRST_CAR, "그랜저", today)
        assertEquals(1, registered.cars.size)
        assertEquals(SeedData.VIN_FIRST_CAR, registered.currentCar?.vin)
        assertEquals(today.toEpochDay(), registered.currentCar?.startEpochDay)
        assertEquals(registered, HandoverEngine.registerFirstCar(registered, SeedData.VIN_NEW_CAR, "새 현대차", today))
    }

    // ───────── 메모 ─────────

    @Test
    fun `개인 메모는 동승자가 있으면 읽지 않고 미룬다`() {
        val now = SeedData.dateTimeOf(ScenarioDay.WEEKDAY)
        val (data, memo) = MemoEngine.add(
            SeedData.forDay(ScenarioDay.WEEKDAY), "결혼기념일 선물 찾기", SeedData.OWNER_ID, now.minusHours(10), personal = true,
        )

        val together = BriefingEngine.build(data, now, seatsOf(2))
        assertFalse(together.items.any { it.memoId == memo.id })
        // 읽지 않았으므로 읽은 날이 남지 않고, 혼자 타는 날 읽는다.
        val afterTogether = BriefingEngine.markDone(data, together, now.toLocalDate())
        assertNull(afterTogether.memos.first { it.id == memo.id }.briefedEpochDay)
        val alone = BriefingEngine.build(afterTogether, now.plusDays(1), SeatOccupancy())
        assertTrue(alone.items.any { it.memoId == memo.id })
    }

    @Test
    fun `장소 메모는 그곳에 가까워지면 알려 주고 끝나면 근접 알림을 걷는다`() {
        val now: LocalDateTime = SeedData.dateTimeOf(ScenarioDay.WEEKDAY)
        val remind = MemoRemind.AtPlace(SeedData.MART, radiusM = 400)
        val (data, memo) = MemoEngine.add(SeedData.forDay(ScenarioDay.WEEKDAY), "건전지 사기", SeedData.MOM_ID, now, remind)

        assertEquals(listOf(SeedData.MART to 400), MemoEngine.placesToWatch(data))
        assertEquals(listOf(memo.id), MemoEngine.dueAtPlace(data, "마트").map { it.id })
        assertTrue(MemoEngine.dueAtPlace(data, "회사").isEmpty())
        assertEquals("엄마 메모예요. 건전지 사기.", MemoEngine.spoken(data, memo))
        // 브리핑에서는 어디서 다시 알려 주는지 함께 보여 준다.
        val item = BriefingEngine.build(data, now, SeatOccupancy()).items.first { it.memoId == memo.id }
        assertEquals("마트 근처에서 다시 알려 줌", item.sub)

        val reminded = MemoEngine.markReminded(data, listOf(memo.id), now.plusHours(11))
        assertTrue(MemoEngine.dueAtPlace(reminded, "마트").isEmpty())
        assertTrue(MemoEngine.placesToWatch(reminded).isEmpty())
    }
}
