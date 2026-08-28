package com.sesac.speechapp.service

import com.sesac.speechapp.dto.UserDto
import com.sesac.speechapp.dto.UpdateProfileRequest
import com.sesac.speechapp.entity.UserProfile
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

@Service
class UserService(
    private val appUserRepository: AppUserRepository,
    private val userProfileRepository: UserProfileRepository
) {

    fun getMyProfile(userUuid: String): UserDto {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        return toDto(user)
    }

    @Transactional
    fun updateProfile(userUuid: String, request: UpdateProfileRequest): UserDto {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        // 신규 가입 직후 profile이 없는 경우 방어적으로 생성 (PATCH 선요청 대비)
        val profile = user.profile ?: ensureProfile(user)

        request.nickname?.let {
            profile.nickname = it
        }

        return toDto(user)
    }

    /**
     * profile 연관이 없는 유저에게 새 UserProfile을 생성/연결한다.
     * cascade = ALL 이므로 user 저장 시 함께 영속화된다.
     */
    fun ensureProfile(user: com.sesac.speechapp.entity.AppUser): UserProfile {
        val profile = UserProfile(user = user)
        userProfileRepository.save(profile)
        user.profile = profile
        return profile
    }

    fun toDto(user: com.sesac.speechapp.entity.AppUser): UserDto = UserDto(
        uuid = user.uuid,
        email = user.email,
        nickname = user.profile?.nickname,
        profileImageUrl = user.profile?.profileImageBucketPath,
        level = 1,
        // LocalDateTime → Instant 변환은 반드시 atZone(...).toInstant() 사용
        // (Instant.from(LocalDateTime)은 UnsupportedTemporalTypeException 발생)
        createdAt = user.createdAt?.atZone(ZoneId.systemDefault())?.toInstant()
    )
}