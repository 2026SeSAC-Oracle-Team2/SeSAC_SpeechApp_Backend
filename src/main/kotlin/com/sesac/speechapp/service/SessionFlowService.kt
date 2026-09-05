package com.sesac.speechapp.service

import com.sesac.speechapp.dto.session.FinishData
import com.sesac.speechapp.dto.session.HintData
import com.sesac.speechapp.dto.session.ListenSubmitData
import com.sesac.speechapp.dto.session.SessionCreateData
import com.sesac.speechapp.dto.session.SessionHistoryResponse
import com.sesac.speechapp.dto.session.SessionReportData
import com.sesac.speechapp.dto.session.TalkData
import com.sesac.speechapp.dto.session.VoiceSubmitData
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

/**
 * 세션 플로우 퍼사드 (05a §3 + 03a §2·§7 — v1.9 계약 기준).
 *
 * ⚠️ D-8② 분할 (2026-09-06, 동작 불변): 실제 로직은 관심사별 서비스로 이동됐다.
 *  - 세션 생성 → [SessionCreationService] (today/theme 분기·이미지 풀 3분할·TURN INSERT)
 *  - 문제 제출·힌트·이야기 턴·finish·problems 트리거 → [SessionScoringService]
 *  - 대시보드 조회(기록 카드·세부 보고서) → [SessionReportQueryService]
 *  - 발화지표 산정식 → [ScoreCalculationService]
 *  - 공용 순수 헬퍼 → [SessionTurnSupport]
 *
 * 이 클래스는 컨트롤러가 호출하는 퍼사드만 유지한다 — 컨트롤러 시그니처 전부 무변경
 * (동작 불변의 핵심). 퍼사드 → 신규 서비스는 빈 주입 호출(프록시 정상 경계).
 *
 * ⚠️ 트랜잭션 경계 보존: 각 신규 서비스의 @Transactional은 원본과 동일 위치·동일 속성.
 * finish의 afterCommit 백그라운드 트리거와 REQUIRES_NEW 적용자(SessionReportApplier)
 * 구조는 이 프록시 구조에 의존 — 분할 시 public 메서드 경계 유지.
 *
 * companion 상수(USER_MEMORY_HARD_CAP·PROBLEM_TYPES·ALLOWED_THEMAS)는
 * SessionReportApplier·Worker·SessionCreationService 참조 호환을 위해 여기에 유지.
 */
@Service
class SessionFlowService(
    private val sessionCreationService: SessionCreationService,
    private val sessionScoringService: SessionScoringService,
    private val sessionReportQueryService: SessionReportQueryService
) {
    // ============================================================
    // [1] POST /api/v1/sessions/today · /theme — 세션 생성 (8문제 일괄)
    //     v2는 하위호환 유지(클라 데모용) — 동일 내부 로직 호출
    // ============================================================
    @Transactional
    fun createSessionToday(userId: Long): SessionCreateData = sessionCreationService.createSessionToday(userId)

    @Transactional
    fun createSessionTheme(userId: Long, thema: String): SessionCreateData = sessionCreationService.createSessionTheme(userId, thema)

    @Transactional
    fun createSession(userId: Long, sessionType: String, thema: String?): SessionCreateData =
        sessionCreationService.createSession(userId, sessionType, thema)

    // ============================================================
    // 4.3 LISTEN — 백엔드 자체 채점 (ADR-003, 즉시 응답)
    // ============================================================
    @Transactional
    fun submitListen(sessionId: Long, turnId: Long, selected: Int): ListenSubmitData =
        sessionScoringService.submitListen(sessionId, turnId, selected)

    // ============================================================
    // 4.3 NAMING / SHADOWING / SELF_TALK — 컨테이너 채점
    // ============================================================
    @Transactional
    fun submitNaming(sessionId: Long, turnId: Long, userId: Long, file: MultipartFile): VoiceSubmitData =
        sessionScoringService.submitNaming(sessionId, turnId, userId, file)

    @Transactional
    fun submitShadowing(sessionId: Long, turnId: Long, userId: Long, file: MultipartFile): VoiceSubmitData =
        sessionScoringService.submitShadowing(sessionId, turnId, userId, file)

    @Transactional
    fun submitSelfTalk(sessionId: Long, turnId: Long, userId: Long, file: MultipartFile): VoiceSubmitData =
        sessionScoringService.submitSelfTalk(sessionId, turnId, userId, file)

    // ============================================================
    // 4.4 NAMING 힌트 — 의미단서 → 조음단서 (ADR-004)
    // ============================================================
    @Transactional
    fun getHint(sessionId: Long, turnId: Long): HintData = sessionScoringService.getHint(sessionId, turnId)

    // ============================================================
    // 4.5 이야기 턴 (STORYTELLING) — 8턴 하드캡 (유저 답변 수 기준, 승인 사안 A)
    // ============================================================
    @Transactional
    fun talk(sessionId: Long, userId: Long, file: MultipartFile?): TalkData =
        sessionScoringService.talk(sessionId, userId, file)

    // ============================================================
    // [2] 세션 종료 — 간이 보고서 응답 + 리포트 2단계 트리거
    // ============================================================
    @Transactional
    fun finishSession(sessionId: Long, userId: Long): FinishData =
        sessionScoringService.finishSession(sessionId, userId)

    // ============================================================
    // [4.1] GET /users/me/sessions/history — 기록 카드 (05a §8.2)
    // ============================================================
    @Transactional(readOnly = true)
    fun getHistory(userId: Long): SessionHistoryResponse = sessionReportQueryService.getHistory(userId)

    // ============================================================
    // [4.2] GET /sessions/{id}/report — 세부 보고서 (05a §8.3)
    // ============================================================
    @Transactional
    fun getSessionReport(sessionId: Long, userId: Long): SessionReportData =
        sessionReportQueryService.getSessionReport(sessionId, userId)

    companion object {
        /**
         * USER_MEMORY 하드캡 (04 v2.6 §4.2): 8KB = 8192 **문자** 기준.
         * ⚠️ CLOB LENGTH()는 문자 수 — UTF-8 바이트와 다름. 절단·실측 모두 문자 수로 통일.
         */
        const val USER_MEMORY_HARD_CAP = 8192

        /** 문제풀이 5종 — STORYTELLING 제외 (TURN 집계·8문제 완료 감지 공용) */
        val PROBLEM_TYPES = listOf("LISTEN_TEXT", "LISTEN_PICTURE", "NAMING", "SHADOWING", "SELF_TALK")

        /** 테마 유효성 (D-5 [1.2] — IMAGE_THEMA CHECK 6종 중 EASY 기본 3종) */
        val ALLOWED_THEMAS = setOf("TEST", "HOSPITAL", "CAFE")
    }
}