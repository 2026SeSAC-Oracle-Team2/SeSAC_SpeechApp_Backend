package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Lob
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "turn", schema = "speechapp_user")
class Turn(

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "turnSeq")
    @SequenceGenerator(name = "turnSeq", sequenceName = "turn_seq", schema = "speechapp_user", allocationSize = 1)
    val id: Long? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Column(name = "turn_number", nullable = false)
    val turnNumber: Int,

    @Column(name = "content_type", nullable = false, length = 50)
    val contentType: String,

    // P2-36 (ADR-007): PENDING(출제·미풀이) / SUBMITTED(답안 제출) / SCORED(채점 완료)
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "PENDING",

    @Lob
    @Column(name = "prompt_text")
    val promptText: String? = null,

    @Lob
    @Column(name = "choices_json")
    var choicesJson: String? = null,

    @Column(name = "correct_value", length = 500)
    var correctValue: String? = null,

    @Column(name = "selected_value", length = 500)
    var selectedValue: String? = null,

    @Lob
    @Column(name = "answer_text")
    var answerText: String? = null,

    @Column(name = "hints_shown")
    var hintsShown: Int? = null,

    @Column(name = "score", nullable = true, precision = 5, scale = 2)
    var score: java.math.BigDecimal? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null
) {
    constructor() : this(sessionId = 0L, turnNumber = 0, contentType = "", score = null)
}
