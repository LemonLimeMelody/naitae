package com.naitae.connect.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/* 읽어 줄 문장을 만드는 데 쓰는 한국어 도우미 (domain 내부용) */

/** 메모 생성 시각 등 날짜·시각을 epoch 로 바꿀 때 쓰는 기준 시간대(기기 설정과 무관하게 같은 값이 나오도록 고정) */
internal val NAITAE_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

internal object Ko {
    /** "HH:mm" → 자정부터의 분. 형식이 맞지 않으면 null */
    fun minutesOf(time: String?): Int? {
        if (time == null) return null
        val parts = time.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** "10:00" → "오전 10시", "19:00" → "저녁 7시", "05:30" → "새벽 5시 반" */
    fun spokenTime(time: String?): String? {
        val minutes = minutesOf(time) ?: return null
        val h = minutes / 60
        val m = minutes % 60
        val (period, hour12) = when {
            h == 0 -> "밤" to 12
            h < 6 -> "새벽" to h
            h < 12 -> "오전" to h
            h == 12 -> "낮" to 12
            h < 18 -> "오후" to (h - 12)
            h < 22 -> "저녁" to (h - 12)
            else -> "밤" to (h - 12)
        }
        val minutePart = when (m) {
            0 -> ""
            30 -> " 반"
            else -> " ${m}분"
        }
        return "$period ${hour12}시$minutePart"
    }

    fun weekday(date: LocalDate): String = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "월요일"
        DayOfWeek.TUESDAY -> "화요일"
        DayOfWeek.WEDNESDAY -> "수요일"
        DayOfWeek.THURSDAY -> "목요일"
        DayOfWeek.FRIDAY -> "금요일"
        DayOfWeek.SATURDAY -> "토요일"
        else -> "일요일"
    }

    /** "11월 12일 목요일" */
    fun dateLabel(date: LocalDate): String = "${date.monthValue}월 ${date.dayOfMonth}일 ${weekday(date)}"

    /** 남은 날을 말로: 2 → "이틀" */
    fun dayCount(days: Int): String = when (days) {
        1 -> "하루"
        2 -> "이틀"
        3 -> "사흘"
        4 -> "나흘"
        5 -> "닷새"
        6 -> "엿새"
        7 -> "일주일"
        else -> "${days}일"
    }

    /** 마지막 글자에 받침이 있는지(조사 고르기용). 숫자는 읽는 소리 기준. */
    fun hasBatchim(word: String): Boolean {
        val c = word.lastOrNull { it.isLetterOrDigit() } ?: return false
        return when {
            c.code in 0xAC00..0xD7A3 -> (c.code - 0xAC00) % 28 != 0
            c.isDigit() -> c in "013678"
            else -> false
        }
    }

    /** 받침이 있으면 withBatchim, 없으면 without 을 붙인다. 예: josa("미팅", "이", "가") → "미팅이" */
    fun josa(word: String, withBatchim: String, without: String): String =
        word + if (hasBatchim(word)) withBatchim else without

    /** 문장 끝에 마침표가 없으면 붙인다. */
    fun sentence(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        val last = t.last()
        return if (last == '.' || last == '!' || last == '?') t else "$t."
    }
}
