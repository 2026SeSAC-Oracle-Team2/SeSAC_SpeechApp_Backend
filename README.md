# SeSAC 발화 연습 — Backend

> 실어증 환자 대상 AI 발화 연습 대화형 에이전트 (Android)의 백엔드 서버
> Kotlin / Spring Boot / Oracle XE / Docker / OCI

---

## 📋 진행 상황

| 영역 | 완료 | 상태 |
|------|------|------|
| 프로젝트 초기화 | ✅ | Spring Boot 4.1.1 + Kotlin 2.3.21 + Gradle wrapper |
| DB Entity (H2) | ✅ | `AppUser`, `UserProfile` — JPA + H2 인메모리 |
| Firebase Auth | ✅ | ID Token 검증 + 자체 JWT(Access/Refresh) 발급/검증 |
| 인증 API | ✅ | `POST /auth/firebase`, `POST /auth/refresh`, `POST /auth/logout` |
| 사용자 API | ✅ | `GET /users/me`, `PATCH /users/me` |
| 에러 핸들링 | ✅ | `GlobalExceptionHandler` + Logback |
| 음성 업로드 API | ⬜ | Phase 3에서 진행 예정 |
| WebSocket | ⬜ | Phase 3에서 진행 예정 |
| Oracle DB 연동 | ⬜ | Database 세션 DDL 완료 후 교체 예정 |
| AI 컨테이너 연동 | ⬜ | AI Containers 세션 완료 후 연동 예정 |

> **오늘 완료 체크포인트:** Android가 Firebase Google Sign-In 후 메인 화면까지 진입할 수 있도록 **인증 API** 완성

---

## 🏗️ 디렉토리 구조

```
SeSAC_SpeechApp_Backend/
├── .env                        # 시크릿 환경변수 (Git 제외)
├── .gitignore
├── .oci/                       # OCI API Key (Git 제외)
│   ├── config
│   └── OCI_SeSAC_API_key.pem
├── secrets/                    # Firebase Admin SDK JSON (Git 제외)
│   └── sesac-teamproject-firebase-adminsdk-fbsvc-*.json
├── config/                     # 앱 설정 파일
├── scripts/                    # 배포/유틸리티 스크립트
├── docs/                       # API 명세서 등 문서 ← 여기 확인!
│   └── API_SPEC.md
├── gradle/                     # Gradle wrapper
├── gradlew / gradlew.bat       # Gradle wrapper 스크립트
├── build.gradle.kts            # Gradle Kotlin DSL (의존성: Firebase Admin, JJWT, H2 등)
├── settings.gradle.kts
├── src/
│   └── main/
│       ├── kotlin/com/sesac/speechapp/
│       │   ├── config/         # JwtProperties, SecurityConfig
│       │   ├── controller/   # AuthController, UserController
│       │   ├── service/      # AuthService, UserService
│       │   ├── repository/   # AppUserRepository, UserProfileRepository
│       │   ├── entity/       # AppUser, UserProfile
│       │   ├── dto/          # ApiResponse, AuthDto, FirebaseAuthRequest
│       │   ├── exception/    # GlobalExceptionHandler
│       │   └── security/     # FirebaseAuthUtil, JwtTokenProvider, JwtAuthenticationFilter
│       └── resources/
│           ├── application.yml       # 환경별 프로필 (local/dev/prod)
│           └── logback-spring.xml     # Console-only 로깅
│   └── test/
└── README.md
```

---

## 🚀 기술 스택

| 영역 | 기술 | 버전 |
|------|------|------|
| 언어 | Kotlin | 2.3.21 |
| 프레임워크 | Spring Boot | 4.1.1 |
| 빌드 | Gradle (Kotlin DSL) | 8.10 |
| 데이터베이스 | H2 (개발) / Oracle XE (운영) | 21c |
| ORM | Spring Data JPA | 4.1.1 |
| 인증 | Firebase Auth + 자체 JWT | firebase-admin 9.4.3, jjwt 0.12.6 |
| 파일 저장 | OCI Object Storage | 향후 연동 |
| AI 연동 | llm-container / scoring-container | 향후 연동 |
| 웹소켓 | Spring WebSocket | 향후 연동 |

