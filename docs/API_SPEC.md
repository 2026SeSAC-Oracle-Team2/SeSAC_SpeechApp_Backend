# SeSAC Backend — API 명세서

> **버전:** v1.0 (2026-08-24)
> **기준:** `feature/auth-api` 브랜치 기준
> **대상:** Android 프론트엔드 개발자

---

## 1. 기본 정보

### Base URL

| 환경 | URL | 상태 |
|------|-----|------|
| 로컬(VM) | `http://localhost:8080` | ✅ 현재 사용 가능 |
| 운영(VM) | 추후 배포 예정 | ⬜ 미정 |

### 응답 형식

모든 API는 아래 형식으로 응답합니다:

```json
{
  "success": true,
  "data": { ... },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

에러 시:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "E0400",
    "message": "유효하지 않은 요청입니다.",
    "detail": "session_id는 필수입니다."
  },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

| 필드 | 설명 |
|------|------|
| `code` | `E{HTTP상태코드}{시퀀스}` — 예: E0400 = 400 첫 번째 |
| `message` | 사용자에게 노출할 한국어 메시지 |
| `detail` | 디버깅용 상세 정보 (nullable) |
| `timestamp` | ISO 8601 형식 (UTC) |

### HTTP 상태 코드

| 코드 | 사용 상황 |
|------|----------|
| 200 | 성공 (GET, PUT, PATCH) |
| 201 | 생성 성공 (POST) |
| 400 | 잘못된 요청 (파라미터 오류, 유효성 실패) |
| 401 | 인증 실패 (토큰 없음/만료/Firebase 실패) |
| 404 | 리소스 없음 |
| 500 | 서버 내부 오류 |

---

## 2. 인증 API

### 2.1 Firebase 로그인 (Google OAuth2)

Android에서 Firebase Google Sign-In 후 받은 **ID Token**을 서버에 전송하면, 서버가 자체 JWT(Access + Refresh Token)를 발급합니다.

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **Path** | `/api/v1/auth/firebase` |
| **인증** | 불필요 |

#### 요청

```json
{
  "id_token": "eyJhbGciOiJSUzI1NiIs..."
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `id_token` | String | ✅ | Firebase에서 받은 ID Token |

#### 응답 (성공)

```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbG...NiIs...",
    "refresh_token": "eyJhbG...NiIs...",
    "expires_in": 900,
    "user": {
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "email": "user@example.com",
      "nickname": null,
      "profile_image_url": null,
      "level": 1,
      "created_at": "2026-08-24T17:30:00Z"
    },
    "is_new_user": true
  },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `access_token` | String | 15분 유효한 JWT |
| `refresh_token` | String | 7일 유효한 JWT |
| `expires_in` | Long | Access Token 만료까지 초 (900 = 15분) |
| `user.uuid` | String | 서버에서 생성한 고유 식별자 |
| `user.email` | String | Firebase 이메일 |
| `user.nickname` | String? | 최초 가입 시 null |
| `user.profile_image_url` | String? | 최초 가입 시 null |
| `user.level` | Int | 현재 항상 1 (레벨 시스템 미완성) |
| `is_new_user` | Boolean | 처음 가입 여부 |

#### 응답 (실패 예시)

**Firebase ID Token 무효:**

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "E0500",
    "message": "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
    "detail": null
  },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

> 현재 Firebase SDK 예외가 GlobalExceptionHandler로 감싸져서 E0500으로 떨어짐. 향후 에러 코드 세분화 예정.

---

### 2.2 토큰 갱신

Access Token이 만료되면 Refresh Token으로 새 Access Token을 발급받습니다.

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **Path** | `/api/v1/auth/refresh` |
| **인증** | Refresh Token (Body) |

#### 요청

```json
{
  "refresh_token": "eyJhbG...NiIs..."
}
```

#### 응답 (성공)

```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbG...NiIs...",
    "expires_in": 900
  },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

#### 응답 (실패 — 만료된 Refresh Token)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "E0400",
    "message": "만료된 Refresh Token입니다.",
    "detail": null
  },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

