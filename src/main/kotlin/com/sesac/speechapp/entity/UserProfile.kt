package com.sesac.speechapp.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "user_profile", schema = "speechapp_user")
class UserProfile(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: AppUser? = null,

    @Column(name = "nickname", length = 50)
    var nickname: String? = null,

    // OCI Object Storage 오브젝트 키만 저장 (예: {userUUID}/profile.jpg) — BE-DB-04
    // DDL 기준: profile_image_bucket_path (2026-08-28 레포 DDL 정합. DB 컬럼 rename 완료)
    @Column(name = "profile_image_bucket_path", length = 500)
    var profileImageBucketPath: String? = null,

    // P2-36: AI 컨테이너 userInfos 전달용 (nullable 3종, 2026-09-02 확정)
    // D-2 (04 v2.6 / 03a v1.7): likes→hobbies rename (가입 플로우 개편 v1.3 — 취미 자유 텍스트)
    @Column(name = "hobbies", length = 500)
    var hobbies: String? = null,

    @Column(name = "sex", length = 10)
    var sex: String? = null,

    // D-2 (04 v2.6): 구 AGE(Number) 폐지 → BIRTH_DATE(DATE) 신설.
    // 컨테이너 전달 시 age는 백엔드가 BIRTH_DATE 기반 산정 (03a §1.1).
    @Column(name = "birth_date")
    var birthDate: LocalDate? = null,

    // D-2 (04 v2.6): 누적 개인화 메모리 CLOB — AI 대화 기반 컨테이너 관리 오파크 텍스트.
    // 백엔드 파싱 비관여, 통째 전달/저장. 갱신 = /report/total 응답 1곳. 규약: 03a §10
    @Lob
    @Column(name = "user_memory")
    var userMemory: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    constructor() : this(null, null, null, null)
}