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

    @Lob
    @Column(name = "prompt_text")
    val promptText: String? = null,

    @Lob
    @Column(name = "choices_json")
    val choicesJson: String? = null,

    @Column(name = "correct_value", length = 500)
    val correctValue: String? = null,

    @Column(name = "selected_value", length = 500)
    val selectedValue: String? = null,

    @Lob
    @Column(name = "answer_text")
    val answerText: String? = null,

    @Column(name = "hints_shown")
    val hintsShown: Int? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null
) {
    constructor() : this(sessionId = 0L, turnNumber = 0, contentType = "")
}
