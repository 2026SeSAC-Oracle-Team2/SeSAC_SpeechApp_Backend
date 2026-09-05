package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.Turn
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TurnRepository : JpaRepository<Turn, Long> {
    fun findBySessionIdOrderByTurnNumberAsc(sessionId: Long): List<Turn>
    fun findTopBySessionIdOrderByTurnNumberDesc(sessionId: Long): Turn?
    fun countBySessionId(sessionId: Long): Long
    fun findByContentType(contentType: String): List<Turn>

    /** v1.4 articulationRate 산정용 — NAMING/SHADOWING/SELF_TALK 문제풀이 턴 조회 */
    fun findByContentTypeIn(contentTypes: Collection<String>): List<Turn>

    /** 회원탈퇴용 벌크 삭제 (FK 역순 하드딜리트 — B-1) */
    @Modifying
    @Query("DELETE FROM Turn t WHERE t.sessionId IN :sessionIds")
    fun deleteBySessionIds(@Param("sessionIds") sessionIds: Collection<Long>)
}