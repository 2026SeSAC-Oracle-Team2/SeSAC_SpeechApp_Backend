package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.VoiceRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface VoiceRecordRepository : JpaRepository<VoiceRecord, Long> {
    fun findBySessionIdOrderByCreatedAtAsc(sessionId: Long): List<VoiceRecord>
    fun findByTurnId(turnId: Long): List<VoiceRecord>
    fun findByUserId(userId: Long): List<VoiceRecord>
}