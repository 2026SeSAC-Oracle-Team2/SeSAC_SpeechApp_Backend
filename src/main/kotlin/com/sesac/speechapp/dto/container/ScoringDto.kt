package com.sesac.speechapp.dto.container

/**
 * 채점 컨테이너(Scoring) 요청 DTO
 *
 * 백엔드는 OCI Object Storage에 저장된 음성 파일의 Pre-Authenticated URL을 전달.
 * 컨테이너는 해당 URL로 직접 스트리밍 다운로드 후 채점 수행.
 */
data class ScoringRequest(
    /** VoiceRecord PK */
    val voiceRecordId: Long,
    /** 해당 턴의 문제 본문 (발음 비교용) */
    val promptText: String,
    /** 음성 파일을 다운로드할 수 있는 OCI Pre-Authenticated URL */
    val audioUrl: String,
    /** 음성 파일 Content-Type (예: audio/mp4) */
    val audioContentType: String = "audio/mp4",
    /** 세션 유형 */
    val sessionType: String,
    /** 사용자의 기대 응답 (correct_value) */
    val expectedAnswer: String? = null
)

/**
 * 채점 컨테이너(Scoring) 응답 DTO
 */
data class ScoringResponse(
    /** VoiceRecord PK */
    val voiceRecordId: Long,
    /** 전체 점수 (0.0 ~ 100.0) */
    val overallScore: Double? = null,
    /** 음절 수 */
    val syllables: Int? = null,
    /** 응답 시간 (초) */
    val responseTimeSec: Double? = null,
    /** 발음 속도 (음절/분) */
    val articulationRate: Double? = null,
    /** 발음 정확도 점수 */
    val pronunciationScore: Double? = null,
    /** 피치/억양 점수 */
    val intonationScore: Double? = null,
    /** 채점 상태 SUCCESS / FAILED */
    val status: String = "SUCCESS",
    /** 에러 메시지 */
    val errorMessage: String? = null
)
