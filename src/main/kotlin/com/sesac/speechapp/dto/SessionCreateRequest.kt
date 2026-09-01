package com.sesac.speechapp.dto

import java.time.LocalDateTime

data class SessionCreateRequest(
    val userId: Long,
    val theme: String? = null
)

data class SessionCreateResponse(
    val sessionId: Long,
    val createdAt: LocalDateTime
)
