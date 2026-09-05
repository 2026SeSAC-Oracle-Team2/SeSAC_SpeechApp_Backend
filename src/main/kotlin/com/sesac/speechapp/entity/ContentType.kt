package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

/**
 * CONTENT_TYPE — 컨텐츠 타입 룩업 (04 v2.6 §4.3).
 * D-1 (2026-09-05) LIVE에서 6종으로 확정: LISTEN 폐지 → LISTEN_TEXT/LISTEN_PICTURE 세분화 (v1.4).
 * 컬럼: type_code VARCHAR2(20) PK / type_name VARCHAR2(50) / category VARCHAR2(50) / created_at TIMESTAMP
 * (구 코드는 type_name VARCHAR2(100)로 기술 — LIVE 실측 기준 50으로 정정)
 */
@Entity
@Table(name = "content_type", schema = "speechapp_user")
class ContentType(

    @Id
    @Column(name = "type_code", nullable = false, length = 20)
    val typeCode: String,

    @Column(name = "type_name", nullable = false, length = 50)
    val typeName: String,

    @Column(name = "category", nullable = false, length = 50)
    val category: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null
) {
    constructor() : this("", "", "")
}