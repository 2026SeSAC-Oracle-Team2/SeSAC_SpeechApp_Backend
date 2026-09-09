package com.sesac.speechapp.dto

import java.math.BigDecimal
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserDto,
    @JsonProperty("isNewUser") val isNewUser: Boolean
)

class UserDto(
    val id: Long,
    val uuid: String,
    // 소셜 가입 확장에 따라 email은 nullable (AppUser.email nullable 정합성)
    val email: String?,
    val nickname: String?,
    val profileImageUrl: String?,
    // D-3 (05a v1.5): 가입 플로우 개편 확장 필드 — 하위호환: 추가만, 제거 없음
    val hobbies: String? = null,
    val sex: String? = null,
    // ISO yyyy-MM-dd 문자열 (UserProfile.birthDate LocalDate → toString)
    val birthDate: String? = null,
    // 선택 태그 쉼표 문자열 — 03a §1.1 형식 ("등산, 골프") — /sessions 주입은 D-5
    val tags: String? = null,
    // 대표 AQ (USER_REPRESENTATIVE_SCORES.USER_AQ) — null = 설문 미응답 (설문 재노출 판별 기준)
    val userAq: Int? = null,
    val level: Int = 1,
    val createdAt: Instant?
)

class UpdateProfileRequest(
    val nickname: String?,
    // D-3: 가입 플로우 개편 확장 — 부분 업데이트 규약 (null = 기존값 유지)
    val hobbies: String? = null,
    val sex: String? = null,
    // ISO yyyy-MM-dd 고정 (DateTimeFormatter.ISO_LOCAL_DATE — 파싱 실패 → E0400)
    val birthDate: String? = null,
    // USER_PROFILE_TAGS 전량 교체 — 클라가 항상 현재 선택 전체를 전송.
    // null = 태그 변경 없음 / 명시적 [] = 전체 삭제 / >5개 = E0400 / 없는 tag_id = E0404
    val tagIds: List<Long>? = null
)

// D-3 [2]: GET /api/v1/users/me/tags — 태그 마스터 15종 (05a §2)
class TagItem(
    val tagId: Long,
    val tag: String
)

class TagsResponse(
    val tags: List<TagItem>
)

// D-3 [3]: POST /api/v1/users/me/survey — 가입 설문 접수 (06 §5.2)
// 산출 주체 = 서버 — 요청은 answers 원문만 (클라 계산값 수신 금지), 응답 원문 미저장(환산 AQ만)
class SurveyRequest(
    val answers: List<Int>
)

class SurveyResponse(
    val userAq: Int?,
    val user: UserDto
)

// D-3 [4]: GET /api/v1/users/me/scores — 대표점수 조회 (05a §8.1)
// null = 캐시 미산출 — 클라 폴백 (방사형 0 표시 + "학습을 시작해보세요" 등)
class ScoresResponse(
    val userAq: Int?,
    val listen: BigDecimal?,
    val naming: BigDecimal?,
    val shadowing: BigDecimal?,
    val selfTalk: BigDecimal?
)

class TokenRefreshRequest(
    val refreshToken: String
)

class TokenRefreshResponse(
    val accessToken: String,
    val expiresIn: Long
)