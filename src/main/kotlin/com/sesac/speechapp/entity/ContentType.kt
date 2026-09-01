package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "content_type", schema = "speechapp_user")
class ContentType(

    @Id
    @Column(name = "type_code", nullable = false, length = 50)
    val typeCode: String,

    @Column(name = "type_name", nullable = false, length = 100)
    val typeName: String,

    @Column(name = "category", nullable = false, length = 50)
    val category: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null
) {
    constructor() : this("", "", "")
}
