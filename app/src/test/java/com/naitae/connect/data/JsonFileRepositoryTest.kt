package com.naitae.connect.data

import com.naitae.connect.core.model.Memo
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.vehicle.ScenarioDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JSON 파일 저장소: 저장했다가 다시 읽어도 같은 값인지, 깨진 파일에도 죽지 않는지 */
class JsonFileRepositoryTest {
    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("naitae-repo-test").toFile()
        file = File(dir, "naitae.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun memo(id: String, remind: MemoRemind): Memo =
        Memo(id = id, text = "메모 $id", authorId = SeedData.MOM_ID, createdAtEpochMs = 1_000L, remind = remind)

    @Test
    fun `파일이 없으면 seed 로 시작하고 아직 파일을 만들지 않는다`() {
        val seed = SeedData.forDay(ScenarioDay.WEEKDAY)
        val repository = JsonFileRepository(file) { seed }

        assertEquals(seed, repository.data.value)
        assertFalse(file.exists())
    }

    @Test
    fun `모든 알림 방식의 메모를 저장했다가 다시 읽으면 같은 값이다`() = runTest {
        // 편지·기록·일정이 모두 들어 있는 날의 데이터에 메모 세 가지를 더한다.
        val repository = JsonFileRepository(file) { SeedData.forDay(ScenarioDay.BIRTHDAY) }
        val updated = repository.update { data ->
            data.copy(
                memos = data.memos + listOf(
                    memo("next", MemoRemind.NextIgnition),
                    memo("evening", MemoRemind.EveningIgnition(afterHour = 18)),
                    memo("place", MemoRemind.AtPlace(SeedData.MART, radiusM = 500)),
                ),
            )
        }
        assertEquals(updated, repository.data.value)
        assertTrue(file.isFile)
        // 임시 파일은 이름을 바꿔 원본이 되므로 남지 않는다.
        assertFalse(File(dir, "naitae.json.tmp").exists())

        val reloaded = JsonFileRepository(file) { error("파일이 있으면 seed 를 쓰지 않는다") }
        assertEquals(updated, reloaded.data.value)
        val memos = reloaded.data.value.memos.associateBy { it.id }
        assertEquals(MemoRemind.NextIgnition, memos.getValue("next").remind)
        assertEquals(MemoRemind.EveningIgnition(18), memos.getValue("evening").remind)
        assertEquals(MemoRemind.AtPlace(SeedData.MART, 500), memos.getValue("place").remind)
        assertEquals(MemoRemind.EveningIgnition(17), memos.getValue(SeedData.MEMO_MILK).remind)

        // 알림 방식은 "type" 으로 구분해 저장하고, 기본값도 빠짐없이 적는다.
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"type\":\"next\""))
        assertTrue(text.contains("\"type\":\"evening\""))
        assertTrue(text.contains("\"type\":\"place\""))
        assertTrue(text.contains("\"schemaVersion\":1"))
    }

    @Test
    fun `20년치 데이터도 통째로 바꿔 저장하고 그대로 다시 읽는다`() = runTest {
        val repository = JsonFileRepository(file) { SeedData.forDay(ScenarioDay.WEEKDAY) }
        val twenty = SeedData.forDay(ScenarioDay.TWENTY_YEARS_LATER)

        repository.replace(twenty)

        assertEquals(twenty, repository.data.value)
        assertEquals(twenty, JsonFileRepository(file) { SeedData.forDay(ScenarioDay.WEEKDAY) }.data.value)
    }

    @Test
    fun `깨진 파일이면 seed 로 시작하고 다음 저장으로 바로잡는다`() = runTest {
        file.writeText("{ 이건 JSON 이 아니다", Charsets.UTF_8)
        val seed = SeedData.forDay(ScenarioDay.FISHING_SATURDAY)

        val repository = JsonFileRepository(file) { seed }
        assertEquals(seed, repository.data.value)

        repository.update { it.copy(lastBriefingEpochDay = 123L) }
        val reloaded = JsonFileRepository(file) { SeedData.forDay(ScenarioDay.WEEKDAY) }
        assertEquals(123L, reloaded.data.value.lastBriefingEpochDay)
        assertEquals(seed.records, reloaded.data.value.records)
    }

    @Test
    fun `모르는 항목이 있는 파일도 읽는다`() = runTest {
        val seed = SeedData.forDay(ScenarioDay.FAMILY_SUNDAY)
        JsonFileRepository(file) { seed }.replace(seed)
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.startsWith("{"))
        file.writeText("{\"addedInLaterVersion\":{\"x\":[1,2]}," + text.substring(1), Charsets.UTF_8)

        val reloaded = JsonFileRepository(file) { SeedData.forDay(ScenarioDay.WEEKDAY) }
        assertEquals(seed, reloaded.data.value)
    }

    @Test
    fun `동시에 바꿔도 하나도 잃지 않는다`() = runTest {
        val repository = JsonFileRepository(file) { SeedData.forDay(ScenarioDay.WEEKDAY) }
        val before = repository.data.value.memos.size

        coroutineScope {
            repeat(30) { index ->
                launch(Dispatchers.Default) {
                    repository.update { data: NaitaeData ->
                        data.copy(memos = data.memos + memo("m$index", MemoRemind.NextIgnition))
                    }
                }
            }
        }

        assertEquals(before + 30, repository.data.value.memos.size)
        val reloaded = JsonFileRepository(file) { SeedData.forDay(ScenarioDay.WEEKDAY) }
        assertEquals(repository.data.value, reloaded.data.value)
    }

    @Test
    fun `바뀐 것이 없으면 값을 그대로 돌려준다`() = runTest {
        val seed = SeedData.forDay(ScenarioDay.WEEKDAY)
        val repository = JsonFileRepository(file) { seed }

        assertEquals(seed, repository.update { it })
        assertFalse(file.exists())
    }
}
