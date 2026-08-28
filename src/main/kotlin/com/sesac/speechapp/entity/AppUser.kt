package com.sesac.speechapp.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "app_user", schema = "speechapp_user")
class AppUser(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "uuid", nullable = false, unique = true, updatable = false, length = 36)
    val uuid: String = UUID.randomUUID().toString(),

    // 소셜 전용 가입 시 null 가능 (DDL: firebase_uid VARCHAR2(255) UNIQUE, CHECK 제약으로 social_id와 대체 필수)
    @Column(name = "firebase_uid", nullable = true, unique = true, length = 255)
    val firebaseUid: String?,

    // 소셜 로그인 확장용 (Naver/Kakao 등) — BE-DB-02
    @Column(name = "social_provider", nullable = true, length = 20)
    val socialProvider: String? = null,

    @Column(name = "social_id", nullable = true, unique = true, length = 255)
    val socialId: String? = null,

    // 소셜 가입 시 이메일 미제공 가능 (DDL: email VARCHAR2(255) UNIQUE nullable)
    @Column(name = "email", nullable = true, unique = true, length = 255)
    val email: String?,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    @OneToOne(mappedBy = "user", cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    var profile: UserProfile? = null

    // JPA 재정의 생성자
    constructor() : this(firebaseUid = null, email = null, socialId = null, socialProvider = null)
}