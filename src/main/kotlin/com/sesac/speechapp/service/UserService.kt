package com.sesac.speechapp.service

import com.sesac.speechapp.dto.UserDto
import com.sesac.speechapp.dto.UpdateProfileRequest
import com.sesac.speechapp.entity.AppUser
import com.sesac.speechapp.entity.UserProfile
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.UserProfileRepository
import com.sesac.speechapp.security.deleteUserByUid
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.ZoneId

@Service
class UserService(
    private val appUserRepository: AppUserRepository,
    private val userProfileRepository: UserProfileRepository
) {
    private val logger = LoggerFactory.getLogger(UserService::class.java)

    fun getMyProfile(userUuid: String): UserDto {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        return toDto(user)
    }

    /** 컨트롤러/서비스 내부용 엔티티 접근 (profile-image API에서 키 읽기/쓰기에 사용) */
    fun getMyProfileEntity(userUuid: String): AppUser {
        return appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")
    }

    /**
     * 프로필 이미지 키 저장. profile이 없으면 방어적으로 생성.
     * @Transactional: DB 업데이트 원자성 보장
     */
    @Transactional
    fun updateProfileImagePath(userUuid: String, objectKey: String): UserDto {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")
        val profile = user.profile ?: ensureProfile(user)
        profile.profileImageBucketPath = objectKey
        // dirty checking으로 커밋 시 UPDATE 반영 (별도 save 불필요)
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
     * 회원탈퇴: DB hard delete + Firebase 계정 삭제(커밋 후).
     *
     * - DB 삭제 순서: UserProfile(자식, FK 먼저) → AppUser(부모).
     * - @Transactional은 DB 삭제에만 의미가 있다.
     * - Firebase 삭제는 DB 커밋 성공 후(afterCommit)에 시도하며,
     *   실패해도 예외를 던지지 않는다(로그만 남김) — hard delete 이후 재시도 불가이므로
     *   Firebase 장애가 탈퇴를 막지 않는다.
     * - uid가 없으면(소셜 전용 계정) 삭제 생략.
     */
    @Transactional
    fun withdraw(userUuid: String) {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        val firebaseUid = user.firebaseUid

        // FK 순서: UserProfile(자식) 먼저 → AppUser(부모) 나중
        user.profile?.let { userProfileRepository.delete(it) }
        appUserRepository.delete(user)
        appUserRepository.flush() // 제약 위반 시 즉시 예외 → 트랜잭션 롤백

        logger.info("회원탈퇴 DB hard delete 완료: uuid={}, email={}", userUuid, user.email)

        // DB 커밋 성공 후에만 Firebase 삭제 시도 (hard delete 이후 재시도 불가 → 커밋 보장이 선행)
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    com.google.firebase.auth.FirebaseAuth.getInstance().deleteUserByUid(firebaseUid)
                }
            })
        } else {
            // 트랜잭션 동기화가 없는 환경(단위 테스트 등)에서는 즉시 시도
            com.google.firebase.auth.FirebaseAuth.getInstance().deleteUserByUid(firebaseUid)
        }
    }

    /**
     * profile 연관이 없는 유저에게 새 UserProfile을 생성/연결한다.
     * cascade = ALL 이므로 user 저장 시 함께 영속화된다.
     */
    fun ensureProfile(user: AppUser): UserProfile {
        val profile = UserProfile(user = user)
        userProfileRepository.save(profile)
        user.profile = profile
        return profile
    }

    fun toDto(user: AppUser): UserDto = UserDto(
        id = user.id ?: -1L,
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