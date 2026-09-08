package com.sesac.speechapp.service

import com.sesac.speechapp.dto.aicontainer.ProblemsReportRequest
import com.sesac.speechapp.dto.aicontainer.TotalReportRequest
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * 리포트 백그라운드 워커 (D-5 [2.2]) — @Async 실행만 담당하는 얇은 레이어.
 *
 * - 트리거: afterCommit 훅 (SessionFlowService) — 메인 트랜잭션 커밋 후 실행
 * - 실행: sessionReportExecutor 스레드풀 (AsyncConfig) — 스텁 sleep이
 *   메인 HTTP 응답을 막지 않는다 (total 10초 감안)
 * - DB 적용은 SessionReportApplier(REQUIRES_NEW)로 위임 — 트랜잭션 경계 분리
 *
 * 실패 정책: 백그라운드 실패는 로그만 남기고 삼킨다 — 제출/finish 응답은 이미 반환됐고
 * 사용자에게 노출되는 실패가 아니며, 간이/상세 보고서는 다음 세션에서 재계산 보정 가능
 * (REP_SCORES는 갱신 지점 ②마다 전면 재계산 — 부분 실패가 누적되지 않음).
 */
@Component
class SessionReportBackgroundWorker(
    private val aiContainerClient: com.sesac.speechapp.ai.AiContainerClient,
    private val applier: SessionReportApplier,
    private val sessionRepository: SessionRepository,
    private val turnRepository: com.sesac.speechapp.repository.TurnRepository,
    private val userProfileRepository: UserProfileRepository
) {
    private val logger = LoggerFactory.getLogger(SessionReportBackgroundWorker::class.java)

    /** 8문제 채점 완료 → /report/problems 자동 호출 (03a §7.1) */
    @Async("sessionReportExecutor")
    open fun generateProblemsReportInBackground(sessionId: Long, userId: Long) {
        try {
            val session = sessionRepository.findById(sessionId).orElse(null)
            if (session == null) {
                logger.warn("[D-5] problems 리포트 스킵 — 세션 없음: {}", sessionId)
                return
            }
            val turnResults = buildProblemTurnResults(sessionId)
            logger.info(
                "[D-5] /report/problems 백그라운드 호출 시작: sessionId={}, turns={}",
                sessionId, turnResults.size
            )
            val response = aiContainerClient.generateProblems(
                ProblemsReportRequest(
                    sessionId = sessionId,
                    userId = userId,
                    turns = turnResults
                )
            )
            applier.applyProblemsReport(sessionId, response)
        } catch (e: Exception) {
            logger.error("[D-5] /report/problems 백그라운드 실패 (로그만 남기고 계속): sessionId={}", sessionId, e)
        }
    }

    /** 세션 종료(학습 완료) → /report/total 백그라운드 호출 (03a §7.2) */
    @Async("sessionReportExecutor")
    open fun generateTotalReportInBackground(sessionId: Long, userId: Long) {
        try {
            val profile = userProfileRepository.findByUserId(userId)
            val existingMemory = profile?.userMemory
            val turnResults = buildProblemTurnResults(sessionId)
            val talkContext = buildTalkContext(sessionId)
            logger.info(
                "[D-5] /report/total 백그라운드 호출 시작: sessionId={}, turns={}, talkContext={}건, userMemory 기존={}자",
                sessionId, turnResults.size, talkContext.size, existingMemory?.length ?: 0
            )
            val response = aiContainerClient.generateTotal(
                TotalReportRequest(
                    sessionId = sessionId,
                    userId = userId,
                    userMemory = existingMemory,
                    turns = turnResults,
                    talkContext = talkContext
                )
            )
            applier.applyTotalReport(sessionId, response)
        } catch (e: Exception) {
            logger.error("[D-5] /report/total 백그라운드 실패 (로그만 남기고 계속): sessionId={}", sessionId, e)
        }
    }

    /**
     * [e2e3-A] 음성 문제 채점 백그라운드 워커 — 컨테이너 호출+SCORED 적재+8턴 감지 재시도.
     *
     * 제출(동기)은 saveUserVoice+SUBMITTED 전환까지만 하고 즉시 반환 (사용자 계약:
     * "녹음 업로드 확인까지만 되면 클라이언트가 다음으로 진행"). 실제 채점(STT+LLM,
     * 실측 naming 12.6s)은 여기서 실행.
     * - 채점 적재는 applier.applyVoiceScored(REQUIRES_NEW)로 위임 — 트랜잭션 경계 분리
     *   (리포트 워커·applier와 동일 구조. 백그라운드 스레드엔 메인 트랜잭션 없음).
     * - 적재 후 8턴 감지 재판정: 트리거 조건을 "8행 전부 SCORED"에서 "8행 전부
     *   SUBMITTED 이상"으로 완화(지시서 [A] 설계) — 각 채점 완료 시점에 재판정되므로
     *   채점이 늦어도 마지막 SCORED 적재 시점에 트리거된다.
     * - 멱등 가드: LEARNING_SESSION.AQ IS NOT NULL이면 이미 적재 완료 — 재호출 금지.
     * - 실패 정책: 리포트 워커와 동일 — 로그만 남기고 삼킨다 (제출 응답은 이미 반환됨).
     */
    @Async("sessionReportExecutor")
    open fun scoreVoiceInBackground(
        sessionId: Long,
        turnId: Long,
        userId: Long,
        contentType: String,
        objectKey: String
    ) {
        try {
            // REQUIRES_NEW 적재 — 반환 시점에 커밋 완료 (백그라운드 스레드라 afterCommit
            // 등록 대신 반환 후 직접 후속 단계를 실행한다).
            applier.applyVoiceScored(sessionId, turnId, userId, contentType, objectKey)
            // [e2e3-A] 채점 완료 콜백에서 8턴 감지 재판정 (멱등 — aq IS NOT NULL 가드).
            maybeTriggerProblemsReport(sessionId)
        } catch (e: Exception) {
            logger.error(
                "[e2e3-A] 음성 채점 백그라운드 실패 (로그만 남기고 계속): sessionId={}, turnId={}",
                sessionId, turnId, e
            )
        }
    }

    /**
     * [e2e3-A] 8문제 완료 감지 재판정 — SessionScoringService.maybeTriggerProblemsReport의
     * 완화 버전(전부 SUBMITTED 이상 + aq IS NOT NULL 멱등 가드). 비동기 스레드에서 직접
     * 호출하므로 afterCommit 등록 없이 즉시 워커 호출 (applier의 REQUIRES_NEW는 이미 커밋됨).
     */
    private fun maybeTriggerProblemsReport(sessionId: Long) {
        val scored = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType in com.sesac.speechapp.service.SessionFlowService.PROBLEM_TYPES }
        if (scored.size != 8 || scored.any { it.status == "PENDING" }) return

        val session = sessionRepository.findById(sessionId).orElse(null) ?: return
        if (session.aq != null) return  // 멱등 가드 — 간이보고서 이미 적재됨 (재호출 금지)
        val userId = session.userId
        logger.info("[e2e3-A] 8문제 채점 완료 감지(비동기 재판정): sessionId={} → /report/problems 백그라운드 호출", sessionId)
        generateProblemsReportInBackground(sessionId, userId)
    }

    /** 문제풀이 8턴 결과 (SessionFlowService.buildTurnResults와 동일 로직 — 비동기 컨텍스트 재구성) */
    private fun buildProblemTurnResults(sessionId: Long): List<com.sesac.speechapp.dto.aicontainer.TurnResult> =
        turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType != "STORYTELLING" }
            .map { t ->
                com.sesac.speechapp.dto.aicontainer.TurnResult(
                    turnId = t.turnNumber,
                    type = toContainerType(t.contentType),
                    context = t.correctValue ?: t.promptText,
                    userAnswer = t.answerText ?: t.selectedValue,
                    score = t.score
                )
            }

    /** talkContext (§7.2) — 답변 완료 턴까지만 (SessionFlowService와 동일 로직) */
    private fun buildTalkContext(sessionId: Long): List<com.sesac.speechapp.dto.aicontainer.ChatMessage> =
        turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType == "STORYTELLING" }
            .flatMap { t ->
                listOfNotNull(
                    t.promptText?.let { com.sesac.speechapp.dto.aicontainer.ChatMessage("AI", it) },
                    t.answerText?.let { com.sesac.speechapp.dto.aicontainer.ChatMessage("USER", it) }
                )
            }

    /** seed 코드 → 컨테이너 소문자 타입 (SessionFlowService와 동일 매핑) */
    private fun toContainerType(seedCode: String): String = when (seedCode) {
        "SELF_TALK" -> "selfTalk"
        "LISTEN_TEXT" -> "listenText"
        "LISTEN_PICTURE" -> "listenPicture"
        else -> seedCode.lowercase()
    }
}