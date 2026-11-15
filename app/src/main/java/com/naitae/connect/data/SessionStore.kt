package com.naitae.connect.data

import com.naitae.connect.domain.TripSnapshot
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 프로세스가 다시 떠도 이어 가야 하는 것(계정 원본이 아닌, 이 차에서의 진행 상태).
 * 계정 원본(naitae.json) 옆의 작은 파일에 둔다.
 */
@Serializable
data class SessionState(
    /** 시연 날짜(ScenarioDay 이름). Pleos 시연 패널에서 고른 날 */
    val day: String? = null,
    /** clockSavedAtMs(기기 시각) 때의 시연 시각. 시연 시계는 그 뒤로 실제 시간만큼 흐른다. */
    val clock: String? = null,
    val clockSavedAtMs: Long = 0L,
    /** 도착해서 준비해 둔 기록 초안, 세워 둔 곳, 오늘 이미 물은 길 안내 */
    val trip: TripSnapshot = TripSnapshot(),
    /** "도착해서 보기"로 미뤄 둔 편지 */
    val postponedLetterId: String? = null,
) {
    fun scenarioDay(): ScenarioDay? = ScenarioDay.values().firstOrNull { it.name == day }

    /** 지금(기기 시각 wallNowMs)의 시연 시각. 저장된 시각이 없거나 읽을 수 없으면 null */
    fun scenarioNow(wallNowMs: Long): LocalDateTime? {
        val saved = try {
            LocalDateTime.parse(clock ?: return null)
        } catch (e: Exception) {
            return null
        }
        // 기기 시계가 뒤로 갔으면 저장한 시각에서 이어 간다.
        return saved.plus((wallNowMs - clockSavedAtMs).coerceAtLeast(0L), ChronoUnit.MILLIS)
    }
}

/**
 * [SessionState] 를 파일에 둔다. 만들 때 한 번 읽고, 바뀔 때마다 임시 파일에 쓴 뒤 이름을 바꾼다.
 * 쓰기는 io 에서 하고, 언제나 그 순간의 가장 새 값을 쓰므로 순서가 뒤바뀌어도 옛 값이 남지 않는다.
 */
class SessionStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val writing = Mutex()

    /** 지난 프로세스가 남긴 값. 파일이 없거나 읽지 못했으면 null */
    val restored: SessionState? = load()

    @Volatile
    var state: SessionState = restored ?: SessionState()
        private set

    fun update(transform: (SessionState) -> SessionState) {
        val changed = synchronized(lock) {
            val next = transform(state)
            if (next == state) {
                false
            } else {
                state = next
                true
            }
        }
        if (changed) scope.launch(io) { writing.withLock { save(state) } }
    }

    private fun load(): SessionState? =
        try {
            if (file.isFile) json.decodeFromString(SessionState.serializer(), file.readText(Charsets.UTF_8)) else null
        } catch (e: Exception) {
            SdkLog.sys("진행 상태 파일을 읽지 못해 처음부터 시작 (${e.javaClass.simpleName})")
            null
        }

    /** 저장에 실패해도 앱은 메모리의 값으로 계속 돈다. */
    private fun save(value: SessionState) {
        try {
            val text = json.encodeToString(SessionState.serializer(), value)
            file.absoluteFile.parentFile?.mkdirs()
            val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            temp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            SdkLog.sys("진행 상태 저장 실패 (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
