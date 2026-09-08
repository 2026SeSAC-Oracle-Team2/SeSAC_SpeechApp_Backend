package com.sesac.speechapp.service

import com.sesac.speechapp.ai.AiContainerClient
import com.sesac.speechapp.dto.aicontainer.ContainerImageItem
import com.sesac.speechapp.dto.aicontainer.ContainerUserInfo
import com.sesac.speechapp.dto.aicontainer.CreateSessionRequest
import com.sesac.speechapp.dto.session.SessionCreateData
import com.sesac.speechapp.dto.session.TurnDto
import com.sesac.speechapp.entity.Session
import com.sesac.speechapp.entity.Turn
import com.sesac.speechapp.entity.TurnImage
import com.sesac.speechapp.entity.VoiceRecord
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.ImageResourceRepository
import com.sesac.speechapp.repository.ImageThemaRepository
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.TurnImageRepository
import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.UserRepresentativeScoreRepository
import com.sesac.speechapp.repository.VoiceRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 세션 생성 서비스 (D-8② 분할, 2026-09-06 — 동작 불변).
 *
 * SessionFlowService에서 이동: createSessionToday·createSessionTheme·createSession 본체
 * (today/theme 분기·이미지 풀 3분할·세션명·TURN 8행 INSERT).
 * 로직 전부 SessionFlowService 9521f2b 기준 그대로 — 계약(05a §3.1)·키·엔드포인트 무변경.
 *
 * - 테마 랜덤 선택은 `demo.themes` 프로퍼티 (데모: TEST만 — 이미지 등록된 테마만 운영).
 */
