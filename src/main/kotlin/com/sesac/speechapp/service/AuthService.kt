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

        // [e2e5-hotfix HF-1+HF-2] 인증 판정 이중화 + 미완성 프로필 신규 취급.
        //
        // 구 로직: isNewUser = existsByFirebaseUid 단일 조건 → uid가 갈린 재가입
        // (클라 로그아웃이 FirebaseAuth signOut 없이 토큰 클리어만 하는 케이스·
        //  credential 캐시)에서 동일 email의 APP_USER.EMAIL 유니크(SYS_C008308)
        // 충돌 ORA-00001 500 (사용자 원문 재현 3회).
        //
        // [HF-1] uid miss → email 재조회: 기존 행 있으면 그 행으로 로그인 처리
        // (email 병합 — INSERT 없음이라 유니크 안전), warn 로그 1건.
        //
        // [HF-2] isNewUser 규약 보강 (사용자 계약: "신규 사용자 로그인 직후
        // activity_signup.xml이 나오지 않고 activity_survey.xml로 넘어감"):
        //   isNewUser = (uid 행 없음) && (email 행 없음 또는 profile 미완성)
        //   profile 미완성 = nickname IS NULL — SignupActivity에서 닉네임 등을
        //   채우므로 null = 가입 3단계 미완료 증거. 기존 계정이라도 닉네임이
        //   비어 있으면 isNewUser=true로 응답해 클라 goNext가 Signup부터 시작.
        //   (userAq==null → Survey 분기는 기존 유저용 그대로 유지 — 클라 계약 무변경)
        val userByUid = appUserRepository.findByFirebaseUid(firebaseUid)
        val user: AppUser
        var isNewUser = false
        if (userByUid != null) {
            user = userByUid
        } else {
            val userByEmail = appUserRepository.findByEmail(email)
            if (userByEmail != null) {
                // [HF-1] uid 불일치 email 병합 — 기존 행으로 로그인 (INSERT 없음)
                logger.warn(
                    "[hotfix] uid 불일치 email 병합: email={}, existingId={}",
                    email, userByEmail.id
                )
                user = userByEmail
                // [HF-2] 프로필 완성도 기반 isNewUser — 닉네임 null = 가입 플로우 미완료
                isNewUser = userByEmail.profile?.nickname == null
            } else {
                logger.info("신규 사용자 등록: email=$email")
                user = createNewUser(firebaseUid, email)
                isNewUser = true
            }
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