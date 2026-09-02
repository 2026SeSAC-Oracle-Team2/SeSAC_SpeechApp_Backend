package com.sesac.speechapp.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
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
    @Column(name = "likes", length = 500)
    var likes: String? = null,

    @Column(name = "sex", length = 10)
    var sex: String? = null,

    @Column(name = "age")
    var age: Int? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    constructor() : this(null, null, null, null)
}