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

    fun getNextTurnNumber(sessionId: Long): Int {
        // TODO: 실제 구현은 TurnRepository에서 COUNT + 1
        // 지금은 단순히 1부터 시작
        return 1
    }
}
