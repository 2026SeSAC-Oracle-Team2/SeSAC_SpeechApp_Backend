package com.sesac.speechapp.service

import com.sesac.speechapp.dto.UserDto
import com.sesac.speechapp.dto.UpdateProfileRequest
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class UserService(
    private val appUserRepository: AppUserRepository,
    private val userProfileRepository: UserProfileRepository
) {

    fun getMyProfile(userUuid: String): UserDto {
        val user = appUserRepository.findByEmail(userUuid)
            ?: appUserRepository.findAll().find { it.uuid == userUuid }
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        return UserDto(
            uuid = user.uuid,
            email = user.email,
            nickname = user.profile?.nickname,
            profileImageUrl = user.profile?.profileImageUrl,
            level = 1,
            createdAt = user.createdAt?.let { Instant.from(it) }
        )
    }

    @Transactional
    fun updateProfile(userUuid: String, request: UpdateProfileRequest): UserDto {
        val user = appUserRepository.findAll().find { it.uuid == userUuid }
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        request.nickname?.let {
            user.profile?.nickname = it
        }

        return UserDto(
            uuid = user.uuid,
            email = user.email,
            nickname = user.profile?.nickname,
            profileImageUrl = user.profile?.profileImageUrl,
            level = 1,
            createdAt = user.createdAt?.let { Instant.from(it) }
        )
    }
}