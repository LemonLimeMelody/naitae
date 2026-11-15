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
    fun detect(data: NaitaeData, currentVin: String, today: LocalDate): HandoverSummary? {
        if (currentVin.isBlank()) return null
        val current = data.currentCar ?: return null
        if (current.vin == currentVin) return null
        return HandoverSummary(
            fromCar = current,
            toVin = currentVin,
            ringCount = ringCount(data, today),
            topicCount = data.topics.size,
            recordCount = data.records.size,
        )
    }

    /** 예전 차를 보낸 차로 표시하고 새 차를 등록한다. 기록·일정·메모는 계정에 그대로 남는다. */
    fun apply(data: NaitaeData, summary: HandoverSummary, newModel: String, today: LocalDate): NaitaeData {
        // 이미 이어받았으면 그대로 둔다(두 번 불려도 차가 겹쳐 등록되지 않게).
        if (data.currentCar?.vin == summary.toVin) return data
        val day = today.toEpochDay()
        val retired = data.cars.map { if (it.retiredEpochDay == null) it.copy(retiredEpochDay = day) else it }
        return data.copy(cars = retired + CarProfile(vin = summary.toVin, model = newModel, startEpochDay = day))
    }

    /** 첫 실행: 등록된 차가 없으면 지금 차를 첫 차로 등록한다. */
    fun registerFirstCar(data: NaitaeData, vin: String, model: String, today: LocalDate): NaitaeData {
        if (data.cars.isNotEmpty() || vin.isBlank()) return data
        return data.copy(cars = listOf(CarProfile(vin = vin, model = model, startEpochDay = today.toEpochDay())))
    }

    /**
     * 가장 오래된 기록이 있는 주제의 나이테에서 이어받는 겹 수를 센다(RingBuilder 와 같은 규칙).
     * 기록이 없으면 0.
     */
    private fun ringCount(data: NaitaeData, today: LocalDate): Int {
        val day = today.toEpochDay()
        val oldest = data.records.filter { it.epochDay <= day }.minByOrNull { it.epochDay } ?: return 0
        return RingBuilder.grownRingCount(RingBuilder.rings(data, oldest.topicId, today))
    }
}
