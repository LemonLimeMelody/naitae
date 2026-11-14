package com.naitae.connect.data

import com.naitae.connect.core.model.NaitaeData
import kotlinx.coroutines.flow.StateFlow

/*
 * 저장소 계약 (계약 파일). 「나이테」 계정 원본을 통째로 들고 있고, 바뀌면 파일에 저장한다.
 * 프로토타입은 서버 대신 앱 내부 JSON 파일을 계정 원본으로 쓴다.
 */
interface NaitaeRepository {
    val data: StateFlow<NaitaeData>

    /** 원자적으로 바꾸고 저장한다. 바뀐 값을 돌려준다. */
    suspend fun update(transform: (NaitaeData) -> NaitaeData): NaitaeData

    /** 통째로 바꾼다(시나리오 초기화, 이어받기 시연). */
    suspend fun replace(data: NaitaeData)
}
