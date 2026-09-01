package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.Session
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SessionRepository : JpaRepository<Session, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<Session>
}
