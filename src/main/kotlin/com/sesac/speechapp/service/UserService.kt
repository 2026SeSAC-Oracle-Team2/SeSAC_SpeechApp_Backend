package com.sesac.speechapp.service

import com.sesac.speechapp.dto.ScoresResponse
import com.sesac.speechapp.dto.session.SessionHistoryItem
import com.sesac.speechapp.dto.session.SessionHistoryResponse
import com.sesac.speechapp.dto.SurveyRequest
import com.sesac.speechapp.dto.SurveyResponse
import com.sesac.speechapp.dto.TagsResponse
import com.sesac.speechapp.dto.TagItem
import com.sesac.speechapp.dto.UserDto
import com.sesac.speechapp.dto.UpdateProfileRequest
import com.sesac.speechapp.entity.AppUser
import com.sesac.speechapp.entity.UserProfile
import com.sesac.speechapp.entity.UserProfileTag
import com.sesac.speechapp.entity.UserRepresentativeScore
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.TagRepository
import com.sesac.speechapp.repository.TurnImageRepository
import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.UserProfileRepository
import com.sesac.speechapp.repository.UserProfileTagRepository
import com.sesac.speechapp.repository.UserRepresentativeScoreRepository
import com.sesac.speechapp.repository.VoiceRecordRepository
import com.sesac.speechapp.security.deleteUserByUid
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class UserService(
    private val appUserRepository: AppUserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val sessionRepository: SessionRepository,
    private val turnRepository: TurnRepository,
    private val turnImageRepository: TurnImageRepository,
    private val voiceRecordRepository: VoiceRecordRepository,
    private val tagRepository: TagRepository,
    private val userProfileTagRepository: UserProfileTagRepository,
    private val userRepresentativeScoreRepository: UserRepresentativeScoreRepository,
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

    /**
     * 프로필 수정 (D-3 [1] — 05a §2 갱신).
     *
     * 부분 업데이트 규약: null 필드는 기존값 유지. tagIds=null이면 태그 교체 금지
     * (명시적 []만 전체 삭제 의미). birthDate는 ISO yyyy-MM-dd 고정 — 파싱 실패 → E0400.
     * tagIds는 USER_PROFILE_TAGS 전량 교체(기존 DELETE 후 INSERT) — 클라가 항상
     * 현재 선택 전체를 보낸다. >5개 → E0400, 없는 tag_id → E0404.
     */
    @Transactional
    fun updateProfile(userUuid: String, request: UpdateProfileRequest): UserDto {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        // 신규 가입 직후 profile이 없는 경우 방어적으로 생성 (PATCH 선요청 대비)
        val profile = user.profile ?: ensureProfile(user)

        request.nickname?.let {
            profile.nickname = it
        }
        request.hobbies?.let {
            profile.hobbies = it
        }
        request.sex?.let {
            profile.sex = it
        }
        request.birthDate?.let { raw ->
            profile.birthDate = try {
                LocalDate.parse(raw) // ISO yyyy-MM-dd (DateTimeFormatter.ISO_LOCAL_DATE 기본)
            } catch (e: Exception) {
                throw IllegalArgumentException("birthDate 형식 오류: yyyy-MM-dd 형식이 필요합니다. (수신: $raw)")
            }
        }
        request.tagIds?.let { tagIds ->
            replaceTags(user, tagIds)
        }

        return toDto(user)
    }

    /**
     * USER_PROFILE_TAGS 전량 교체 — 기존 DELETE 후 INSERT (D-3 [1]).
     * JPQL 벌크 DELETE는 즉시 SQL 실행 → 이후 INSERT가 커밋 시 flush되어 순서 보장.
     * USER_PROFILE_TAGS.USER_ID FK → APP_USER.ID (FK_UPT_USER 실측) — 키는 user.id.
     */
    private fun replaceTags(user: AppUser, tagIds: List<Long>) {
        if (tagIds.size > 5) {
            throw IllegalArgumentException("태그는 최대 5개까지 선택할 수 있습니다. (수신: ${tagIds.size}개)")
        }
        val userId = requireNotNull(user.id) { "사용자 ID 누락: ${user.uuid}" }
        val distinctIds = tagIds.distinct() // 중복 전송 시 복합 PK 충돌 방지
        if (distinctIds.isNotEmpty()) {
            val found = tagRepository.findAllById(distinctIds)
            if (found.size != distinctIds.size) {
                throw NoSuchElementException("존재하지 않는 태그가 포함되어 있습니다. (요청 tagIds=$tagIds)") // → E0404
            }
        }
        // 전량 교체: 기존 DELETE 후 INSERT
        userProfileTagRepository.deleteByUserId(userId)
        distinctIds.forEach { tagId ->
            userProfileTagRepository.save(UserProfileTag(userId = userId, tagId = tagId))
        }
        logger.info("프로필 태그 전량 교체: uuid={}, tagIds={}", user.uuid, distinctIds)
    }

    /**
     * 가입 설문 접수 (D-3 [3] — 06 §5.2).
     * 산출 주체 = 서버: answers 원문(5개, 1~5)만 수신 → 총점 = Σ(answer×4) → 환산 AQ.
     * 환산: 20~61 → 30 / 62~80 → 70 / 81~100 → 90.
     * USER_REPRESENTATIVE_SCORES upsert — 행 없으면 INSERT, 있으면 user_aq 갱신(재노출 케이스).
     * 응답 원문은 저장하지 않는다(환산 AQ만). 중복 응답 허용(갱신 처리 — 405 거부 금지).
     * REP_SCORES.USER_ID FK → USER_PROFILE.USER_ID (= APP_USER.ID 값, FK_REP_SCORES_USER 실측) — 키는 user.id.
     */
    @Transactional
    fun submitSurvey(userUuid: String, request: SurveyRequest): SurveyResponse {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        val answers = request.answers
        if (answers.size != 5) {
            throw IllegalArgumentException("설문 응답은 5개 문항의 답변이 필요합니다. (수신: ${answers.size}개)")
        }
        if (answers.any { it < 1 || it > 5 }) {
            throw IllegalArgumentException("설문 답변은 1~5 범위의 정수여야 합니다.")
        }

        val total = answers.sumOf { it * 4 } // 20~100
        val userAq = when (total) {
            in 20..61 -> 30
            in 62..80 -> 70
            in 81..100 -> 90
            else -> throw IllegalStateException("설문 총점이 허용 범위를 벗어났습니다: $total")
        }

        val userId = requireNotNull(user.id) { "사용자 ID 누락: $userUuid" }
        val existing = userRepresentativeScoreRepository.findByUserId(userId)
        if (existing == null) {
            userRepresentativeScoreRepository.save(UserRepresentativeScore(userId = userId, userAq = userAq))
            logger.info("가입 설문 접수 — REP_SCORES 신규 INSERT: uuid={}, total={}, userAq={}", userUuid, total, userAq)
        } else {
            existing.userAq = userAq // dirty checking UPDATE (재노출 케이스 — 갱신 처리)
            logger.info("가입 설문 재접수 — userAq 갱신: uuid={}, total={}, userAq={}", userUuid, total, userAq)
        }

        return SurveyResponse(userAq = userAq, user = toDto(user))
    }

    /**
     * 대표점수 조회 (D-3 [4] — 05a §8.1).
     * REP_SCORES 단일 SELECT. 행이 없으면(신규 가입 직후 설문 전) 전 필드 null — 클라 폴백.
     */
    @Transactional(readOnly = true)
    fun getScores(userUuid: String): ScoresResponse {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")

        val rep = requireNotNull(user.id) { "사용자 ID 누락: $userUuid" }
            .let { userRepresentativeScoreRepository.findByUserId(it) }

        return ScoresResponse(
            userAq = rep?.userAq,
            listen = rep?.userScoreListen,
            naming = rep?.userScoreNaming,
            shadowing = rep?.userScoreShadowing,
            selfTalk = rep?.userScoreSelfTalk
        )
    }

    /**
     * 학습 기록 카드 리스트 (D-5 [4.1] — 05a §8.2).
     * LEARNING_SESSION 조회(user_id, CREATED_AT DESC) → 필터:
     * STATUS != COMPLETED_NO_TALK **AND AQ IS NOT NULL**
     * (AQ null = 간이 보고서 미생성 세션 — 카드에 AQ 표시 불가라 제외.
     *  학습 중간에 나간 IN_PROGRESS 세션도 자연 배제됨. 05a §8.2 규약에 규약 추가 반영).
     * createdAt은 ISO 타임스탬프 — 표현(포맷)은 클라 책임.
     */
    @Transactional(readOnly = true)
    fun getSessionHistory(userUuid: String): SessionHistoryResponse {
        val user = appUserRepository.findByUuid(userUuid)
            ?: throw IllegalArgumentException("사용자를 찾을 수 없습니다: $userUuid")
        val userId = requireNotNull(user.id) { "사용자 ID 누락: $userUuid" }

        val rows = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .filter { it.status != "COMPLETED_NO_TALK" && it.aq != null }

        return SessionHistoryResponse(
            sessions = rows.map { s ->
                SessionHistoryItem(
                    sessionId = requireNotNull(s.id),
                    sessionName = s.sessionName,
                    createdAt = s.createdAt?.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    aq = s.aq
                )
            }
        )
    }

    /** 태그 마스터 15종 조회 (D-3 [2] — 05a §2). order by tagId */
    @Transactional(readOnly = true)
    fun getTags(): TagsResponse {
        return TagsResponse(
            tags = tagRepository.findAllByOrderByTagIdAsc().map { TagItem(tagId = requireNotNull(it.tagId), tag = it.tag) }
        )
    }

    /**
     * 회원탈퇴: DB hard delete + OCI 유저 파일 정리 + Firebase 계정 삭제(커밋 후).
     *
     * - DB 삭제는 전 FK가 NO ACTION(무 CASCADE)이므로 **자식부터 FK 역순**으로 삭제한다 (B-1 + D-3 [5]).
     *   순서: TURN_IMAGE → VOICE_RECORD → TURN → LEARNING_SESSION → USER_PROFILE_TAGS →
     *         USER_REPRESENTATIVE_SCORES → USER_PROFILE → APP_USER
     *   (D-1 신설 USER_PROFILE_TAGS/USER_REPRESENTATIVE_SCORES 미삭제 시 PROFILE 삭제 ORA-02292 — 핵심 회귀)
     *   (JPQL 벌크 삭제는 영속성 컨텍스트를 우회하므로 flush()로 선행 영속화분을 강제 반영한 뒤 수행)
     * - TAGS는 마스터(공유) 테이블이라 삭제 금지 — 유저 연결(UPS)만 삭제.
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

        // FK 역순 하드딜리트 (B-1 + D-3 [5]):
        // TURN_IMAGE → VOICE_RECORD → TURN → LEARNING_SESSION → USER_PROFILE_TAGS →
        // USER_REPRESENTATIVE_SCORES → USER_PROFILE → APP_USER
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
        userProfileTagRepository.deleteByUserId(userId)      // 5) USER_PROFILE_TAGS (D-1 신설 — APP_USER 자식)
        userRepresentativeScoreRepository.deleteByUserId(userId) // 6) USER_REPRESENTATIVE_SCORES (PROFILE 삭제 전 필수)
        user.profile?.let { userProfileRepository.delete(it) } // 7) USER_PROFILE
        appUserRepository.delete(user)                       // 8) APP_USER
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

    /**
     * D-3: UserDto 확장 — 기존 필드 유지 + hobbies/sex/birthDate/tags/userAq 추가 (하위호환).
     * - birthDate: LocalDate → ISO yyyy-MM-dd 문자열 (LocalDate.toString)
     * - tags: USER_PROFILE_TAGS → TAGS 조인 후 쉼표 문자열 (03a §1.1 형식 "등산, 골프")
     *   ⚠️ UPS.USER_ID FK → APP_USER.ID (FK_UPT_USER 실측) — 키는 user.id
     * - userAq: REP_SCORES.USER_ID FK → USER_PROFILE.USER_ID (= APP_USER.ID 값, FK_REP_SCORES_USER 실측) — null 허용
     */
    fun toDto(user: AppUser): UserDto {
        val profile = user.profile
        val userId = requireNotNull(user.id) { "사용자 ID 누락: ${user.uuid}" }
        val userAq = userRepresentativeScoreRepository.findByUserId(userId)?.userAq

        return UserDto(
            id = userId,
            uuid = user.uuid,
            email = user.email,
            nickname = profile?.nickname,
            profileImageUrl = profile?.profileImageBucketPath,
            hobbies = profile?.hobbies,
            sex = profile?.sex,
            birthDate = profile?.birthDate?.toString(),
            tags = buildTagsString(userId).ifEmpty { null },
            userAq = userAq,
            level = 1,
            // LocalDateTime → Instant 변환은 반드시 atZone(...).toInstant() 사용
            // (Instant.from(LocalDateTime)은 UnsupportedTemporalTypeException 발생)
            createdAt = user.createdAt?.atZone(ZoneId.systemDefault())?.toInstant()
        )
    }

    /**
     * D-4 [1.1]: 태그 조립 헬퍼 — USER_PROFILE_TAGS → TAGS 조립 후 쉼표 문자열 (03a §1.1 형식).
     * 연결이 없으면 빈 문자열 반환 (호출부가 null/빈값 처리).
     * - 순서: tag_id 오름차순 (findByUserIdOrderByTagIdAsc)
     * - N+1 회피: 단일 findAllById로 태그명 일괄 조회 (toDto 선례 패턴 재사용)
     * - 사용처: toDto(GET/PATCH /me 응답) + SessionFlowService userInfos.tags 주입
     *   (/sessions·/aichat — 03a §1.1 userInfos 규약)
     */
    fun buildTagsString(userId: Long): String {
        val tagRels = userProfileTagRepository.findByUserIdOrderByTagIdAsc(userId)
        if (tagRels.isEmpty()) return ""
        val tagNames: Map<Long, String> = tagRepository.findAllById(tagRels.map { it.tagId })
            .associate { requireNotNull(it.tagId) to it.tag }
        return tagRels.mapNotNull { tagNames[it.tagId] }.joinToString(", ")
    }
}