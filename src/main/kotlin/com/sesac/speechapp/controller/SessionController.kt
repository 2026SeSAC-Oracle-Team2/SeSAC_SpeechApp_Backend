package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.ApiResponse
import com.sesac.speechapp.dto.SessionCreateRequest
import com.sesac.speechapp.dto.SessionCreateResponse
import com.sesac.speechapp.service.SessionService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/sessions")
class SessionController(
    private val sessionService: SessionService
) {
    @PostMapping
    fun createSession(@RequestBody request: SessionCreateRequest): ResponseEntity<ApiResponse<SessionCreateResponse>> {
        val session = sessionService.createSession(request.userId, request.theme)
        return ResponseEntity.ok(ApiResponse.success(
            SessionCreateResponse(
                sessionId = session.id!!,
                createdAt = session.createdAt!!
            )
        ))
    }
}
