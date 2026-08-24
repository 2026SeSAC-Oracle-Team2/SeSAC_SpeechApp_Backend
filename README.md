# SeSAC 발화 연습 — Backend

> 실어증 환자 대상 AI 발화 연습 대화형 에이전트 (Android)의 백엔드 서버
> Kotlin / Spring Boot / Oracle XE / Docker / OCI

---

## 🏗️ 디렉토리 구조

```
SeSAC_SpeechApp_Backend/
├── .env                    # 시크릿 환경변수 (Git 제외)
├── .gitignore
├── .oci/                   # OCI API Key (Git 제외)
│   ├── config
│   └── OCI_SeSAC_API_key.pem
├── config/                 # 앱 설정 파일
├── secrets/                # Firebase Admin SDK JSON 등 (Git 제외)
│   └── sesac-teamproject-firebase-adminsdk-*.json
├── scripts/                # 배포/유틸리티 스크립트
├── src/                    # Spring Boot 소스 코드 (향후)
├── build.gradle.kts        # Gradle Kotlin DSL (향후)
└── README.md
```

---

## 🔐 시크릿 파일 배치법 (VM 배포 시)

이 레포지토리에는 **시크릿 파일이 포함되지 않습니다.** VM에서 clone 후 아래 파일들을 직접 배치해야 합니다.

### 1. Firebase Admin SDK JSON

- **VM 경로:** `/home/opc/app/secrets/sesac-teamproject-firebase-adminsdk-*.json`
- **용도:** Firebase ID Token 검증 (Spring Boot)

### 2. OCI API Key

- **VM 경로:** `/home/opc/app/.oci/OCI_SeSAC_API_key.pem`
- **+ `~/.oci/config`**
- **용도:** OCI Object Storage 버킷 접근

### 3. `.env` 파일

- **VM 경로:** `/home/opc/app/.env`
- **필수 항목:** (`.env.example` 참고)
  - `DB_URL` — Oracle XE 연결 문자열
  - `DB_USERNAME`, `DB_PASSWORD`
  - `OPENAI_API_KEY`
  - `FIREBASE_PROJECT_ID`
  - `OCI_BUCKET_NAME`
  - `OCI_NAMESPACE`

### 4. Spring Boot `application.yml` (향후)

- VM 환경에 맞는 DB URL, Object Storage 설정, 컨테이너 URL 포함
- `src/main/resources/application.yml`에 배치 (Git에 포함됨 — 시크릿은 `${}` placeholder 사용)

---

## 🚀 기술 스택

| 영역 | 기술 |
|------|------|
| 언어 | Kotlin |
| 프레임워크 | Spring Boot 3.x |
| 빌드 | Gradle (Kotlin DSL) |
| 데이터베이스 | Oracle XE 21c (Docker) |
| ORM | Spring Data JPA |
| 인증 | Firebase Auth + 자체 JWT |
| 파일 저장 | OCI Object Storage |
| AI 연동 | llm-container (FastAPI) / scoring-container (FastAPI) |
| 웹소켓 | Spring WebSocket + STOMP |
| 비동기 | Spring `@Async` + `ThreadPoolTaskExecutor` |
| 컨테이너 | Docker + Docker Compose |

---

## ⚠️ 보안 주의사항

- `.env`, `.oci/`, `secrets/` 디렉터리는 **절대 Git에 커밋하지 마세요.**
- `.gitignore`에 이미 포함되어 있습니다.
- 시크릿 키는 로컬에 백업 후 안전하게 관리하세요.

