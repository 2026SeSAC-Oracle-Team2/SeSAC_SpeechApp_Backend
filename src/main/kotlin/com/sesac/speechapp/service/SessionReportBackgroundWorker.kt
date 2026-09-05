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