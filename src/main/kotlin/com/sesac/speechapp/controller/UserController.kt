package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.*
import com.sesac.speechapp.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService
) {

    @GetMapping("/me")
    fun getMyProfile(
        @AuthenticationPrincipal userUuid: String
    ): ResponseEntity<ApiResponse<UserDto>> {
        val result = userService.getMyProfile(userUuid)
        return ResponseEntity.ok(ApiResponse.success(result))
    }

    @PatchMapping("/me")
    fun updateMyProfile(
        @AuthenticationPrincipal userUuid: String,
        @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<ApiResponse<UserDto>> {
        val result = userService.updateProfile(userUuid, request)
        return ResponseEntity.ok(ApiResponse.success(result))
    }
}