---

### 2.3 로그아웃

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **Path** | `/api/v1/auth/logout` |
| **인증** | Bearer JWT (현재는 서버 측 토큰 무효화 없이 단순 응답) |

#### 응답

```json
{
  "success": true,
  "data": null,
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

---

## 3. 사용자 API

### 3.1 내 프로필 조회

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **Path** | `/api/v1/users/me` |
| **인증** | Bearer JWT |

#### 요청 헤더

```
Authorization: Bearer eyJhbG...NiIs...
```

#### 응답 (성공)

```json
{
  "success": true,
  "data": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "nickname": "홍길동",
    "profile_image_url": null,
    "level": 1,
    "created_at": "2026-08-24T17:30:00Z"
  },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

#### 응답 (실패 — 토큰 없음)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "E0401",
    "message": "Full authentication is required to access this resource",
    "detail": null
  },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

---

### 3.2 프로필 수정

현재는 **닉네임**만 수정 가능합니다.

| 항목 | 내용 |
|------|------|
| **Method** | `PATCH` |
| **Path** | `/api/v1/users/me` |
| **인증** | Bearer JWT |

#### 요청

```json
{
  "nickname": "새닉네임"
}
```

#### 응답 (성공)

```json
{
  "success": true,
  "data": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "nickname": "새닉네임",
    "profile_image_url": null,
    "level": 1,
    "created_at": "2026-08-24T17:30:00Z"
  },
  "timestamp": "2026-08-24T17:30:00.123Z"
}
```

---

## 4. Android 연동 가이드

### 4.1 로그인 흐름

```
[Android]
  1. Firebase Google Sign-In → id_token 획득
  2. POST /api/v1/auth/firebase
     Body: { "id_token": "..." }
  3. 응답: access_token, refresh_token, user.uuid, is_new_user
  4. 토큰 저장 (EncryptedSharedPreferences 권장)
  5. 메인 화면 진입
```

### 4.2 API 호출 시 인증

모든 인증 필요 API에 다음 헤더 포함:

```
Authorization: Bearer {access_token}
```

### 4.3 토큰 만료 처리

```
API 호출 → 401 응답
  → POST /api/v1/auth/refresh (refresh_token 전송)
  → 새 access_token 수신
  → 원래 API 재호출
```

---

## 5. 향후 추가 예정 API

| 엔드포인트 | 메서드 | 설명 | 예정 단계 |
|-----------|--------|------|----------|
| `/api/v1/voice/upload` | POST | 음성 파일 업로드 | Phase 3 |
| `/api/v1/voice/{id}` | GET | 음성 파일 다운로드 URL | Phase 3 |
| `/api/v1/sessions` | POST | 세션 생성 | Phase 3 |
| `/api/v1/sessions` | GET | 세션 목록 조회 | Phase 4 |
| `/api/v1/sessions/{id}` | GET | 세션 상세 조회 | Phase 4 |
| `/api/v1/sessions/{id}/report` | GET | 종합 보고서 조회 | Phase 4 |
| `/api/v1/dashboard` | GET | 대시보드 데이터 | Phase 4 |
| WebSocket `/ws/chat` | WS | AI 실시간 대화 | Phase 5 |

---

## 6. 테스트 샘플

### cURL 예시

```bash
# 1. 로그인 (실제 Firebase ID Token 필요)
curl -X POST http://localhost:8080/api/v1/auth/firebase \
  -H "Content-Type: application/json" \
  -d '{"id_token":"실제_토큰_여기"}'

# 2. 프로필 조회 (로그인 후 받은 access_token 사용)
curl http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer eyJhbG..."

# 3. 프로필 수정
curl -X PATCH http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer eyJhbG..." \
  -H "Content-Type: application/json" \
  -d '{"nickname":"새닉네임"}'
```

---

## 7. 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|-----------|
| v1.0 | 2026-08-24 | 인증 API (Firebase 로그인, 토큰 갱신, 로그아웃, 사용자 프로필 조회/수정) |
