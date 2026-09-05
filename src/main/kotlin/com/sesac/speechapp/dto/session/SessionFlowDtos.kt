package com.sesac.speechapp.dto.session

import java.math.BigDecimal

/**
 * 클라이언트 ↔ BE 세션 API DTO (05a_Client_API_Reference.md).
 *
 * v1.6 (D-5, 2026-09-06):
 * - SessionCreateData.type 신설 (today|theme — 03a §2 세션 2종 분기 계약)
 * - SessionHistoryItem/SessionHistoryResponse 신설 (05a §8.2 기록 카드)
 * - SessionReportData(metricCards/radar/talkHistory/answer) 신설 (05a §8.3 세부 보고서)
 * - FinishData는 구조 유지 — talk/total 피드백은 null 전송 (2단계 재편: 상세는 8.3에서 수령)
 */

// ---------- 세션 생성 (§3.1 — today/theme 공통) ----------

data class ChoiceDto(
    val order: Int,
    val mediaType: String,   // TEXT | IMAGE
    val context: String      // 텍스트 내용 또는 image_id
)

data class TurnDto(
    val turnId: Long,
    val turnNumber: Int,
    val type: String,        // LISTEN_TEXT | LISTEN_PICTURE | NAMING | SHADOWING | SELF_TALK
    val ttsUrl: String? = null,      // /api/v1/voice/{voiceRecordId}
    val passage: String? = null,
    val choices: List<ChoiceDto>? = null,   // LISTEN 전용
    val imageId: Long? = null,              // NAMING / SELF_TALK
    val imageUrl: String? = null,
    val hintAvailable: Int? = null          // NAMING 전용 (2)
)

data class SessionCreateData(
    val sessionId: Long,
    val theme: String,
    val type: String,        // D-5: today | theme (LEARNING_SESSION.TYPE — 05a §3.1)
    val turns: List<TurnDto>
)

// ---------- 답안 제출 ----------

data class ListenSubmitRequest(
    val selected: Int    // 선택지 order (1-based)
)

data class ListenSubmitData(
    val turnId: Long,
    val score: Int,      // 100 | 0
    val correct: Boolean
)

data class VoiceSubmitData(
    val turnId: Long,
    val score: java.math.BigDecimal,
    val voiceRecordId: Long,
    val userVoiceEval: UserVoiceEvalDto
)

data class UserVoiceEvalDto(
    val durationSecond: Int,
    val syllables: Int,
    val speakingTime: java.math.BigDecimal,
    val articulationTime: java.math.BigDecimal,
    val text: String
)

// ---------- 힌트 ----------

data class HintData(
    val hintOrder: Int,     // 1 | 2
    val cueType: String,    // SEMANTIC | ARTICULATORY
    val text: String
)

// ---------- 이야기 턴 ----------

data class TalkData(
    val turnId: Long,
    val turnNumber: Int,
    val aiText: String,          // prompt_text (AI 발화)
    val userText: String? = null // 이번 턴 유저 발화 STT (첫 턴 null)
)

// ---------- 세션 종료 (§3.5 — 간이 보고서 응답) ----------

data class FinishData(
    val sessionAQ: Int,
    val feedbacks: FeedbacksDto
)

/**
 * D-5: talk/total 피드백은 리포트 2단계 재편으로 /report/total(백그라운드) 수신 시점에
 * 적재 — finish 응답 시점엔 항상 null (05a §3.5 갱신: 클라 D-6이 이 계약을 따라감).
 */
data class FeedbacksDto(
    val listenFeedback: String?,
    val namingFeedback: String?,
    val shadowingFeedback: String?,
    val selfTalkFeedback: String?,
    val talkFeedback: String? = null,     // 항상 null — 상세 보고서(§8.3)에서 수령
    val totalFeedback: String? = null     // 항상 null — 상세 보고서(§8.3)에서 수령
)

// ---------- 대시보드: 기록 카드 (§8.2 — D-5 신설) ----------

data class SessionHistoryItem(
    val sessionId: Long,
    val sessionName: String?,
    val createdAt: String?,    // ISO 타임스탬프 (표현은 클라 포맷 책임)
    val aq: Int?
)

data class SessionHistoryResponse(
    val sessions: List<SessionHistoryItem>
)

// ---------- 세부 보고서 (§8.3 — D-5 신설) ----------

/** 4축 방사형 — 해당 세션 TURN.score 집계 (LISTEN=LISTEN_TEXT+LISTEN_PICTURE 통합) */
data class RadarDto(
    val listen: BigDecimal?,
    val naming: BigDecimal?,
    val shadowing: BigDecimal?,
    val selfTalk: BigDecimal?
)

/** metricCard 턴별 답변 — LISTEN=선택지 텍스트+정답 여부 / 음성형=STT 텍스트+음성 URL */
data class AnswerDto(
    val mediaType: String,   // text | voice
    val value: String?,      // LISTEN=선택했던 선택지 텍스트 / 음성형=answer_text(STT)
    val correct: Boolean?,   // LISTEN 전용 — selected==correct_value
    val voiceUrl: String? = null  // 음성형 전용 — 유저 음성 스트리밍 경로
)

data class MetricTurnDto(
    val turnId: Long,
    val turnNumber: Int,
    val promptText: String?,
    val ttsUrl: String?,
    val imageUrl: String?,
    val answer: AnswerDto?
)

data class MetricCardDto(
    val type: String,        // LISTEN | NAMING | SHADOWING | SELF_TALK
    val score: BigDecimal?,  // 해당 타입 TURN.score 평균 (radar와 동일 값)
    val feedback: String?,   // LEARNING_SESSION.*_feedback (간이 보고서 적재분)
    val turns: List<MetricTurnDto>
)

/** 이야기 대화 내역 — AI 발화(ttsUrl) + 유저 답변(voiceUrl 다시 듣기) */
data class TalkHistoryItem(
    val speaker: String,     // AI | USER
    val text: String,
    val ttsUrl: String? = null,    // AI 행 — VOICE_RECORD AI행 있으면 매핑
    val voiceUrl: String? = null   // USER 행 — 유저 음성 스트리밍 경로
)

data class SessionReportData(
    val sessionId: Long,
    val aq: Int?,
    val totalFeedback: String?,
    val radar: RadarDto?,
    val metricCards: List<MetricCardDto>,
    val talkFeedback: String?,
    val talkHistory: List<TalkHistoryItem>,
    val reportViewedAt: String?   // ISO — 이번 조회로 기록된 시각 (null이면 기록 실패)
)