package com.sesac.speechapp.service

import com.sesac.speechapp.entity.Session

/**
 * 완료 세션 공용 프레디케이트 (D-8②b — history·stats 공용).
 *
 * 규약: STATUS != COMPLETED_NO_TALK **AND** AQ IS NOT NULL
 * - 학습 중단 세션(COMPLETED_NO_TALK) 제외 — history 제외 규약(05a §8.2)과 동일 원칙.
 * - AQ NULL 세션(간이 보고서 미생성·IN_PROGRESS)도 제외.
 * - ⚠️ 실측 근거(2026-09-06): COMPLETED_NO_TALK에도 간이 AQ가 적재되는 행이 존재
 *   (D-5 지점② — 세션 89, AQ=90). 단일 `aq != null` 필터만 쓰면 streak/history가
 *   중단 세션으로 오염되므로 반드시 복합 조건.
 *
 * history와 stats가 이 프레디케이트를 공유해야 두 집계가 어긋나지 않는다.
 */
object CompletedSessionFilter {
    fun isCompletedWithAq(s: Session): Boolean =
        s.status != "COMPLETED_NO_TALK" && s.aq != null
}