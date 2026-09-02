package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "voice_record", schema = "speechapp_user")
class VoiceRecord(

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "voiceRecordSeq")
    @SequenceGenerator(name = "voiceRecordSeq", sequenceName = "voice_record_seq", schema = "speechapp_user", allocationSize = 1)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Column(name = "turn_id", nullable = false)
    val turnId: Long,

    @Column(name = "speaker", nullable = false, length = 10)
    val speaker: String, // 'USER' or 'AI'

    @Column(name = "voice_file_path", nullable = false, length = 500)
    val voiceFilePath: String,

    @Column(name = "duration_seconds")
    val durationSeconds: Int? = null,

    @Column(name = "syllables")
    val syllables: Int? = null,

    // P2-36 (ADR-010): RESPONSE_TIME→SPEAKING_TIME, ARTICULATION_RATE→ARTICULATION_TIME rename
    // NUMBER(무precision) 컬럼 — BigDecimal 매핑 (Double→BINARY_DOUBLE 함정 회피)
    @Column(name = "speaking_time")
    val speakingTime: java.math.BigDecimal? = null,

    @Column(name = "articulation_time")
    val articulationTime: java.math.BigDecimal? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null
) {
    constructor() : this(userId = 0L, sessionId = 0L, turnId = 0L, speaker = "", voiceFilePath = "")
}
