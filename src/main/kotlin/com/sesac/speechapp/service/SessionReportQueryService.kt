package com.sesac.speechapp.service

import com.sesac.speechapp.dto.session.AnswerDto
import com.sesac.speechapp.dto.session.BriefReportData
import com.sesac.speechapp.dto.session.MetricCardDto
import com.sesac.speechapp.dto.session.MetricTurnDto
import com.sesac.speechapp.dto.session.RadarDto
import com.sesac.speechapp.dto.session.SessionHistoryItem
import com.sesac.speechapp.dto.session.SessionHistoryResponse
import com.sesac.speechapp.dto.session.SessionReportData
import com.sesac.speechapp.dto.session.TalkHistoryItem
import com.sesac.speechapp.entity.Turn
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.TurnImageRepository
import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.VoiceRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 세션 리포트 조회 서비스 (D-8② 분할, 2026-09-06 — 동작 불변).
 *
 * SessionFlowService에서 이동: getHistory(05a §8.2)·getSessionReport(§8.3) — 읽기 전용
 * (getSessionReport만 REPORT_VIEWED_AT 기록 쓰기 포함 — 기존 @Transactional 유지).
 * 로직 전부 SessionFlowService 9521f2b 기준 그대로 — 응답 키·radar 집계 무변경.
 */
