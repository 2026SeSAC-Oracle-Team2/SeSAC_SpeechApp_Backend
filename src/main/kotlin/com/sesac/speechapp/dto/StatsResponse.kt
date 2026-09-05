package com.sesac.speechapp.dto

import java.math.BigDecimal

// D-8②b: GET /api/v1/users/me/stats — 홈 통계 (05a §8.4)
// ⚠️ scores(ADR-009 대표점수 = 최근 20 상위 10)와 다른 식 — 이건 "최근 10개 전부 평균".
class StatsResponse(
    val streakDays: Int,
    val avgScore: BigDecimal?,
    val deltaScore: BigDecimal?
)
