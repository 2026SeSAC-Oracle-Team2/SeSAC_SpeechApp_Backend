package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Lob
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "learning_session", schema = "speechapp_user")
class Session(

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sessionSeq")
    @SequenceGenerator(name = "sessionSeq", sequenceName = "session_seq", schema = "speechapp_user", allocationSize = 1)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "theme", length = 100)
    val theme: String? = null,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "IN_PROGRESS",

    // P2-36 (ADR-008): 리포트 — 세션 AQ (100점 만점 정수, 리포트 생성 시점에 적재, 전까지 NULL)
    @Column(name = "aq")
    var aq: Int? = null,

    @Lob
    @Column(name = "listen_feedback")
    var listenFeedback: String? = null,

    @Lob
    @Column(name = "naming_feedback")
    var namingFeedback: String? = null,

    @Lob
    @Column(name = "shadowing_feedback")
    var shadowingFeedback: String? = null,

    @Lob
    @Column(name = "self_talk_feedback")
    var selfTalkFeedback: String? = null,

    @Lob
    @Column(name = "talk_feedback")
    var talkFeedback: String? = null,

    @Lob
    @Column(name = "total_feedback")
    var totalFeedback: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    constructor() : this(userId = 0L)
}
