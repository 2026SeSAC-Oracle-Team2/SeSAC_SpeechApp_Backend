package com.sesac.speechapp.service

import com.sesac.speechapp.dto.*
import com.sesac.speechapp.entity.AppUser
import com.sesac.speechapp.entity.UserProfile
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.UserProfileRepository
import com.sesac.speechapp.security.FirebaseAuthUtil
import com.sesac.speechapp.security.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AuthService(
    private val firebaseAuthUtil: FirebaseAuthUtil,
    private val jwtTokenProvider: JwtTokenProvider,
    private val appUserRepository: AppUserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userService: UserService
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    @Transactional
    fun authenticateWithFirebase(request: FirebaseAuthRequest): AuthResponse {
        val firebaseUid = firebaseAuthUtil.getUid(request.idToken)
            ?: throw IllegalArgumentException("유효하지 않은 Firebase ID Token입니다.")

        val email = firebaseAuthUtil.getEmail(request.idToken)
            ?: throw IllegalArgumentException("Firebase Token에서 이메일을 확인할 수 없습니다.")

        val isNewUser = !appUserRepository.existsByFirebaseUid(firebaseUid)

        val user = if (isNewUser) {
            logger.info("신규 사용자 등록: email=$email")
            createNewUser(firebaseUid, email)
        } else {
            appUserRepository.findByFirebaseUid(firebaseUid)
                ?: throw IllegalStateException("사용자를 찾을 수 없습니다.")
        }

        val accessToken = jwtTokenProvider.generateAccessToken(user.uuid)
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.uuid)

        return AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = 900,
            // D-3: UserDto 확장(hobbies/sex/birthDate/tags/userAq) 통일 — userAq null = 설문 미응답
            // (클라 재노출 판별은 USER_AQ null 기준, 06 §5.2)
            user = userService.toDto(user),
            isNewUser = isNewUser
        )
    }

    fun refreshToken(request: TokenRefreshRequest): TokenRefreshResponse {
        val userUuid = jwtTokenProvider.getUserUuid(request.refreshToken)
            ?: throw IllegalArgumentException("유효하지 않은 Refresh Token입니다.")

        if (!jwtTokenProvider.validateToken(request.refreshToken)) {
            throw IllegalArgumentException("만료된 Refresh Token입니다.")
        }

        val newAccessToken = jwtTokenProvider.generateAccessToken(userUuid)
        return TokenRefreshResponse(
            accessToken = newAccessToken,
            expiresIn = 900
        )
    }

    private fun createNewUser(firebaseUid: String, email: String): AppUser {
        val newUser = AppUser(firebaseUid = firebaseUid, email = email)
        val savedUser = appUserRepository.save(newUser)

        val profile = UserProfile(user = savedUser)
        userProfileRepository.save(profile)
        savedUser.profile = profile

        return savedUser
    }
}