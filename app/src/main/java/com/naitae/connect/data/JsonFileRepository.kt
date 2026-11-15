package com.naitae.connect.data

import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON 파일 저장소. 만들 때 파일을 읽고, 파일이 없거나 깨졌으면 seed() 로 시작한다.
 * 바꿀 때마다 임시 파일에 쓴 뒤 이름을 바꿔, 쓰다가 꺼져도 원본이 깨지지 않게 한다.
 */
class JsonFileRepository(
    private val file: File,
    seed: () -> NaitaeData,
) : NaitaeRepository {
    private val mutex = Mutex()
    private val _data = MutableStateFlow(load() ?: seed())
    override val data: StateFlow<NaitaeData> = _data.asStateFlow()

    override suspend fun update(transform: (NaitaeData) -> NaitaeData): NaitaeData = mutex.withLock {
        val before = _data.value
        val next = transform(before)
        if (next != before) {
            _data.value = next
            save(next)
        }
        next
    }

    override suspend fun replace(data: NaitaeData) {
        mutex.withLock {
            _data.value = data
            save(data)
        }
    }

    /** 파일에서 읽는다. 없거나 읽지 못하면 null */
    private fun load(): NaitaeData? =
        try {
            if (file.isFile) json.decodeFromString(NaitaeData.serializer(), file.readText(Charsets.UTF_8)) else null
        } catch (e: Exception) {
            SdkLog.sys("계정 원본 파일을 읽지 못해 기본 데이터로 시작 (${e.javaClass.simpleName})")
            null
        }

    /** 저장에 실패해도 앱은 메모리의 값으로 계속 돈다. */
    private suspend fun save(value: NaitaeData) {
        withContext(Dispatchers.IO) {
            try {
                val text = json.encodeToString(NaitaeData.serializer(), value)
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
                SdkLog.sys("계정 원본 저장 실패 (${e.javaClass.simpleName})")
            }
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            // 메모 알림 방식(MemoRemind)처럼 여러 모양이 있는 값은 "type" 으로 구분해 저장한다.
            classDiscriminator = "type"
        }
    }
}
