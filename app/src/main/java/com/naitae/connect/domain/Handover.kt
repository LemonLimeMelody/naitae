package com.naitae.connect.domain

import com.naitae.connect.core.model.CarProfile
import com.naitae.connect.core.model.NaitaeData
import java.time.LocalDate

/* 이어진다: 이어받기 (계약) */

data class HandoverSummary(
    val fromCar: CarProfile?,
    val toVin: String,
    /** 이어받는 나이테의 겹 수(가장 오래된 기록부터의 햇수) */
    val ringCount: Int,
    val topicCount: Int,
    val recordCount: Int,
)

object HandoverEngine {
    /** 지금 차의 VIN 이 계정에 등록된 현재 차와 다르면 이어받기 대상이다. 같으면 null. 등록된 차가 없으면(첫 차) null. */
    fun detect(data: NaitaeData, currentVin: String, today: LocalDate): HandoverSummary? = TODO("domain")

    /** 예전 차를 보낸 차로 표시하고 새 차를 등록한다. 기록·일정·메모는 계정에 그대로 남는다. */
    fun apply(data: NaitaeData, summary: HandoverSummary, newModel: String, today: LocalDate): NaitaeData = TODO("domain")

    /** 첫 실행: 등록된 차가 없으면 지금 차를 첫 차로 등록한다. */
    fun registerFirstCar(data: NaitaeData, vin: String, model: String, today: LocalDate): NaitaeData = TODO("domain")
}
