package com.sesac.speechapp.dto.aicontainer

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * BE ↔ AI 컨테이너 계약 DTO 세트 (03a_AI_Container_API_Reference.md v1.7 + 03 v1.6 기준).
 * - 컨테이너 JSON 규약을 그대로 반영 (sessionID/userID 등 대소문자 포함).
 * - 소문자 camelCase 타입 표기: listenText / listenPicture / naming / shadowing / selfTalk
 *   (v1.4: 구 `listen` 폐지 → LISTEN 세분화)
 *
 * v1.7 (D-2, 2026-09-05) 변경:
 * - ContainerUserInfo: likes 제거 → hobbies/tags/userMemory (03a §1.1)
 * - CreateSessionRequest: imageList+namingImageIds/selfTalkImageIds 제거 →
 *   3분할 imageListListening/Naming/SelfTalk (03a v1.2 계약)
 * - ShadowingScoreRequest: articulationRate 신설 (03a §5)
 * - NamingScoreRequest.userRT: 0개(첫사용)면 0 전송 — 구 null 폐지 (03a §4)
 */

// ============================================================
// POST /sessions/today · /sessions/theme — 세션 문제 일괄 생성 (§2)
// ============================================================

data class ContainerUserInfo(
    val nickname: String?,          // nullable X 규약이지만 스텁 환경 허용
    val hobbies: String? = null,    // 취미 자유 텍스트 (구 likes 폐지 — v1.3)
    val tags: String? = null,       // 관심사 태그 쉼표 구분 문자열 (List 아님 — 03a §1.1)
    val sex: String? = null,
    val age: Int? = null,           // BIRTH_DATE 기반 백엔드 산정 Int
    val userMemory: String? = null  // 누적 개인화 메모리 — 오파크 CLOB 통째 전달 (v1.3)
)

data class ContainerImageItem(
    @JsonProperty("imageId") val imageId: Long,
    @JsonProperty("imageName") val imageName: String
)

data class CreateSessionRequest(
    @JsonProperty("sessionId") val sessionId: Long,
    @JsonProperty("thema") val thema: String,
    // v1.2 계약: 3분할 이미지 풀 (구 imageList·namingImageIds·selfTalkImageIds 폐지).
    // 분류 규약: IMAGE_TAG_PATH 있음=SELF_TALK / 없음+SEMANTIC_CUE 있음=NAMING / 둘 다 없음=LISTEN.
    // imageListListening은 userAQ 등급 기반 EASY/HARD 태그 필터 결과 (03a §2)
    @JsonProperty("imageListListening") val imageListListening: List<ContainerImageItem>,
    @JsonProperty("imageListNaming") val imageListNaming: List<ContainerImageItem>,
    @JsonProperty("imageListSelfTalk") val imageListSelfTalk: List<ContainerImageItem>,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("userInfos") val userInfos: ContainerUserInfo,
    @JsonProperty("userAQ") val userAQ: Int? = null // USER_REPRESENTATIVE_SCORES.USER_AQ 캐시 조회 (v1.7)
)

data class CreateSessionResponse(
    @JsonProperty("sessionId") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("problemList") val problemList: List<ContainerProblem>
)

data class ContainerProblem(
    @JsonProperty("turnId") val turnId: Int,            // 컨테이너 로컬 번호 1~8 (ADR-006)
    @JsonProperty("type") val type: String,             // listenText | listenPicture | naming | shadowing | selfTalk
    @JsonProperty("ttsPath") val ttsPath: String?,      // 공유폴더상 AI TTS 경로
    @JsonProperty("passage") val passage: String?,
    @JsonProperty("perType") val perType: ContainerPerType? = null
)

/**
 * 타입별 데이터 — listenText/listenPicture: correct + options / naming: correct /
 * shadowing: 없음 / selfTalk: image.
 * v1.4 options 규약: listenText=텍스트형만 / listenPicture=이미지형만 (유형 고정, 혼합 폐지).
 * 개수 2~4개 = 등급표(03a §2)에 따름. correct는 listen=정답 인덱스, naming=정답 단어.
 */
data class ContainerPerType(
    // listen: 정답 선택지 인덱스 (0-based) / naming: 정답 단어 (String)
    @JsonProperty("correct") val correct: Any? = null,
    // listen 전용: 선택지 2~4개 (listenText=텍스트형만 / listenPicture=이미지형만)
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
    @JsonProperty("hintCount") val hintCount: Int,              // 0~2 (의미→조음 순 공개 수)
    // 유저 평균 RT — 최근 naming 음성 20개 중 발화시간/음절수 최단 10개의 (발화시간 총합 ÷ 음절수 총합).
    // 백엔드 산정. 0개(첫사용 유저)면 0 전송 — v1.4: 구 null 전송 폐지.
    // 컨테이너는 0 수신 시 이번 녹음 결과를 평균으로 간주 (예외처리 확정)
    @JsonProperty("userRT") val userRT: BigDecimal
)

data class ShadowingScoreRequest(
    @JsonProperty("sessionID") val sessionId: Long,
    @JsonProperty("userID") val userId: Long,
    @JsonProperty("problemContext") val problemContext: String, // 원문 구문
    @JsonProperty("userVoicePath") val userVoicePath: String,
    // 유저 개인 조음속도 (v1.4 신설) — 최근 문제풀이(NAMING/SHADOWING/SELF_TALK) 유저 음성 중
    // SYLLABLES NOT NULL·ARTICULATION_TIME > 0인 것 20개 중 조음속도(ARTICULATION_TIME÷SYLLABLES)
    // 최단(가장 빠른) 10개의 (SYLLABLES 총합 ÷ ARTICULATION_TIME 총합). 백엔드 산정, 소수 2자리.
    // 0개(첫사용 유저)면 0 전송 — 컨테이너는 0 수신 시 이번 녹음을 평균으로 간주.
    // 명칭은 팀원 전달본 그대로 — `user` 접두 없음 (userRT와 다름)
    @JsonProperty("articulationRate") val articulationRate: BigDecimal
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
// userVoiceEval — 공통 유저 음성 평가 객체 (§1.2)
// ============================================================

data class UserVoiceEval(
    @JsonProperty("durationSecond") val durationSecond: Int,
    @JsonProperty("syllables") val syllables: Int,
    @JsonProperty("speakingTime") val speakingTime: BigDecimal,
    @JsonProperty("articulationTime") val articulationTime: BigDecimal,
    @JsonProperty("text") val text: String
)

// ============================================================
// POST /aichat — 이야기 턴 (§6)
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
    @JsonProperty("type") val type: String,           // 소문자: listenText | listenPicture | naming | shadowing | selfTalk
    @JsonProperty("context") val context: String?,    // 문제 context (지문/정답단어/원문/이미지 이름)
    @JsonProperty("userAnswer") val userAnswer: String?, // 유저 답변 (STT 텍스트). LISTEN은 선택 결과
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
// POST /report/problems · /report/total — 세션 보고서 2단계 (§7)
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