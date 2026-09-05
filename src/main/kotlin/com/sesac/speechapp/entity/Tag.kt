package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/**
 * TAGS — 관심사 태그 마스터 (04 v2.6 §4.2.1, D-1 LIVE 신설).
 * 가입 플로우의 선택형 관심사 태그. 클라는 TAGS 테이블을 받아 버블 나열 → 최대 5개 선택.
 * 시드 15종: 건강관리·등산·골프·여행·트로트·요리·텃밭가꾸기·낚시·독서·바둑·사진·전시관람·국내여행·반려동물·봉사활동
 */
@Entity
@Table(name = "tags", schema = "speechapp_user")
class Tag(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val tagId: Long? = null,

    @Column(name = "tag", nullable = false, unique = true, length = 50)
    val tag: String
) {
    constructor() : this(null, "")
}

/**
 * USER_PROFILE_TAGS — 유저↔태그 N:M 연결 (04 v2.6 §4.2.1, D-1 LIVE 신설).
 * 복합 PK (user_id + tag_id), FK 2개. 최대 5개 제한은 앱 레벨 검증 (CHECK 없음).
 * 복합PK 매핑 선례: TurnImage.kt (@IdClass)
 */
@Entity
@Table(name = "user_profile_tags", schema = "speechapp_user")
@IdClass(UserProfileTagId::class)
class UserProfileTag(

    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long = 0L,

    @Id
    @Column(name = "tag_id", nullable = false)
    val tagId: Long = 0L
) : Serializable {
    constructor() : this(0L, 0L)
}

class UserProfileTagId(
    val userId: Long = 0L,
    val tagId: Long = 0L
) : Serializable