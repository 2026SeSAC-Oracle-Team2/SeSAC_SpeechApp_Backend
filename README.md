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

## 🖥️ 서버 세팅 (VM)

<details>
<summary>⬇️ 펼쳐서 보기 (JDK 설치 / 서버 실행 / 방화벽 / 문제 해결)</summary>

### 1. 필수 설치 요약

| 항목 | 최소 버전 | 비고 |
|------|-----------|------|
| Java JDK | 17+ | Spring Boot 3.x 이상 필수 |
| Gradle | 8.0+ | `./gradlew` wrapper로 대체 가능 |

---

### 2. Java JDK 설치

#### 방법 A: SDKMAN (모든 Linux 배포 공통 — 권장)

SDKMAN은 배포판에 관계없이 동일한 방법으로 JDK를 설치할 수 있어요.

```bash
# 1. SDKMAN 설치
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 2. 사용 가능한 JDK 목록 확인
sdk list java

# 3. OpenJDK 21 설치 (Temurin/Eclipse 기반)
#    아래 버전은 예시 — 실제 최신 버전은 `sdk list java`로 확인 후 설치
sdk install java 21.0.12-tem

# 4. 기본 JDK 설정
sdk default java 21.0.12-tem

# 5. 확인
java -version
```

#### 방법 B: OS 패키지 관리자 (배포판별)

**Ubuntu / Debian 계열:**

```bash
# 1. APT 업데이트
sudo apt-get update

# 2. OpenJDK 21 설치
sudo apt-get install -y openjdk-21-jdk

# 3. 설치 확인
java -version
javac -version

# 4. JAVA_HOME 설정 (선택, 권장)
# 설치 경로 확인 (SDKMAN이 아닌 APT/dnf 설치일 경우만)
ls /usr/lib/jvm/ 2>/dev/null
# 예: java-21-openjdk-arm64

echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

> **Debian 13(trixie) 참고:** `openjdk-17-jdk`는 기본 리포지토리에 없을 수 있어요. 대신 `openjdk-21-jdk`를 설치하세요.
> **SDKMAN으로 설치한 경우:** JDK는 `~/.sdkman/candidates/java/`에 설치되며, `.bashrc`에 `sdk default` 설정만 하면 돼요.

**Oracle Linux / RHEL / CentOS / Rocky Linux / AlmaLinux:**

```bash
# 1. 시스템 업데이트
sudo dnf update -y

# 2. OpenJDK 21 설치
sudo dnf install -y java-21-openjdk-devel

# 3. 설치 확인
java -version
javac -version

# 4. JAVA_HOME 설정 (선택, 권장)
ls /usr/lib/jvm/ 2>/dev/null
# 예: java-21-openjdk

echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

> **Oracle Linux 참고:** `dnf`가 없으면 `yum`을 사용하세요. (`sudo yum install -y java-21-openjdk-devel`)

---

### 3. Gradle 설치

Gradle은 **Gradle Wrapper**(`./gradlew`)를 사용하면 별도 설치 없이도 빌드가 가능해요. 단, wrapper를 사용하려면 Java JDK가 먼저 설치되어 있어야 합니다.

#### 방법 A: Gradle Wrapper 사용 (권장 — 프로젝트에 포함됨)

이 프로젝트에는 이미 Gradle Wrapper가 포함되어 있어요. JDK만 설치하면 바로 사용 가능합니다.

```bash
# JDK 설치 후 바로 사용
./gradlew --version
```

#### 방법 B: Gradle CLI 직접 설치

SDKMAN으로 설치 (모든 Linux 공통):

```bash
# SDKMAN이 이미 설치되어 있다면
sdk install gradle 8.10

# 확인
gradle --version
```

OS 패키지 관리자로 설치 (선택):

```bash
# Ubuntu/Debian
sudo apt-get install -y gradle

# Oracle Linux / RHEL
sudo dnf install -y gradle
```

---

### 4. 방화벽 설정 (VM 외부 접속 시 필수)

Spring Boot 서버는 기본적으로 **8080 포트**에서 실행됩니다. VM 외부(인터넷/모바일)에서 API를 호출하려면 **방화벽에서 8080 포트를 개방**해야 합니다.

#### OCI (Oracle Cloud) VM 기준

OCI는 **Security List**와 **Network Security Groups** 두 가지로 방화벽을 제어합니다.

**방법 A: OCI Console (웹 브라우저)**

