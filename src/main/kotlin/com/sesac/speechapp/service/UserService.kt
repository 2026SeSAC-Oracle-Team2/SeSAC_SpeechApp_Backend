package com.sesac.speechapp.service

import com.sesac.speechapp.dto.UserDto
import com.sesac.speechapp.dto.UpdateProfileRequest
import com.sesac.speechapp.entity.AppUser
import com.sesac.speechapp.entity.UserProfile
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.TurnImageRepository
import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.UserProfileRepository
import com.sesac.speechapp.repository.VoiceRecordRepository
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
    private val userProfileRepository: UserProfileRepository,
    private val sessionRepository: SessionRepository,
    private val turnRepository: TurnRepository,
    private val turnImageRepository: TurnImageRepository,
    private val voiceRecordRepository: VoiceRecordRepository,
    private val objectStorageService: ObjectStorageService
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
     * 회원탈퇴: DB hard delete + OCI 유저 파일 정리 + Firebase 계정 삭제(커밋 후).
     *
     * - DB 삭제는 전 FK가 NO ACTION(무 CASCADE)이므로 **자식부터 FK 역순**으로 삭제한다 (B-1).
     *   순서: TURN_IMAGE → VOICE_RECORD → TURN → LEARNING_SESSION → USER_PROFILE → APP_USER
     *   (JPQL 벌크 삭제는 영속성 컨텍스트를 우회하므로 flush()로 선행 영속화분을 강제 반영한 뒤 수행)
     * - OCI 음성/프로필 파일(userfiles 버킷의 {userUUID}/ 하위)은 실패해도 탈퇴를 막지 않는다(로그만).
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
        val userId = requireNotNull(user.id) { "사용자 ID 누락: $userUuid" }

        // FK 역순 하드딜리트 (B-1): TURN_IMAGE → VOICE_RECORD → TURN → LEARNING_SESSION → USER_PROFILE → APP_USER
        // JPQL 벌크 삭제는 영속성 컨텍스트를 우회하므로 즉시 SQL 실행 — 순서만 지키면 FK 안전.
        val sessionIds = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId).mapNotNull { it.id }
        val turnIds = sessionIds.flatMap { sid -> turnRepository.findBySessionIdOrderByTurnNumberAsc(sid).mapNotNull { it.id } }

        // OCI 정리 대상 키 수집 (DB 삭제 전 — userfiles 버킷의 {userUUID}/ 하위. classpath: 스텁 행 제외)
        val voiceObjectKeys = voiceRecordRepository.findByUserId(userId).map { it.voiceFilePath }
            .filter { !it.startsWith("classpath:") && !it.startsWith("stub/") }
        val profileObjectKey = user.profile?.profileImageBucketPath

        if (turnIds.isNotEmpty()) {
            turnImageRepository.deleteByTurnIds(turnIds)   // 1) TURN_IMAGE (TURN의 자식)
        }
        voiceRecordRepository.deleteByUserId(userId)        // 2) VOICE_RECORD (TURN의 자식, USER_ID FK 포함)
        if (sessionIds.isNotEmpty()) {
            turnRepository.deleteBySessionIds(sessionIds)   // 3) TURN
            sessionRepository.deleteBySessionIds(sessionIds) // 4) LEARNING_SESSION
        }
        user.profile?.let { userProfileRepository.delete(it) } // 5) USER_PROFILE
        appUserRepository.delete(user)                       // 6) APP_USER
        appUserRepository.flush() // 제약 위반 시 즉시 예외 → 트랜잭션 롤백

        logger.info(
            "회원탈퇴 DB hard delete 완료: uuid={}, email={}, sessions={}, turns={}",
            userUuid, user.email, sessionIds.size, turnIds.size
        )

        // DB 커밋 성공 후에만 OCI 파일 정리 시도 — 실패해도 탈퇴는 유지 (로그만, B-1)
        // 커밋 성공 후에만 지우는 이유: 트랜잭션 롤백 시 파일이 남아있는 유저의 데이터를 보존하기 위함.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    deleteUserFiles(userUuid, voiceObjectKeys, profileObjectKey)
                    com.google.firebase.auth.FirebaseAuth.getInstance().deleteUserByUid(firebaseUid)
                }
            })
        } else {
            // 트랜잭션 동기화가 없는 환경(단위 테스트 등)에서는 즉시 시도
            deleteUserFiles(userUuid, voiceObjectKeys, profileObjectKey)
            com.google.firebase.auth.FirebaseAuth.getInstance().deleteUserByUid(firebaseUid)
        }
    }

    /**
     * 탈퇴 유저의 OCI userfiles 파일 삭제 (음성 + 프로필).
     * 실패해도 탈퇴를 막지 않는다 — 잔존 오브젝트는 운영 정리 대상(로그로 추적).
     */
    private fun deleteUserFiles(userUuid: String, voiceObjectKeys: List<String>, profileObjectKey: String?) {
        (voiceObjectKeys + listOfNotNull(profileObjectKey)).forEach { key ->
            try {
                objectStorageService.deleteObject(key)
                logger.info("탈퇴 OCI 파일 삭제 완료: uuid={}, key={}", userUuid, key)
            } catch (e: Exception) {
                logger.warn("탈퇴 OCI 파일 삭제 실패 (탈퇴는 계속 진행): uuid={}, key={}, error={}", userUuid, key, e.message)
            }
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