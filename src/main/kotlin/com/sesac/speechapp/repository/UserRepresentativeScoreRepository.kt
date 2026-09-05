package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.UserRepresentativeScore
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * USER_REPRESENTATIVE_SCORES — 대표점수 캐시 (04 v2.6 §4.2.2, D-3 [3][4][5]).
 * user_id는 PK이자 FK→user_profile.user_id (1:1, MapsId) — @GeneratedValue 없음.
 * 갱신 지점 2곳 고정: ① 가입 설문 접수(USER_AQ 세팅 — D-3) ② /report/problems 수신(D-5 대상).
 */
@Repository
interface UserRepresentativeScoreRepository : JpaRepository<UserRepresentativeScore, Long> {
    fun findByUserId(userId: Long): UserRepresentativeScore?

    /** 회원탈퇴용 벌크 삭제 (FK 역순 — PROFILE 삭제 전 필수, D-3 [5]) */
    @Modifying
    @Query("DELETE FROM UserRepresentativeScore s WHERE s.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: Long)
}