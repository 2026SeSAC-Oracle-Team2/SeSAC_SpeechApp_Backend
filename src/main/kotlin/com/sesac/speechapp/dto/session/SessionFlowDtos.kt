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

data class FeedbacksDto(
    val listenFeedback: String?,
    val namingFeedback: String?,
    val shadowingFeedback: String?,
    val selfTalkFeedback: String?,
    val talkFeedback: String?,
    val totalFeedback: String?
)
