package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.UserProfileTag
import com.sesac.speechapp.entity.UserProfileTagId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * USER_PROFILE_TAGS — 유저↔태그 N:M 연결 (04 v2.6 §4.2.1, D-3 [1][5]).
 * PATCH /me tagIds 전량 교체(기존 DELETE 후 INSERT) + 회원탈퇴 벌크 삭제용.
 * 복합 PK (user_id + tag_id) — 엔티티 선례: TurnImage (@IdClass).
 */
@Repository
interface UserProfileTagRepository : JpaRepository<UserProfileTag, UserProfileTagId> {
    fun findByUserIdOrderByTagIdAsc(userId: Long): List<UserProfileTag>

    /** 기존 연결 전량 삭제 (JPQL 벌크 — 영속성 컨텍스트 우회, 즉시 SQL 실행). 전량 교체 + 탈퇴 공용 */
    @Modifying
    @Query("DELETE FROM UserProfileTag upt WHERE upt.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: Long)
}