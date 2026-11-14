package com.naitae.connect.data

import com.naitae.connect.core.model.NaitaeData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 구현 예정(data 담당): JSON 파일 저장소. 파일이 없거나 깨졌으면 seed() 로 시작한다. */
class JsonFileRepository(
    private val file: File,
    seed: () -> NaitaeData,
) : NaitaeRepository {
    private val _data = MutableStateFlow(seed())
    override val data: StateFlow<NaitaeData> = _data

    override suspend fun update(transform: (NaitaeData) -> NaitaeData): NaitaeData {
        val next = transform(_data.value)
        _data.value = next
        return next
    }

    override suspend fun replace(data: NaitaeData) {
        _data.value = data
    }
}
