package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.VoiceRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface VoiceRecordRepository : JpaRepository<VoiceRecord, Long> {
    fun findBySessionIdOrderByCreatedAtAsc(sessionId: Long): List<VoiceRecord>
    fun findByTurnId(turnId: Long): List<VoiceRecord>
    fun findByUserId(userId: Long): List<VoiceRecord>

    /** 회원탈퇴용 벌크 삭제 (FK 역순 하드딜리트 — B-1) */
    @Modifying
    @Query("DELETE FROM VoiceRecord v WHERE v.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: Long)

    /**
     * [e2e3-A] 비동기 채점 완료 시 USER 음성 지표 3종 UPDATE — 벌크 JPQL.
     * entity 필드가 val이므로 dirty checking 불가 → @Modifying UPDATE.
     * ⚠️ voiceRecordId 보존 계약(05a §3.2): 제출 응답이 준 ID가 그대로 유효해야 하므로
     * delete+save(새 ID) 금지. RETURNING 없이 행 수만 반환(0이면 미발견 — 호출부 WARN).
     * 벌크 UPDATE는 1차 캐시를 우회 — 이 메서드는 백그라운드(REQUIRES_NEW) 전용 경로로만 쓴다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE VoiceRecord v SET v.durationSeconds = :durationSeconds, v.syllables = :syllables, " +
            "v.speakingTime = :speakingTime, v.articulationTime = :articulationTime " +
            "WHERE v.turnId = :turnId AND v.speaker = 'USER'"
    )
    fun updateUserVoiceMetrics(
        @Param("turnId") turnId: Long,
        @Param("durationSeconds") durationSeconds: Int?,
        @Param("syllables") syllables: Int?,
        @Param("speakingTime") speakingTime: java.math.BigDecimal?,
        @Param("articulationTime") articulationTime: java.math.BigDecimal?
    ): Int

    /**
     * [e2e3-A] 같은 턴 재제출 시 지표 리셋 + 경로 갱신 — ID 보존(voiceRecordId 계약).
     * voice_file_path도 새 objectKey로 갱신한다(재제출 파일로 교체 의미).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE VoiceRecord v SET v.voiceFilePath = :objectKey, v.durationSeconds = NULL, " +
            "v.syllables = NULL, v.speakingTime = NULL, v.articulationTime = NULL " +
            "WHERE v.id = :voiceRecordId"
    )
    fun updateUserVoiceMetricsToNull(
        @Param("voiceRecordId") voiceRecordId: Long,
        @Param("objectKey") objectKey: String
    ): Int
}