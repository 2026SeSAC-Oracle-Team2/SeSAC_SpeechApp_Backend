package com.sesac.speechapp.dto

import java.time.Instant

class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserDto,
    val isNewUser: Boolean
)

class UserDto(
    val uuid: String,
    val email: String,
    val nickname: String?,
    val profileImageUrl: String?,
    val level: Int = 1,
    val createdAt: Instant?
)

class UpdateProfileRequest(
    val nickname: String?
)

class TokenRefreshRequest(
    val refreshToken: String
)

class TokenRefreshResponse(
    val accessToken: String,
    val expiresIn: Long
)
