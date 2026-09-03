package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.Session
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SessionRepository : JpaRepository<Session, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<Session>

    /** 회원탈퇴용 벌크 삭제 (FK 역순 하드딜리트 — B-1) */
    @Modifying
    @Query("DELETE FROM Session s WHERE s.id IN :sessionIds")
    fun deleteBySessionIds(@Param("sessionIds") sessionIds: Collection<Long>)
}
