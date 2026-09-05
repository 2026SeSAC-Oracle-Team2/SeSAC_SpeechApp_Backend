package com.sesac.speechapp.service

import com.sesac.speechapp.entity.Session
import com.sesac.speechapp.repository.SessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class SessionService(
    private val sessionRepository: SessionRepository
) {
    @Transactional
    fun createSession(userId: Long, theme: String? = null): Session {
        val session = Session(
            userId = userId,
            theme = theme,
            status = "IN_PROGRESS"
        )
        return sessionRepository.save(session)
    }

}
