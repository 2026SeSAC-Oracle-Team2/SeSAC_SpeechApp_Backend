package com.sesac.speechapp.dto.aicontainer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * BE ↔ AI 컨테이너 계약 DTO 세트 (03_AI_Container_Contract.md v1.0 기준).
 * - 컨테이너 JSON 규약을 그대로 반영 (sessionID/userID 등 대소문자 포함).
 * - 소문자 camelCase 타입 표기: listen / naming / shadowing / selfTalk / storytelling
 */

// ============================================================
// POST /sessions — 세션 문제 일괄 생성 (§2)
// ============================================================

data class ContainerUserInfo(
    val nickname: String?,
    val likes: String? = null,
    val sex: String? = null,
    val age: Int? = null
)

data class ContainerImageItem(
    @JsonProperty("imageId") val imageId: Long,
    @JsonProperty("imageName") val imageName: String
)

data class CreateSessionRequest(
    @JsonProperty("sessionId") val sessionId: Long,
    @JsonProperty("thema") val thema: String,
    @JsonProperty("imageList") val imageList: List<ContainerImageItem>,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("userInfos") val userInfos: ContainerUserInfo,
    @JsonProperty("userAQ") val userAQ: Int? = null
)

data class CreateSessionResponse(
    @JsonProperty("sessionId") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("problemList") val problemList: List<ContainerProblem>
)

data class ContainerProblem(
    @JsonProperty("turnId") val turnId: Int,            // 컨테이너 로컬 번호 1~8 (ADR-006)
    @JsonProperty("type") val type: String,             // listen | naming | shadowing | selfTalk
    @JsonProperty("ttsPath") val ttsPath: String?,      // 공유폴더상 AI TTS 경로
    @JsonProperty("passage") val passage: String?,
    @JsonProperty("perType") val perType: ContainerPerType? = null
)

/**
 * 타입별 데이터 — listen: correct + options / naming: correct / shadowing: 없음 / selfTalk: image
 * options는 text/image 혼합. correct는 타입별 의미가 다름 (listen: 정답 인덱스, naming: 정답 단어).
 */
data class ContainerPerType(
    // listen: 정답 선택지 인덱스 (0-based) / naming: 정답 단어 (String)
    @JsonProperty("correct") val correct: Any? = null,
    // listen 전용: 선택지 2~4개 (text/image 혼합)
    @JsonProperty("options") val options: List<ContainerOption>? = null,
    // selfTalk 전용: 문제 상황 이미지 id
    @JsonProperty("image") val image: Long? = null
)

data class ContainerOption(
    @JsonProperty("type") val type: String,      // text | image
    @JsonProperty("context") val context: String // 텍스트 내용 또는 image_id
)

// ============================================================
// POST /answer/naming (§4) / shadowing (§5) / selfTalk (§6)
// ============================================================

data class NamingScoreRequest(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("problemContext") val problemContext: String, // 정답 단어
    @JsonProperty("userVoicePath") val userVoicePath: String,   // 공유폴더상 경로
    @JsonProperty("hintCount") val hintCount: Int,              // 0~2
    @JsonProperty("userRT") val userRT: BigDecimal?             // 백엔드 산정 (0개면 null)
)

data class ShadowingScoreRequest(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("problemContext") val problemContext: String, // 원문 구문
    @JsonProperty("userVoicePath") val userVoicePath: String
)

data class SelfTalkScoreRequest(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("problemImage") val problemImage: String,     // 이미지 이름
    @JsonProperty("problemTag") val problemTag: String,         // tags.json 내용 통째로
    @JsonProperty("userVoicePath") val userVoicePath: String
)

data class NamingScoreResponse(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("scoreNaming") val scoreNaming: BigDecimal,
    @JsonProperty("userVoiceEval") val userVoiceEval: UserVoiceEval
)

data class ShadowingScoreResponse(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("scoreShadowing") val scoreShadowing: BigDecimal,
    @JsonProperty("userVoiceEval") val userVoiceEval: UserVoiceEval
)

data class SelfTalkScoreResponse(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("scoreSelfTalk") val scoreSelfTalk: BigDecimal,
    @JsonProperty("userVoiceEval") val userVoiceEval: UserVoiceEval
)

// ============================================================
// userVoiceEval — 공통 유저 음성 평가 객체 (§7)
// ============================================================

data class UserVoiceEval(
    @JsonProperty("durationSecond") val durationSecond: Int,
    @JsonProperty("syllables") val syllables: Int,
    @JsonProperty("speakingTime") val speakingTime: BigDecimal,
    @JsonProperty("articulationTime") val articulationTime: BigDecimal,
    @JsonProperty("text") val text: String
)

// ============================================================
// POST /aichat — 이야기 턴 (§8)
// ============================================================

data class AiChatRequest(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("userInfos") val userInfos: ContainerUserInfo,
    @JsonProperty("turnResults") val turnResults: List<TurnResult>,
    @JsonProperty("context") val context: List<ChatMessage>,   // 첫 요청은 빈 배열
    @JsonProperty("userVoicePath") val userVoicePath: String? = null // 첫 요청엔 없음
)

data class TurnResult(
    @JsonProperty("turnId") val turnId: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("context") val context: String?,   // 문제 context (지문/정답단어/원문)
    @JsonProperty("userAnswer") val userAnswer: String?,
    @JsonProperty("score") val score: BigDecimal?
)

data class ChatMessage(
    @JsonProperty("speaker") val speaker: String,    // AI | USER
    @JsonProperty("text") val text: String
)

data class AiChatResponse(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("llmResponse") val llmResponse: String,
    @JsonProperty("userText") val userText: String? = null // 첫 요청 응답엔 없음
)

// ============================================================
// POST /report — 세션 보고서 (§9)
// ============================================================

data class ReportRequest(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("turns") val turns: List<TurnResult>,
    @JsonProperty("talkContext") val talkContext: List<ChatMessage>
)

data class SessionFeedbacks(
    @JsonProperty("listenFeedback") val listenFeedback: String,
    @JsonProperty("namingFeedback") val namingFeedback: String,
    @JsonProperty("shadowingFeedback") val shadowingFeedback: String,
    @JsonProperty("selfTalkFeedback") val selfTalkFeedback: String,
    @JsonProperty("talkFeedback") val talkFeedback: String,
    @JsonProperty("totalFeedback") val totalFeedback: String
)

data class ReportResponse(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("sessionAQ") val sessionAQ: Int,   // 100점 만점 정수
    @JsonProperty("sessionFeedbacks") val sessionFeedbacks: SessionFeedbacks
)