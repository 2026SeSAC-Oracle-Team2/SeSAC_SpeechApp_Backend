package com.sesac.speechapp.dto.container

/**
 * 문제 생성 컨테이너(LLM) 요청 DTO
 */
data class LlmGenerateRequest(
    /** 세션 ID (컨테이너가 응답에 포함해주면 매핑 편의) */
    val sessionId: Long,
    /** 세션 유형(예: "pronunciation", "conversation", "reading") */
    val sessionType: String,
    /** 난이도 레벨 */
    val level: Int,
    /** 사용자 언어 설정 (ko, en 등) */
    val language: String = "ko",
    /** 이전 턴 맥락 (선택, 멀티턴일 경우) */
    val context: List<TurnContext>? = null
) {
    data class TurnContext(
        val turnNumber: Int,
        val promptText: String,
        val userResponse: String?
    )
}

/**
 * 문제 생성 컨테이너(LLM) 응답 DTO
 */
data class LlmGenerateResponse(
    /** 세션 ID */
    val sessionId: Long,
    /** 턴 번호 (1-based) */
    val turnNumber: Int,
    /** 문제 유형 (SINGLE_CHOICE, PRONUNCIATION, READING 등) */
    val contentType: String,
    /** AI가 생성한 문제 본문 */
    val promptText: String,
    /** 보기(JSON 문자열) — nullable */
    val choicesJson: String? = null,
    /** 정답 — nullable (채점용) */
    val correctValue: String? = null,
    /** TTS 음성 파일 OCI 키 (컨테이너가 TTS 생성 시) */
    val ttsAudioObjectKey: String? = null,
    /** 생성 상태 */
    val status: String = "SUCCESS",
    /** 에러 메시지 */
    val errorMessage: String? = null
)
