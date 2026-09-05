package com.sesac.speechapp.service

import com.sesac.speechapp.entity.UserProfile
import com.sesac.speechapp.entity.UserRepresentativeScore
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.UserProfileRepository
import com.sesac.speechapp.repository.UserRepresentativeScoreRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 리포트 응답 DB 적용자 — 백그라운드 워커와 트랜잭션 경계를 분리한 전용 클래스 (D-5 [2.2]).
 *
 * ⚠️ REQUIRES_NEW 필수 (지시문 함정 목록): 백그라운드 @Async 스레드에는
 * 메인(제출/finish) 트랜잭션이 존재하지 않지만, 적용 메서드가 같은 클래스 내부 호출로
 * 묶이면 프록시 우회로 트랜잭션 경계가 무시된다 — 별도 서비스 클래스로 분리해
 * 스프링 프록시가 REQUIRES_NEW를 확실히 걸도록 한다.
 * (afterCommit에서 호출되므로 메인 트랜잭션은 이미 커밋됨 — 지연 전파 없음)
 */
@Service
class SessionReportApplier(
    private val sessionRepository: SessionRepository,
    private val turnRepository: TurnRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userRepresentativeScoreRepository: UserRepresentativeScoreRepository
) {
    private val logger = LoggerFactory.getLogger(SessionReportApplier::class.java)

    /**
     * 간이 보고서(/report/problems) 응답 적용 — 갱신 지점 ② (04 §4.2.2):
     * LEARNING_SESSION.AQ + 4지표 피드백 UPDATE + REP_SCORES 재계산 UPDATE (ADR-009).
     * STATUS는 IN_PROGRESS 유지 (종료 판정은 finish).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun applyProblemsReport(sessionId: Long, response: com.sesac.speechapp.dto.aicontainer.ProblemsReportResponse) {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }

        session.aq = response.sessionAQ
        session.listenFeedback = response.sessionFeedbacks.listenFeedback
        session.namingFeedback = response.sessionFeedbacks.namingFeedback
        session.shadowingFeedback = response.sessionFeedbacks.shadowingFeedback
        session.selfTalkFeedback = response.sessionFeedbacks.selfTalkFeedback
        // STATUS 유지 (IN_PROGRESS) — talk/total은 §7.2에서 채움

        // REP_SCORES 갱신 지점 ② — ADR-009 식 재계산.
        // JPQL 벌크 UPDATE 대신 dirty checking 방식 유지 (지시문 함정 목록 —
        // UPDATE는 영속 객체 변경이 안전, deleteByUserId 선례와 반대 사례).
        refreshRepresentativeScores(session.userId)

        logger.info(
            "[D-5] 간이 보고서 적재: sessionId={}, AQ={}, 4지표 피드백 UPDATE + REP_SCORES 갱신 완료",
            sessionId, response.sessionAQ
        )
    }

    /**
     * 상세 보고서(/report/total) 응답 적용:
     * talkFeedback/totalFeedback UPDATE + userMemory 갱신 (D-4 규약 이관 — total 경로).
     * STATUS는 finish에서 이미 확정 — 여기서 건드리지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun applyTotalReport(sessionId: Long, response: com.sesac.speechapp.dto.aicontainer.TotalReportResponse) {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }

        // §7.2 규약: talk/total만 non-null — 백엔드는 null 필드 무시하고 2컬럼만 UPDATE
        response.sessionFeedbacks.talkFeedback?.let { session.talkFeedback = it }
        response.sessionFeedbacks.totalFeedback?.let { session.totalFeedback = it }

        // userMemory 갱신 규약 (03a §10.1 — D-4 finishSession에서 이관): null·누락 → 기존값 유지 /
        // 길이 > 8192문자 → 절단 저장 / 정상 → UPDATE
        val userId = session.userId
        val profile: UserProfile? = userProfileRepository.findByUserId(userId)
        val existingMemory = profile?.userMemory
        val returnedMemory = response.userMemory
        if (returnedMemory != null) {
            val capped = if (returnedMemory.length > SessionFlowService.USER_MEMORY_HARD_CAP) {
                logger.info(
                    "[D-5] userMemory 하드캡 절단: {}자 → {}자 (문자 수 기준)",
                    returnedMemory.length, SessionFlowService.USER_MEMORY_HARD_CAP
                )
                returnedMemory.take(SessionFlowService.USER_MEMORY_HARD_CAP)
            } else {
                returnedMemory
            }
            if (profile != null) {
                profile.userMemory = capped
                userProfileRepository.save(profile)
                logger.info(
                    "[D-5] userMemory 갱신 완료 (total 경로): sessionId={}, 기존={}자 → 신규={}자",
                    sessionId, existingMemory?.length ?: 0, capped.length
                )
            } else {
                logger.warn("[D-5] USER_PROFILE 행 부재로 userMemory 갱신 스킵: userId={}", userId)
            }
        } else {
            logger.info("[D-5] userMemory 응답 null — 기존값 유지 (소실 방지): sessionId={}", sessionId)
        }

        logger.info(
            "[D-5] 상세 보고서 적재: sessionId={}, talk/total 피드백 UPDATE 완료",
            sessionId
        )
    }

    /**
     * REP_SCORES 재계산 — ADR-009: 최근 20세션 중 (AQ/지표별) 상위 10 평균.
     * - AQ: 최근 20세션 중 AQ 보유 세션 상위 10 평균 → Math.ceil 정수
     *   (NUMBER(3) 정수 전용 — AVG 소수는 반올림 저장 기대 동작, D-1 백필 선례)
     * - 지표: 최근 20세션의 SCORED 턴 점수를 지표별 수집 → 상위 10개 평균
     *   (D-1 백필식과 동일 패턴 — TURN 단위 상위 10. 갱신 지점 ② 시점에는
     *   문제풀이 8턴이 전부 SCORED이므로 세션 단위 상위 10과 동일 결과)
     *   LISTEN = LISTEN_TEXT + LISTEN_PICTURE 통합.
     */
    fun refreshRepresentativeScores(userId: Long) {
        val recentSessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId).take(20)
        val sessionIds = recentSessions.mapNotNull { it.id }.toSet()

        val userAq: Int? = recentSessions.mapNotNull { it.aq }
            .takeIf { it.isNotEmpty() }
            ?.let { Math.ceil(it.average()).toInt() }

        // 최근 20세션에 속한 SCORED 턴 점수를 지표별 수집 (repository 전수 조회 후 필터 —
        // 데모 규모에서 충분. findBySessionIdIn 없이 기존 메서드 재사용)
        val problemTurns = turnRepository.findByContentTypeIn(SessionFlowService.PROBLEM_TYPES)
            .filter { it.sessionId in sessionIds && it.score != null }
            .map { Triple(it.contentType, it.score!!, it.sessionId) }

        val listenAvg = top10Avg(problemTurns, listOf("LISTEN_TEXT", "LISTEN_PICTURE"))
        val namingAvg = top10Avg(problemTurns, listOf("NAMING"))
        val shadowingAvg = top10Avg(problemTurns, listOf("SHADOWING"))
        val selfTalkAvg = top10Avg(problemTurns, listOf("SELF_TALK"))

        val existing = userRepresentativeScoreRepository.findByUserId(userId)
        if (existing == null) {
            userRepresentativeScoreRepository.save(
                UserRepresentativeScore(
                    userId = userId,
                    userAq = userAq,
                    userScoreListen = listenAvg,
                    userScoreNaming = namingAvg,
                    userScoreShadowing = shadowingAvg,
                    userScoreSelfTalk = selfTalkAvg
                )
            )
            logger.info("[D-5] REP_SCORES 신규 INSERT (갱신 지점 ②): userId={}, AQ={}", userId, userAq)
        } else {
            existing.userAq = userAq ?: existing.userAq
            listenAvg?.let { existing.userScoreListen = it }
            namingAvg?.let { existing.userScoreNaming = it }
            shadowingAvg?.let { existing.userScoreShadowing = it }
            selfTalkAvg?.let { existing.userScoreSelfTalk = it }
            logger.info(
                "[D-5] REP_SCORES 갱신 (갱신 지점 ②): userId={}, AQ → {}, 지표 갱신 완료",
                userId, userAq
            )
        }
    }

    /** 상위 10개 TURN 평균 (지표별 — LISTEN은 LISTEN_TEXT+LISTEN_PICTURE 통합) */
    private fun top10Avg(rows: List<Triple<String, BigDecimal, Long>>, types: List<String>): BigDecimal? {
        val scores = rows.filter { it.first in types }.map { it.second }
        if (scores.isEmpty()) return null
        val top10 = scores.sortedDescending().take(10)
        val sum = top10.reduce { acc, d -> d + acc }
        return sum.divide(BigDecimal(top10.size), 2, RoundingMode.HALF_UP)
    }
}