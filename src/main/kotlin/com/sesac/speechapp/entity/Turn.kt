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

    // D-2 (04 v2.6 §4.5): 6종 — LISTEN_TEXT / LISTEN_PICTURE / NAMING / SHADOWING / SELF_TALK / STORYTELLING
    // (v1.4: 구 LISTEN 폐지 → 세분화. D-1 CHECK 재생성 + 기존 행 LISTEN_TEXT UPDATE 완료)
    @Column(name = "content_type", nullable = false, length = 20)
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

    @Column(name = "correct_value", length = 255)
    var correctValue: String? = null,

    @Column(name = "selected_value", length = 255)
    var selectedValue: String? = null,

    @Lob
    @Column(name = "answer_text")
    var answerText: String? = null,

    @Column(name = "hints_shown")
    var hintsShown: Int? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @Column(name = "score", nullable = true, precision = 5, scale = 2)
    var score: java.math.BigDecimal? = null
) {
    constructor() : this(sessionId = 0L, turnNumber = 0, contentType = "", score = null)
}
