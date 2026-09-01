package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
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
    val status: String = "IN_PROGRESS",

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    constructor() : this(userId = 0L)
}