---

## 🖥️ 로컬 개발 환경 (VM 서버)

### 필수 설치

- Java JDK 17+ (현재 VM: OpenJDK 21)
- Gradle (또는 `./gradlew` wrapper 사용)

### 서버 실행법

```bash
# 1. 레포 clone (처음 한 번)
git clone <repo-url>
cd SeSAC_SpeechApp_Backend

# 2. 브랜치 체크아웃 (인증 API 브랜치)
git checkout feature/auth-api

# 3. 시크릿 파일 배치 (root로 먼저 복사)
#    - secrets/sesac-teamproject-firebase-adminsdk-fbsvc-*.json
#    - .env
#    - .oci/

# 4. 서버 실행 (local 프로필 = H2 인메모리 DB)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
./gradlew bootRun --no-daemon

# 또는 빌드 후 JAR 실행
./gradlew build --no-daemon
java -jar build/libs/speechapp-0.0.1-SNAPSHOT.jar
```

### 서버 확인

```bash
# Health check
curl http://localhost:8080/api/v1/auth/firebase \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"id_token":"test"}'

# H2 Console (개발용 DB 조회)
# http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:mem:speechapp
```

---

## 📡 API 문서

상세 API 명세는 `docs/API_SPEC.md` 참고

| 엔드포인트 | 메서드 | 설명 | 인증 |
|-----------|--------|------|------|
| `/api/v1/auth/firebase` | POST | Firebase 로그인 → JWT 발급 | 불필요 |
| `/api/v1/auth/refresh` | POST | Access Token 갱신 | Refresh Token |
| `/api/v1/auth/logout` | POST | 로그아웃 | Access Token |
| `/api/v1/users/me` | GET | 내 프로필 조회 | Bearer JWT |
| `/api/v1/users/me` | PATCH | 내 프로필 수정 (닉네임) | Bearer JWT |

---

## 🔐 시크릿 파일 배치법 (VM 배포 시)

이 레포지토리에는 **시크릿 파일이 포함되지 않습니다.** VM에서 clone 후 아래 파일들을 직접 배치해야 합니다.

### 1. Firebase Admin SDK JSON

- **경로:** `secrets/sesac-teamproject-firebase-adminsdk-*.json`
- **용도:** Firebase ID Token 검증 (Spring Boot)

### 2. OCI API Key

- **경로:** `.oci/config`, `.oci/*.pem`
- **용도:** OCI Object Storage 버킷 접근

### 3. `.env` 파일

- **경로:** `.env`
- **용도:** 환경변수 주입 (JWT_SECRET, DB 설정 등)

> ⚠️ `.gitignore`에 이미 포함되어 있습니다. 절대 Git에 커밋하지 마세요.

---

## 🌿 Git 브랜치 전략

| 브랜치 | 설명 |
|--------|------|
| `main` | 운영/배포 브랜치 (Oracle DB 연동) |
| `feature/auth-api` | **현재 작업 브랜치** — 인증 API 완료 |
| 향후: `feature/voice-upload` | 음성 업로드 API |
| 향후: `feature/websocket` | WebSocket + AI 컨테이너 연동 |

---

## ⚠️ 보안 주의사항

- `.env`, `.oci/`, `secrets/` 디렉터리는 **절대 Git에 커밋하지 마세요.**
- 시크릿 키는 로컬에 백업 후 안전하게 관리하세요.
- `application.yml`의 `jwt.secret`은 production 환경에서 반드시 변경하세요.

---

## 📅 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|-----------|--------|
| 2026-08-24 | Spring Boot 프로젝트 초기화, 인증 API 완성 | Hermes (Agent) |