@Service
class SessionReportQueryService(
    private val sessionRepository: SessionRepository,
    private val turnRepository: TurnRepository,
    private val turnImageRepository: TurnImageRepository,
    private val voiceRecordRepository: VoiceRecordRepository
) {
    private val logger = LoggerFactory.getLogger(SessionReportQueryService::class.java)

    // ============================================================
    // [4.1] GET /users/me/sessions/history — 기록 카드 (05a §8.2)
    // ============================================================
    @Transactional(readOnly = true)
    fun getHistory(userId: Long): SessionHistoryResponse {
        // 필터: STATUS != COMPLETED_NO_TALK AND AQ IS NOT NULL
        // (AQ null = 간이 보고서 미생성 세션 — 카드에 AQ 표시 불가라 제외.
        //  학습 중간에 나간 IN_PROGRESS 세션도 자연 배제됨. 05a §8.2 규약에 규약 추가 기재)
        val rows = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .filter { it.status != "COMPLETED_NO_TALK" && it.aq != null }
        return SessionHistoryResponse(
            sessions = rows.map { s ->
                SessionHistoryItem(
                    sessionId = s.id!!,
                    sessionName = s.sessionName,
                    createdAt = s.createdAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    aq = s.aq
                )
            }
        )
    }

    // ============================================================
    // [4.2] GET /sessions/{id}/report — 세부 보고서 (05a §8.3)
    // ============================================================

    /**
     * 세부 보고서 — radar(세션 TURN 집계) + metricCards(4지표 카드) + talkHistory.
     * - 학습 중단 세션(COMPLETED_NO_TALK) → E0404 (리스트에도 없으니 직접 호출도 차단)
     * - userId 소유 검증 필수 (permitAll 경로 — 타 유저 세션 조회 방어)
     * - 응답 수신 시 REPORT_VIEWED_AT null이면 기록 (구현 단순성 기준: null일 때만 기록)
     */
    @Transactional
    fun getSessionReport(sessionId: Long, userId: Long): SessionReportData {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }
        if (session.userId != userId) {
            throw IllegalArgumentException("세션 소유 사용자만 조회할 수 있습니다 (sessionId=$sessionId)")
        }
        if (session.status == "COMPLETED_NO_TALK") {
            throw NoSuchElementException("학습 중단 세션은 세부 보고서가 없습니다 (sessionId=$sessionId)")
        }

        val turns = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)

        // radar: content_type별 평균 — LISTEN_TEXT+LISTEN_PICTURE 통합=LISTEN (4축).
        // score NULL 턴 제외, 해당 타입에 SCORED 턴이 없으면 null.
        val radar = RadarDto(
            listen = avgScoreOf(turns, listOf("LISTEN_TEXT", "LISTEN_PICTURE")),
            naming = avgScoreOf(turns, listOf("NAMING")),
            shadowing = avgScoreOf(turns, listOf("SHADOWING")),
            selfTalk = avgScoreOf(turns, listOf("SELF_TALK"))
        )

        // metricCards: 4지표 카드 — feedback=LEARNING_SESSION.*_feedback(간이 보고서 적재분)
        val metricCards = listOf(
            metricCard("LISTEN", session.listenFeedback, turns, listOf("LISTEN_TEXT", "LISTEN_PICTURE")),
            metricCard("NAMING", session.namingFeedback, turns, listOf("NAMING")),
            metricCard("SHADOWING", session.shadowingFeedback, turns, listOf("SHADOWING")),
            metricCard("SELF_TALK", session.selfTalkFeedback, turns, listOf("SELF_TALK"))
        )

        // talkHistory: STORYTELLING 턴 — speaker=AI|USER, VOICE_RECORD 매핑
        val talkTurnRows = turns.filter { it.contentType == "STORYTELLING" }
        val talkHistory = talkTurnRows.flatMap { t ->
            val aiVoice = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "AI" }
            buildList {
                if (t.promptText != null) {
                    add(
                        TalkHistoryItem(
                            speaker = "AI",
                            text = t.promptText!!,
                            ttsUrl = aiVoice?.let { "/api/v1/voice/${it.id}" }
                        )
                    )
                }
                if (t.answerText != null) {
                    val userVoice = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "USER" }
                    add(
                        TalkHistoryItem(
                            speaker = "USER",
                            text = t.answerText!!,
                            voiceUrl = userVoice?.let { "/api/v1/voice/${it.id}" }
                        )
                    )
                }
            }
        }

        // REPORT_VIEWED_AT — null일 때만 기록 (구현 단순성 우선 — 05a §8.3 규약)
        var recordedViewedAt: LocalDateTime? = null
        if (session.reportViewedAt == null) {
            val now = LocalDateTime.now()
            session.reportViewedAt = now
            recordedViewedAt = now
            logger.info("[D-5] REPORT_VIEWED_AT 최초 기록: sessionId={}, at={}", sessionId, now)
        }

        return SessionReportData(
            sessionId = sessionId,
            aq = session.aq,
            totalFeedback = session.totalFeedback,
            radar = radar,
            metricCards = metricCards,
            talkFeedback = session.talkFeedback,
            talkHistory = talkHistory,
            reportViewedAt = recordedViewedAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ?: session.reportViewedAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    /**
     * [e2e4-E-3] 간이보고서용 metricCards 조립 — GET /report와 동일 구조(radar+4지표
     * 확장 카드), talkHistory만 제외. 사용자 계약: "세부보고서 양식을 그대로 가져다
     * 쓰되, AI대화 피드백 및 대화 내역만 지우면 됨".
     * finish 세션은 8턴 SCORED 확정이라 E0404 위험 없음 — [A] 비동기 채점 정합.
     * SessionReportQueryService를 SessionScoringService에 주입해 재사용(로직 단일화).
     */
    @Transactional(readOnly = true)
    fun buildBriefReportData(sessionId: Long, userId: Long): BriefReportData {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }
        if (session.userId != userId) {
            throw IllegalArgumentException("세션 소유 사용자만 조회할 수 있습니다 (sessionId=$sessionId)")
        }
        val turns = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
        val radar = RadarDto(
            listen = avgScoreOf(turns, listOf("LISTEN_TEXT", "LISTEN_PICTURE")),
            naming = avgScoreOf(turns, listOf("NAMING")),
            shadowing = avgScoreOf(turns, listOf("SHADOWING")),
            selfTalk = avgScoreOf(turns, listOf("SELF_TALK"))
        )
        val metricCards = listOf(
            metricCard("LISTEN", session.listenFeedback, turns, listOf("LISTEN_TEXT", "LISTEN_PICTURE")),
            metricCard("NAMING", session.namingFeedback, turns, listOf("NAMING")),
            metricCard("SHADOWING", session.shadowingFeedback, turns, listOf("SHADOWING")),
            metricCard("SELF_TALK", session.selfTalkFeedback, turns, listOf("SELF_TALK"))
        )
        return BriefReportData(radar = radar, metricCards = metricCards)
    }

    private fun metricCard(
        type: String,
        feedback: String?,
        turns: List<Turn>,
        contentTypes: List<String>
    ): MetricCardDto {
        val typeTurns = turns.filter { it.contentType in contentTypes }
        val score = avgScoreOf(turns, contentTypes)
        return MetricCardDto(
            type = type,
            score = score,
            feedback = feedback,
            turns = typeTurns.map { t ->
                MetricTurnDto(
                    turnId = t.id!!,
                    turnNumber = t.turnNumber,
                    promptText = t.promptText,
                    ttsUrl = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "AI" }
                        ?.let { "/api/v1/voice/${it.id}" },
                    imageUrl = t.turnImageId()?.let { "/api/v1/content/images/$it/file" },
                    answer = buildAnswer(t)
                )
            }
        )
    }

    /**
     * 턴별 답변 조립 (05a §8.3):
     * - LISTEN={mediaType:"text", value: 선택했던 선택지 텍스트, correct: selected==correct} —
     *   selected_value는 1-based order(정수 문자열) → choices_json 역직렬화로 텍스트 추출
     *   (deserializeChoices 선례 재사용).
     * - LISTEN_PICTURE: 선택지 context가 image_id → value는 선택지 context 전달,
     *   mediaType은 선택지 따름("image"). 클라가 이미지 로드 가능 (승인 사안 C).
     * - 음성형={mediaType:"voice", value: answer_text(STT), voiceUrl}
     */
    private fun buildAnswer(t: Turn): AnswerDto? {
        return when (t.contentType) {
            "LISTEN_TEXT", "LISTEN_PICTURE" -> {
                val selected = t.selectedValue ?: return AnswerDto(mediaType = "text", value = null, correct = null)
                val choices = SessionTurnSupport.deserializeChoices(t.choicesJson)
                val selectedChoice = choices?.firstOrNull { it.order.toString() == selected }
                val correct = t.correctValue != null && selected == t.correctValue
                AnswerDto(
                    mediaType = selectedChoice?.mediaType?.lowercase() ?: "text",
                    value = selectedChoice?.context,   // LISTEN_TEXT=선택했던 텍스트 / LISTEN_PICTURE=image_id
                    correct = correct
                )
            }
            // [e2e4-E-4] 정답 표시 규약 변경 (사용자 계약 원문):
            //  · "보고서에서 따라말하기, 이름대기의 정답 필드에 문제의 정답이 아닌
            //    유저의 답변이 표기되고 있음 (TURN.ANSWER_TEXT가 아닌
            //    TURN.PROMPT_TEXT를 쓰면 됨)" → value = promptText(문제의 정답/지문)
            //  · "스스로말하기의 정답이 표시되지 않도록 변경 필요(정답이 표시될
            //    필요가 없는 유형임)" → AnswerDto null 반환 — 클라가 정답 행을
            //    렌더하지 않는다.
            //  · LISTEN은 미접촉 — 기존 계약 유지(LISTEN_PICTURE 이미지 정답 정상 확인).
            "NAMING", "SHADOWING" -> {
                AnswerDto(
                    mediaType = "voice",
                    value = t.promptText,   // 문제의 정답(이름대기=정답 단어/따라말하기=원문)
                    correct = null,
                    voiceUrl = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "USER" }
                        ?.let { "/api/v1/voice/${it.id}" }
                )
            }
            // [e2e6-P-2] 정답 미표시 계약(e2e4-E4) 유지 + "내가 말한 답변" 재생 복원
            // (사용자 계약: "스스로말하기 내가 말한 답변 버튼이 없음"). value=null로
            // 정답 행을 렌더하지 않되 voiceUrl만 제공 — 클라 bindPlayer 활성.
            "SELF_TALK" -> AnswerDto(
                mediaType = "voice",
                value = null,   // 정답 개념 없는 유형 — value null로 미노출 (클라 렌더 금지)
                correct = null,
                voiceUrl = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "USER" }
                    ?.let { "/api/v1/voice/${it.id}" }
            )
            else -> {
                // 방어 분기 — contentType은 CHECK 제약 6종으로 한정되지만 STORYTELLING
                // 등이 들어오면 기존 규약(유저 답변)을 유지한다.
                AnswerDto(
                    mediaType = "voice",
                    value = t.answerText,
                    correct = null,
                    voiceUrl = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "USER" }
                        ?.let { "/api/v1/voice/${it.id}" }
                )
            }
        }
    }

    private fun Turn.turnImageId(): Long? =
        turnImageRepository.findByTurnIdOrderByImageOrderAsc(this.id!!).firstOrNull()?.imageId

    /** content_type별 TURN.score 평균 — score NULL 턴 제외, 대상 없으면 null */
    private fun avgScoreOf(turns: List<Turn>, contentTypes: List<String>): BigDecimal? =
        turns.filter { it.contentType in contentTypes && it.score != null }
            .map { it.score!! }
            .takeIf { it.isNotEmpty() }
            ?.let { list ->
                list.reduce { acc, d -> d + acc }.divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP)
            }
}