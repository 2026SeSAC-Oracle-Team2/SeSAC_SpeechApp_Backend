package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.*
import com.sesac.speechapp.service.AuthService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/firebase")
    fun firebaseLogin(
        @RequestBody request: FirebaseAuthRequest
    ): ResponseEntity<ApiResponse<AuthResponse>> {
        val result = authService.authenticateWithFirebase(request)
        return ResponseEntity.ok(ApiResponse.success(result))
    }

    @PostMapping("/refresh")
    fun refreshToken(
        @RequestBody request: TokenRefreshRequest
    ): ResponseEntity<ApiResponse<TokenRefreshResponse>> {
        val result = authService.refreshToken(request)
        return ResponseEntity.ok(ApiResponse.success(result))
    }

    @PostMapping("/logout")
    fun logout(): ResponseEntity<ApiResponse<Any?>> {
        return ResponseEntity.ok(ApiResponse.success(null))
    }
}