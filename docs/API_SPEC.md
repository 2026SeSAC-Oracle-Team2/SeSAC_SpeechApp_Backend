# SeSAC Backend — API 명세서

> **버전:** v1.2 (2026-09-01)
> **기준:** P3-19 voice upload + session API

---

## Base URL

| 환경 | URL | 상태 |
|------|-----|------|
| 로컬(VM) | `http://localhost:8080` | ✅ |
| nginx(A안) | `http://localhost:80` | 🔄 P3-23 진행 중 |

---

## 인증 API (v1.0)

### POST /api/v1/auth/firebase
- Firebase 로그인 → JWT 발급

### POST /api/v1/auth/refresh
- Access Token 갱신

### POST /api/v1/auth/logout
- 로그아웃

### GET /api/v1/users/me
- 내 프로필 조회 (Bearer JWT)

### PATCH /api/v1/users/me
- 내 프로필 수정 (Bearer JWT)

### DELETE /api/v1/users/me
- 회원탈퇴 (Bearer JWT)

### POST /api/v1/users/me/profile-image
- 프로필 사진 업로드 (Bearer JWT, multipart)

### GET /api/v1/users/me/profile-image
- 프로필 사진 조회 (Bearer JWT, 백엔드 프록시 스트리밍)

---

## 세션 API (v1.2 — P3-19)

### POST /api/v1/sessions
- 세션 생성
- Request: `{ "userId": 1, "theme": "CAFE" }`
- Response: `{ "sessionId": 10, "createdAt": "2026-09-01T09:00:00" }`

---

## 음성 업로드 API (v1.2 — P3-19)

### POST /api/v1/voice/upload
- multipart/form-data
- Parameters:
  - `file` (m4a, max 50MB)
  - `userId` (Long)
  - `contentType` (String: LISTEN/NAMING/SHADOWING/SELF_TALK/STORYTELLING)
  - `sessionId` (Long, optional — 없으면 새 세션 생성)
- Response: `{ "voiceRecordId": 1, "turnId": 1, "sessionId": 10, "filePath": "containers/llm/{uuid}/{session}/{turn}_user.m4a" }`