1. [OCI Console](https://cloud.oracle.com) → Networking → Virtual Cloud Networks
2. 해당 VCN의 **Security List** 선택
3. **Ingress Rules** → **Add Ingress Rule** 클릭
4. 아래 내용 입력:
   - **Source Type:** CIDR
   - **Source CIDR:** `0.0.0.0/0` (또는 허용할 IP 대역)
   - **IP Protocol:** TCP
   - **Destination Port Range:** `8080`
5. **Add Ingress Rules** 저장

**방법 B: OCI CLI (터미널)**

```bash
# 1. OCI CLI 로그인
oci session authenticate

# 2. 보안 규칙 추가 (예시 — 자신의 VCN OCID, Subnet OCID 확인 필요)
oci network security-list update \
  --security-list-id ocid1.securitylist.oc1... \
  --ingress-security-rules '[{"source":"0.0.0.0/0","protocol":"6","tcpOptions":{"destinationPortRange":{"min":8080,"max":8080}}}]'
```

**방법 C: OS 방화벽 (iptables / firewalld / ufw)**

VM 내부 OS 방화벽도 확인하세요.

```bash
# === Ubuntu/Debian (ufw) ===
sudo ufw status
sudo ufw allow 8080/tcp
sudo ufw reload

# === Oracle Linux / RHEL / CentOS (firewalld) ===
sudo firewall-cmd --state
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload

# === iptables (직접) ===
sudo iptables -A INPUT -p tcp --dport 8080 -j ACCEPT
sudo iptables-save
```

#### 방화벽 확인

```bash
# 외부에서 VM IP로 접속 확인 (새 터미널에서)
curl http://{VM_IP}:8080/api/v1/auth/firebase \
  -X POST -H "Content-Type: application/json" -d '{"id_token":"test"}'
```

---

### 5. 서버 실행법

```bash
# 1. 레포 clone (처음 한 번)
git clone <repo-url>
cd SeSAC_SpeechApp_Backend

# 2. 브랜치 체크아웃
git checkout main

# 3. 시크릿 파일 배치 (root 권한으로 직접 복사 필요)
#    - secrets/sesac-teamproject-firebase-adminsdk-fbsvc-*.json
#    - .env (JWT_SECRET 등)
#    - .oci/ (OCI Object Storage용)
#
#    예시:
#    sudo cp /백업경로/firebase-adminsdk.json secrets/
#    sudo cp /백업경로/.env .
#    sudo cp -r /백업경로/.oci .
#    sudo chown -R $(whoami):$(whoami) secrets/ .env .oci/

# 4. JAVA_HOME 환경변수 설정 (SDKMAN으로 설치한 경우)
source ~/.bashrc
# 또는 수동 설정
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64  # Debian 기준 경로
# export JAVA_HOME=/usr/lib/jvm/java-21-openjdk        # Oracle Linux 기준 경로

# 5. 서버 실행 (local 프로필 = H2 인메모리 DB)
./gradlew bootRun --no-daemon

# 또는 빌드 후 JAR 직접 실행 (백그라운드)
./gradlew build --no-daemon
nohup java -jar build/libs/speechapp-0.0.1-SNAPSHOT.jar > logs/app.log 2>&1 &
```

---

### 6. 서버 상태 확인 및 종료

```bash
# 서버 프로세스 확인
ps aux | grep speechapp
ps aux | grep java | grep 8080

# 포트 점유 확인
lsof -i :8080        # 또는
ss -tlnp | grep 8080 # 또는
netstat -tlnp | grep 8080

# 서버 종료 (프로세스 ID 확인 후)
kill <PID>

# 강제 종료
kill -9 <PID>
```

---

### 7. API Health Check

```bash
# 인증 API 테스트 (Firebase ID Token 없이 — 에러 응답 확인용)
curl -X POST http://localhost:8080/api/v1/auth/firebase \
  -H "Content-Type: application/json" \
  -d '{"id_token":"test"}'

# 외부에서 VM IP로 접속 (방화벽 개방 후)
curl -X POST http://{VM_IP}:8080/api/v1/auth/firebase \
  -H "Content-Type: application/json" \
  -d '{"id_token":"test"}'

# H2 Console (개발용 DB 조회)
# 브라우저에서: http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:mem:speechapp
# User: sa
# Password: (비워둠)
```

---

### 8. 문제 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| `java: 명령어를 찾을 수 없음` | JDK 미설치 또는 PATH 미등록 | `sudo apt-get install openjdk-21-jdk` + `export JAVA_HOME=...` |
| `port 8080 already in use` | 이전 서버 프로세스가 남아있음 | `lsof -i :8080` → `kill -9 <PID>` |
| `Firebase Admin SDK 초기화 실패` | `secrets/*.json` 파일 없음 | 시크릿 파일 배치 확인 |
| `./gradlew: Permission denied` | 실행 권한 없음 | `chmod +x gradlew` |
| `BindException: 주소가 이미 사용 중입니다` | 이미 다른 서버가 8080 사용 중 | 기존 서버 종료 후 재시도 |
| **외부에서 VM:8080 접속 불가** | **방화벽 미개방** | **OCI Security List 또는 OS 방화벽에서 8080 개방** |

</details>

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
| 2026-08-24 | Spring Boot 프로젝트 초기화, 인증 API 완성 | 김윤혁 |

