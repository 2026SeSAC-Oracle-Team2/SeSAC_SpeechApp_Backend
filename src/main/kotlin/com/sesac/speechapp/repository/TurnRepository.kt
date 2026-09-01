package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.Turn
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TurnRepository : JpaRepository<Turn, Long> {
    fun findBySessionIdOrderByTurnNumberAsc(sessionId: Long): List<Turn>
    fun findTopBySessionIdOrderByTurnNumberDesc(sessionId: Long): Turn?
}
