package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.ApiResponse
import com.sesac.speechapp.dto.UserDto
import com.sesac.speechapp.dto.UpdateProfileRequest
import com.sesac.speechapp.dto.TagsResponse
import com.sesac.speechapp.dto.SurveyRequest
import com.sesac.speechapp.dto.SurveyResponse
import com.sesac.speechapp.dto.ScoresResponse
import com.sesac.speechapp.dto.StatsResponse
import com.sesac.speechapp.dto.session.SessionHistoryResponse
import com.sesac.speechapp.service.ObjectStorageService
import com.sesac.speechapp.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.Duration

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
    private val objectStorageService: ObjectStorageService
) {
    private val logger = LoggerFactory.getLogger(UserController::class.java)

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

    /**
     * 태그 마스터 조회 (D-3 [2] — 05a §2): 15종 {tagId, tag} 목록. JWT 필수.
     */
    @GetMapping("/me/tags")
    fun getTags(): ResponseEntity<ApiResponse<TagsResponse>> {
        val result = userService.getTags()
        return ResponseEntity.ok(ApiResponse.success(result))
    }

    /**
     * 가입 설문 접수 (D-3 [3] — 06 §5.2): answers 5개(1~5) → 서버 산출 환산 AQ(30/70/90)
     * + REP_SCORES upsert. 중복 응답 허용(갱신 처리). 산출 주체 = 서버.
     */
    @PostMapping("/me/survey")
    fun submitSurvey(
        @AuthenticationPrincipal userUuid: String,
        @RequestBody request: SurveyRequest
    ): ResponseEntity<ApiResponse<SurveyResponse>> {
        val result = userService.submitSurvey(userUuid, request)
        return ResponseEntity.ok(ApiResponse.success(result))
    }

    /**
     * 대표점수 조회 (D-3 [4] — 05a §8.1): { userAq, listen, naming, shadowing, selfTalk }.
     * REP_SCORES 단일 SELECT — null은 null 전달 (클라 폴백).
     */
    @GetMapping("/me/scores")
    fun getScores(
        @AuthenticationPrincipal userUuid: String
    ): ResponseEntity<ApiResponse<ScoresResponse>> {
        val result = userService.getScores(userUuid)
        return ResponseEntity.ok(ApiResponse.success(result))
    }

    /**
     * 홈 통계 조회 (D-8②b — 05a §8.4): JWT 필수 — @AuthenticationPrincipal userUuid.
     * 응답: {streakDays, avgScore(소수1자리|null), deltaScore(소수1자리|null)}.
     * 산정 규약은 UserService.getStats KDoc + 05a §8.4 참조.
     */
    @GetMapping("/me/stats")
    fun getMyStats(
        @AuthenticationPrincipal userUuid: String
    ): ResponseEntity<ApiResponse<StatsResponse>> {
        val result = userService.getStats(userUuid)
        return ResponseEntity.ok(ApiResponse.success(result))
    }

    /**
     * 학습 기록 카드 리스트 (D-5 [4.1] — 05a §8.2): STATUS != COMPLETED_NO_TALK +
     * AQ NOT NULL 필터. JWT 필수 — @AuthenticationPrincipal userUuid 기반 소유 스코프.
     * 응답: [{sessionId, sessionName, createdAt(ISO), aq}] — 상한 없음(페이징 미도입).
     */
    @GetMapping("/me/sessions/history")
    fun getSessionHistory(
        @AuthenticationPrincipal userUuid: String
    ): ResponseEntity<ApiResponse<SessionHistoryResponse>> {
        val result = userService.getSessionHistory(userUuid)
        return ResponseEntity.ok(ApiResponse.success(result))
    }

    /**
     * 회원탈퇴 — DB hard delete(FK 역순, B-1) + OCI 유저 파일 정리 + Firebase 계정 삭제.
     * 응답 204 No Content (05a §2 계약).
     */
    @DeleteMapping("/me")
    fun withdrawMe(
        @AuthenticationPrincipal userUuid: String
    ): ResponseEntity<Void> {
        userService.withdraw(userUuid)
        return ResponseEntity.noContent().build()
    }

    /**
     * 프로필 사진 업로드 (multipart, jpg/png/webp, 최대 5MB).
     * 저장 키: {userUUID}/profile.{ext} — 사용자당 1개(덮어쓰기).
     */
    @PostMapping("/me/profile-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadProfileImage(
        @AuthenticationPrincipal userUuid: String,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ApiResponse<UserDto>> {
        logger.info(
            "프로필 이미지 업로드 요청: uuid={}, originalName={}, size={}",
            userUuid, file.originalFilename, file.size
        )

        // 1) 파일 존재/비어있음 검증
        if (file.isEmpty) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error("INVALID_FILE", "업로드된 파일이 없습니다.")
            )
        }

        // 2) 확장자 검증 (jpg/png/webp) — application.yml 표준 multipart 제한(5MB)과 함께 동작
        val extension = ObjectStorageService.extractImageExtension(file.originalFilename)
            ?: return ResponseEntity.badRequest().body(
                ApiResponse.error(
                    "INVALID_FILE_TYPE",
                    "지원하지 않는 파일 형식입니다. (허용: jpg, png, webp)"
                )
            )

        // 3) OCI 업로드
        val objectKey = objectStorageService.buildProfileKey(userUuid, extension)
        val contentType = ObjectStorageService.SUPPORTED_IMAGE_TYPES[extension]!!
        objectStorageService.uploadObject(objectKey, file.bytes, contentType)

        // 4) DB에 키 저장 (신규 가입 직후 profile이 없으면 생성)
        val user = userService.getMyProfileEntity(userUuid)
        val profile = user.profile ?: userService.ensureProfile(user)
        profile.profileImageBucketPath = objectKey
        val saved = userService.updateProfileImagePath(userUuid, objectKey)

        logger.info("프로필 이미지 업로드 완료: uuid={}, key={}", userUuid, objectKey)
        return ResponseEntity.ok(ApiResponse.success(saved))
    }

    /**
     * 프로필 사진 조회 — 버킷은 비공개이므로 백엔드가 프록시 스트리밍한다.
     */
    @GetMapping("/me/profile-image")
    fun getProfileImage(
        @AuthenticationPrincipal userUuid: String
    ): ResponseEntity<*> {
        val user = userService.getMyProfileEntity(userUuid)
        val objectKey = user.profile?.profileImageBucketPath
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error<ByteArrayResource>(
                    "PROFILE_IMAGE_NOT_FOUND",
                    "등록된 프로필 사진이 없습니다."
                )
            )

        val response = objectStorageService.getObject(objectKey)
        val bytes = response.inputStream.use { it.readBytes() }
        val contentType = ObjectStorageService.SUPPORTED_IMAGE_TYPES.entries
            .firstOrNull { objectKey.endsWith(".${it.key}") }?.value
            ?: MediaType.APPLICATION_OCTET_STREAM_VALUE

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)))
            .body(ByteArrayResource(bytes))
    }
}