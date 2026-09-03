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
}