@Service
class SessionCreationService(
    private val aiContainerClient: AiContainerClient,
    private val sessionRepository: SessionRepository,
    private val turnRepository: TurnRepository,
    private val turnImageRepository: TurnImageRepository,
    private val voiceRecordRepository: VoiceRecordRepository,
    private val imageThemaRepository: ImageThemaRepository,
    private val imageResourceRepository: ImageResourceRepository,
    private val appUserRepository: AppUserRepository,
    private val userRepresentativeScoreRepository: UserRepresentativeScoreRepository,
    private val userService: UserService,
    @Value("\${demo.themes:TEST}") private val demoThemes: String
) {
    private val logger = LoggerFactory.getLogger(SessionCreationService::class.java)

    @Transactional
    fun createSessionToday(userId: Long): SessionCreateData = createSession(userId, "today", null)

    @Transactional
    fun createSessionTheme(userId: Long, thema: String): SessionCreateData = createSession(userId, "theme", thema)

    /**
     * 세션 생성 — 2종 분기 (03a §2, P3-31).
     * - today: 테마 랜덤 선택(demo.themes) + 무작위 출제(스텁) → 컨테이너 /sessions/today
     * - theme: 전달받은 thema 고정 → 컨테이너 /sessions/theme
     *   (시나리오 플로우 데이터는 컨텐츠 팀 미확정 — 스텁 내부는 today와 동일,
     *    컨테이너 엔드포인트만 분기. 세션명 시나리오명은 컨텐츠 확정 후 교체 TODO(D-8 이후))
     * - thema 유효성: TEST/HOSPITAL/CAFE 이외 → E0400. IMAGE_THEMA 등록분만 허용
     *   (등록 없으면 기존 예외 흐름).
     */
    @Transactional
    fun createSession(userId: Long, sessionType: String, thema: String?): SessionCreateData {
        val user = appUserRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 사용자입니다: $userId") }

        // 1) 테마 결정 — today: demo.themes 랜덤 / theme: 파라미터 고정(유효성 검증)
        val theme = if (sessionType == "today") {
            demoThemes.split(",").map { it.trim() }.filter { it.isNotEmpty() }.random()
        } else {
            val t = thema?.trim().orEmpty()
            if (t.isEmpty()) throw IllegalArgumentException("테마 학습은 thema 파라미터가 필요합니다 (TEST/HOSPITAL/CAFE)")
            if (t.uppercase() !in SessionFlowService.ALLOWED_THEMAS) {
                throw IllegalArgumentException("허용되지 않는 테마입니다: $t (허용: TEST, HOSPITAL, CAFE)")
            }
            t.uppercase()
        }

        // 2) LEARNING_SESSION INSERT — type=today|theme + session_name (04 v2.6 §4.4).
        //    시나리오명(SESSION_NAME 확정분)은 컨텐츠 팀 명칭 — 지금은 테마 기반. TODO(D-8 이후): 컨텐츠 확정 후 시나리오명 교체
        val sessionName = "오늘의 학습 - $theme"
        val session = Session(
            userId = userId,
            theme = theme,
            type = sessionType,
            sessionName = sessionName,
            status = "IN_PROGRESS"
        )
        sessionRepository.save(session)
        val sessionId = session.id ?: throw IllegalStateException("세션 ID 발급 실패")

        // 3) IMAGE_THEMA로 테마 이미지 풀 조회
        val themaRows = imageThemaRepository.findByThemaKey(theme)
        if (themaRows.isEmpty()) {
            throw IllegalStateException("테마 '$theme'에 등록된 이미지가 없습니다. 관리자 페이지에서 이미지를 등록하세요.")
        }
        // 이미지 이름 포함 (컨테이너가 NAMING 정답 단어로 사용 → 백엔드가 이름→id 리매핑)
        val poolImages = imageResourceRepository.findAllById(themaRows.map { it.imageId })
        val imageList = themaRows.mapNotNull { t ->
            poolImages.firstOrNull { it.imageId == t.imageId }?.let {
                ContainerImageItem(imageId = t.imageId, imageName = it.imageName)
            }
        }

        // v1.2 계약: 3분할 이미지 풀 (분류 규약 03a §2: TAG_PATH 있음=SELF_TALK /
        // 없음+CUE 있음=NAMING / 둘 다 없음=LISTEN). 조건 필터는 백엔드 책임.
        val namingPool = imageList.filter { img -> poolImages.any { it.imageId == img.imageId && it.semanticCue != null && it.imageTagPath.isNullOrBlank() } }
        val selfTalkPool = imageList.filter { img -> poolImages.any { it.imageId == img.imageId && !it.imageTagPath.isNullOrBlank() } }
        val listenPool = imageList.filter { img -> poolImages.any { it.imageId == img.imageId && it.semanticCue == null && it.imageTagPath.isNullOrBlank() } }
        val requiredPerType = 2  // 각 타입 턴 수 (NAMING 2회 + SELF_TALK 2회)

        // 완화: 조건 충족 풀이 최소 개수에 못 미치면 조건을 완화한 풀로 폴백 + 경고 로그
        // (데모 TEST 테마는 이미지 5~6개뿐 — cue만 있고 tag 없는 이미지가 대부분이라 tag 풀 부족이 정상적인 상태).
        val relaxed = namingPool.size < requiredPerType || selfTalkPool.size < requiredPerType || listenPool.isEmpty()
        val namingFinal = if (namingPool.size >= requiredPerType) namingPool else imageList
        val selfTalkFinal = if (selfTalkPool.size >= requiredPerType) selfTalkPool else imageList
        val listenFinal = if (listenPool.isNotEmpty()) listenPool else imageList
        if (relaxed) {
            logger.warn(
                "[v1.2] 조건 이미지 풀 부족 — 필터 완화 (namingPool={}, selfTalkPool={}, listenPool={}, 전체={}): " +
                    "NAMING 출제 이미지에 cue 없는 이미지가 포함될 수 있음. 관리자 페이지에서 cue/tag 데이터 보충 권장",
                namingPool.size, selfTalkPool.size, listenPool.size, imageList.size
            )
        }

        // 4) userInfos + userAQ (v1.7: REP_SCORES.USER_AQ 캐시 직접 조회 — 산정식 실행 없음)
        val profile = user.profile
        val userInfos = ContainerUserInfo(
            nickname = profile?.nickname,
            hobbies = profile?.hobbies,
            // D-4 [1.2]: USER_PROFILE_TAGS 조립 주입 — UserService.buildTagsString 재사용
            tags = userService.buildTagsString(userId).ifEmpty { null },
            sex = profile?.sex,
            age = profile?.birthDate?.let { SessionTurnSupport.calcAge(it) },
            userMemory = profile?.userMemory
        )
        val userAQ = userRepresentativeScoreRepository.findByUserId(userId)?.userAq

        // 5) 컨테이너 POST /sessions/today·theme (스텁 2~3초) — v1.2: 3분할 풀 동봉
        val containerRequest = CreateSessionRequest(
            sessionId = sessionId,
            thema = theme,
            imageListListening = listenFinal.mapNotNull { it.imageId }
                .mapNotNull { id -> imageList.firstOrNull { it.imageId == id } },
            imageListNaming = namingFinal.mapNotNull { it.imageId }
                .mapNotNull { id -> imageList.firstOrNull { it.imageId == id } },
            imageListSelfTalk = selfTalkFinal.mapNotNull { it.imageId }
                .mapNotNull { id -> imageList.firstOrNull { it.imageId == id } },
            userId = userId,
            userInfos = userInfos,
            userAQ = userAQ
        )
        val containerResponse = if (sessionType == "today") {
            aiContainerClient.createSessionToday(containerRequest)
        } else {
            aiContainerClient.createSessionTheme(containerRequest)
        }

        // NAMING 정답 단어(=이미지 이름) → 이미지 id 리매핑용
        val namingCorrectWords = mutableMapOf<Int, String>()

        // 6) problemList → TURN 8행 INSERT (로컬 turnId → turn_number, ADR-006)
        val turnDtos = containerResponse.problemList.mapIndexed { index, problem ->
            val turnNumber = index + 1
            val turn = Turn(
                sessionId = sessionId,
                turnNumber = turnNumber,
                contentType = SessionTurnSupport.toSeedType(problem.type),  // v1.4: listenText→LISTEN_TEXT 등 6종
                status = "PENDING",
                promptText = problem.passage
            )
            when (problem.type.lowercase()) {
                "listentext", "listenpicture" -> {
                    val perType = problem.perType
                        ?: throw IllegalStateException("LISTEN 문제에 perType 없음 (turnId=${problem.turnId})")
                    val options = perType.options
                        ?: throw IllegalStateException("LISTEN 문제에 options 없음 (turnId=${problem.turnId})")
                    val correctIdx = (perType.correct as? Number)?.toInt()
                        ?: throw IllegalStateException("LISTEN 정답 인덱스 파싱 실패 (turnId=${problem.turnId})")
                    turn.correctValue = (correctIdx + 1).toString()  // 정답 choice order (1-based)
                    turn.choicesJson = SessionTurnSupport.serializeChoices(options)
                }
                "naming" -> {
                    val correctWord = problem.perType?.correct as? String
                        ?: throw IllegalStateException("NAMING 정답 단어 없음 (turnId=${problem.turnId})")
                    turn.correctValue = correctWord
                    namingCorrectWords[turnNumber] = correctWord
                }
                "shadowing" -> {
                    turn.correctValue = problem.passage  // 원문 = problemContext
                }
                "selftalk" -> { /* 이미지는 TURN PK 발급 후 매핑 */ }
            }
            turnRepository.save(turn)
            val turnIdVal = turn.id ?: throw IllegalStateException("턴 ID 발급 실패")

            // 이미지 매핑: SELF_TALK=perType.image / NAMING=정답 단어(이미지 이름) 역조회
            var namingImageId: Long? = null
            when (turn.contentType) {
                "SELF_TALK" -> problem.perType?.image?.let { imgId ->
                    turnImageRepository.save(TurnImage(turnId = turnIdVal, imageId = imgId, imageOrder = 1))
                }
                "NAMING" -> {
                    val word = namingCorrectWords[turnNumber]
                    val img = poolImages.firstOrNull { it.imageName == word }
                    if (img != null) {
                        val imgId = requireNotNull(img.imageId) { "이미지 ID 누락 (imageName=${img.imageName})" }
                        turnImageRepository.save(TurnImage(turnId = turnIdVal, imageId = imgId, imageOrder = 1))
                        namingImageId = imgId
                    }
                }
            }

            // VOICE_RECORD AI 행 (TTS 경로 매핑)
            // E2E-복구-2: 컨테이너가 실물 생성한 ttsPath를 그대로 저장한다.
            // - real 모드: "{userUUID}/{sid}/{n}_ai.mp3" (공유폴더 상대경로 —
            //   VoiceStreamController가 shared-audio-root 결합해 실물 스트리밍)
            // - stub 모드: "stub/tts_{n}_ai.mp3" 더미 문자열 (컨트롤러 %4 스텁 분기 유지)
            var voiceRecordId: Long? = null
            if (problem.ttsPath != null) {
                val voiceRecord = VoiceRecord(
                    userId = userId,
                    sessionId = sessionId,
                    turnId = turnIdVal,
                    speaker = "AI",
                    voiceFilePath = problem.ttsPath,
                    speakingTime = null,
                    articulationTime = null
                )
                voiceRecordRepository.save(voiceRecord)
                voiceRecordId = voiceRecord.id
            }

            TurnDto(
                turnId = turnIdVal,
                turnNumber = turnNumber,
                type = turn.contentType,
                ttsUrl = voiceRecordId?.let { "/api/v1/voice/$it" },
                passage = problem.passage,
                choices = if (turn.contentType == "LISTEN_TEXT" || turn.contentType == "LISTEN_PICTURE") SessionTurnSupport.deserializeChoices(turn.choicesJson) else null,
                imageId = when (turn.contentType) {
                    "SELF_TALK" -> problem.perType?.image
                    "NAMING" -> namingImageId
                    else -> null
                },
                imageUrl = when (turn.contentType) {
                    "SELF_TALK" -> problem.perType?.image?.let { "/api/v1/content/images/$it/file" }
                    "NAMING" -> namingImageId?.let { "/api/v1/content/images/$it/file" }
                    else -> null
                },
                hintAvailable = if (turn.contentType == "NAMING") 2 else null
            )
        }

        logger.info("세션 생성 완료: sessionId={}, type={}, theme={}, turns={}", sessionId, sessionType, theme, turnDtos.size)
        return SessionCreateData(sessionId = sessionId, theme = theme, type = sessionType, turns = turnDtos)
    }
}