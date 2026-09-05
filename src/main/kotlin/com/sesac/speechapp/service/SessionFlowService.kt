package com.sesac.speechapp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sesac.speechapp.ai.AiContainerClient
import com.sesac.speechapp.dto.aicontainer.AiChatRequest
import com.sesac.speechapp.dto.aicontainer.ChatMessage
import com.sesac.speechapp.dto.aicontainer.ContainerImageItem
import com.sesac.speechapp.dto.aicontainer.ContainerUserInfo
import com.sesac.speechapp.dto.aicontainer.CreateSessionRequest
import com.sesac.speechapp.dto.aicontainer.NamingScoreRequest
import com.sesac.speechapp.dto.aicontainer.ProblemsReportRequest
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreRequest
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreRequest
import com.sesac.speechapp.dto.aicontainer.TotalReportRequest
import com.sesac.speechapp.dto.aicontainer.TurnResult
import com.sesac.speechapp.dto.session.AnswerDto
import com.sesac.speechapp.dto.session.ChoiceDto
import com.sesac.speechapp.dto.session.FeedbacksDto
import com.sesac.speechapp.dto.session.FinishData
import com.sesac.speechapp.dto.session.HintData
import com.sesac.speechapp.dto.session.ListenSubmitData
import com.sesac.speechapp.dto.session.ListenSubmitRequest
import com.sesac.speechapp.dto.session.MetricCardDto
import com.sesac.speechapp.dto.session.MetricTurnDto
import com.sesac.speechapp.dto.session.RadarDto
import com.sesac.speechapp.dto.session.SessionCreateData
import com.sesac.speechapp.dto.session.SessionHistoryItem
import com.sesac.speechapp.dto.session.SessionHistoryResponse
import com.sesac.speechapp.dto.session.SessionReportData
import com.sesac.speechapp.dto.session.TalkData
import com.sesac.speechapp.dto.session.TalkHistoryItem
import com.sesac.speechapp.dto.session.TurnDto
import com.sesac.speechapp.dto.session.UserVoiceEvalDto
import com.sesac.speechapp.dto.session.VoiceSubmitData
import com.sesac.speechapp.entity.Session
import com.sesac.speechapp.entity.Turn
import com.sesac.speechapp.entity.TurnImage
import com.sesac.speechapp.entity.VoiceRecord
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.UserProfileRepository
import com.sesac.speechapp.repository.ImageResourceRepository
import com.sesac.speechapp.repository.ImageThemaRepository
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.TurnImageRepository
import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.UserRepresentativeScoreRepository
import com.sesac.speechapp.repository.VoiceRecordRepository
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 세션 플로우 서비스 (05a §3 + 03a §2·§7 — v1.9 계약 기준).
 *
 * - 테마 랜덤 선택은 `demo.themes` 프로퍼티 (데모: TEST만 — 이미지 등록된 테마만 운영).
 * - 이야기 턴 하드캡: `demo.talk-turn-limit` (기획 확정 8턴 — 유저 8턴째 답변 후 AI 마무리
 *   응답(9번째)까지 허용, D-5 승인 사안 A: 체크 기준 = 유저 답변 수 기준).
 * - 유저 음성: OCI 업로드 시도 → 실패 시(스텁/오프라인) 논리 경로만 유지하고 계속.
 *
 * v1.9 (D-5, 2026-09-06) 재편:
 * - [1] 세션 2종 분기: createSession(userId, sessionType, thema?) — today(테마 랜덤) /
 *   theme(thema 고정). LEARNING_SESSION type+session_name 세팅, thema 유효성 E0400.
 * - [2] 리포트 2단계: 8번째 문제 채점 완료 시점에 /report/problems 자동 호출
 *   (afterCommit 백그라운드 + REQUIRES_NEW) → AQ+4지표 피드백 UPDATE + REP_SCORES 갱신(지점 ②).
 *   finish 시점에 학습 중단/완료 판정 → 중단(유저 talk 답변 1~3턴) = COMPLETED_NO_TALK +
 *   total 미호출 / 완료(4턴 이상·하드캡) = COMPLETED + total 백그라운드 호출
 *   (talkContext는 유저 4턴째 답변까지만). userMemory 갱신은 total 경로로 이관 완료(D-4 경계).
 * - [3] userAQ = REP_SCORES.USER_AQ 캐시 조회(산정식 폐지 — v1.7 계약).
 *   articulationRate·userRT = 최근 20개 창 내 최단 10 선정으로 수정(구 전체 이력 폐지).
 */
@Service
class SessionFlowService(
    private val aiContainerClient: AiContainerClient,
    private val sessionRepository: SessionRepository,
    private val turnRepository: TurnRepository,
    private val turnImageRepository: TurnImageRepository,
    private val voiceRecordRepository: VoiceRecordRepository,
    private val imageThemaRepository: ImageThemaRepository,
    private val imageResourceRepository: ImageResourceRepository,
    private val appUserRepository: AppUserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userRepresentativeScoreRepository: UserRepresentativeScoreRepository,
    private val userService: UserService,
    private val sessionReportBackgroundWorker: SessionReportBackgroundWorker,
    private val objectStorageService: ObjectStorageService,
    @Value("\${demo.talk-turn-limit:8}") private val talkTurnLimit: Int,
    @Value("\${demo.themes:TEST}") private val demoThemes: String
) {
    private val logger = LoggerFactory.getLogger(SessionFlowService::class.java)
    private val objectMapper = ObjectMapper()

    // ============================================================
    // [1] POST /api/v1/sessions/today · /theme — 세션 생성 (8문제 일괄)
    //     v2는 하위호환 유지(클라 데모용) — 동일 내부 로직 호출
    // ============================================================
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
            if (t.uppercase() !in ALLOWED_THEMAS) {
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
            age = profile?.birthDate?.let { calcAge(it) },
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
                contentType = toSeedType(problem.type),  // v1.4: listenText→LISTEN_TEXT 등 6종
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
                    turn.choicesJson = serializeChoices(options)
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
            var voiceRecordId: Long? = null
            if (problem.ttsPath != null) {
                val stubFile = stubTtsFile(turn.contentType)
                val voiceRecord = VoiceRecord(
                    userId = userId,
                    sessionId = sessionId,
                    turnId = turnIdVal,
                    speaker = "AI",
                    // B-4 (D-5): 타입별 전용 샘플 매핑 확정 — classpath tts_samples
                    voiceFilePath = "classpath:tts_samples/$stubFile",
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
                choices = if (turn.contentType == "LISTEN_TEXT" || turn.contentType == "LISTEN_PICTURE") deserializeChoices(turn.choicesJson) else null,
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

    // ============================================================
    // 4.3 LISTEN — 백엔드 자체 채점 (ADR-003, 즉시 응답)
    // ============================================================
    @Transactional
    fun submitListen(sessionId: Long, turnId: Long, selected: Int): ListenSubmitData {
        val turn = getTurn(sessionId, turnId)
        require(turn.contentType == "LISTEN_TEXT" || turn.contentType == "LISTEN_PICTURE") { "LISTEN 턴이 아닙니다 (${turn.contentType})" }

        val correctRef = turn.correctValue
            ?: throw IllegalStateException("LISTEN 정답 미생성 턴입니다 (turnId=$turnId)")
        val score = if (selected.toString() == correctRef) 100 else 0

        turn.selectedValue = selected.toString()
        turn.score = BigDecimal(score)
        turn.status = "SCORED"

        // [2.2] 8문제 채점 완료 감지 → /report/problems 자동 호출 (afterCommit 백그라운드)
        // 문제풀이 5종(LISTEN_TEXT/LISTEN_PICTURE/NAMING/SHADOWING/SELF_TALK) TURN이 8행이고
        // 전부 SCORED인 시점 — LISTEN 자체채점 직후도 포함.
        maybeTriggerProblemsReport(sessionId)

        return ListenSubmitData(turnId = turnId, score = score, correct = score == 100)
    }

    // ============================================================
    // 4.3 NAMING / SHADOWING / SELF_TALK — 컨테이너 채점
    // ============================================================
    @Transactional
    fun submitNaming(sessionId: Long, turnId: Long, userId: Long, file: MultipartFile): VoiceSubmitData {
        val turn = getTurn(sessionId, turnId)
        require(turn.contentType == "NAMING") { "NAMING 턴이 아닙니다" }
        val user = appUserRepository.findById(userId).orElseThrow()

        val objectKey = objectStorageService.buildVoiceKey(user.uuid, sessionId, turnId, "USER")
        saveUserVoice(objectKey, file)

        val response = aiContainerClient.scoreNaming(
            NamingScoreRequest(
                sessionId = sessionId,
                userId = userId,
                problemContext = turn.correctValue ?: "",
                userVoicePath = objectKey,
                hintCount = turn.hintsShown ?: 0,
                // v1.4: 0개(첫사용)면 0 전송 — 구 null 폐지. 최근 20개 창 적용 (D-5 [3.2])
                userRT = calculateUserRT(userId) ?: BigDecimal.ZERO
            )
        )
        return applyScoredResult(turn, sessionId, userId, objectKey, response.scoreNaming, response.userVoiceEval)
    }

    @Transactional
    fun submitShadowing(sessionId: Long, turnId: Long, userId: Long, file: MultipartFile): VoiceSubmitData {
        val turn = getTurn(sessionId, turnId)
        require(turn.contentType == "SHADOWING") { "SHADOWING 턴이 아닙니다" }
        val user = appUserRepository.findById(userId).orElseThrow()

        val objectKey = objectStorageService.buildVoiceKey(user.uuid, sessionId, turnId, "USER")
        saveUserVoice(objectKey, file)

        val response = aiContainerClient.scoreShadowing(
            ShadowingScoreRequest(
                sessionId = sessionId,
                userId = userId,
                problemContext = turn.correctValue ?: turn.promptText ?: "",
                userVoicePath = objectKey,
                // v1.4: 조음속도 — 0개(첫사용)면 0 전송. 최근 20개 창 적용 (D-5 [3.2])
                articulationRate = calculateArticulationRate(userId) ?: BigDecimal.ZERO
            )
        )
        return applyScoredResult(turn, sessionId, userId, objectKey, response.scoreShadowing, response.userVoiceEval)
    }

    @Transactional
    fun submitSelfTalk(sessionId: Long, turnId: Long, userId: Long, file: MultipartFile): VoiceSubmitData {
        val turn = getTurn(sessionId, turnId)
        require(turn.contentType == "SELF_TALK") { "SELF_TALK 턴이 아닙니다" }
        val user = appUserRepository.findById(userId).orElseThrow()

        val objectKey = objectStorageService.buildVoiceKey(user.uuid, sessionId, turnId, "USER")
        saveUserVoice(objectKey, file)

        // problemTag: tags.json 내용 통째로. 스텁 모드 — OCI 대신 논리 더미 태그 사용.
        val imageId = turnImageRepository.findByTurnIdOrderByImageOrderAsc(turnId).firstOrNull()?.imageId
        val imageName = imageId?.let { imageResourceRepository.findById(it).orElse(null)?.imageName } ?: ""
        val problemTag = """{"tags": ["사람", "상황", "행동", "$imageName"]}"""

        val response = aiContainerClient.scoreSelfTalk(
            SelfTalkScoreRequest(
                sessionId = sessionId,
                userId = userId,
                problemImage = imageName,
                problemTag = problemTag,
                userVoicePath = objectKey
            )
        )
        return applyScoredResult(turn, sessionId, userId, objectKey, response.scoreSelfTalk, response.userVoiceEval)
    }

    /** 공통 적재: TURN.score/answer_text + VOICE_RECORD USER 행 + 8문제 완료 감지 */
    private fun applyScoredResult(
        turn: Turn,
        sessionId: Long,
        userId: Long,
        objectKey: String,
        score: BigDecimal,
        eval: com.sesac.speechapp.dto.aicontainer.UserVoiceEval
    ): VoiceSubmitData {
        turn.answerText = eval.text
        turn.score = score
        turn.status = "SCORED"

        val voiceRecord = VoiceRecord(
            userId = userId,
            sessionId = sessionId,
            turnId = turn.id!!,
            speaker = "USER",
            voiceFilePath = objectKey,
            durationSeconds = eval.durationSecond,
            syllables = eval.syllables,
            speakingTime = eval.speakingTime,
            articulationTime = eval.articulationTime
        )
        voiceRecordRepository.save(voiceRecord)

        // [2.2] 8문제 채점 완료 감지 → /report/problems 자동 호출 (LISTEN 제출 경로와 동일)
        maybeTriggerProblemsReport(sessionId)

        return VoiceSubmitData(
            turnId = turn.id!!,
            score = score,
            voiceRecordId = voiceRecord.id!!,
            userVoiceEval = UserVoiceEvalDto(
                durationSecond = eval.durationSecond,
                syllables = eval.syllables,
                speakingTime = eval.speakingTime,
                articulationTime = eval.articulationTime,
                text = eval.text
            )
        )
    }

    // ============================================================
    // 4.4 NAMING 힌트 — 의미단서 → 조음단서 (ADR-004)
    // ============================================================
    @Transactional
    fun getHint(sessionId: Long, turnId: Long): HintData {
        val turn = getTurn(sessionId, turnId)
        require(turn.contentType == "NAMING") { "NAMING 턴이 아닙니다" }

        val shown = turn.hintsShown ?: 0
        if (shown >= 2) throw IllegalStateException("힌트를 모두 사용했습니다")

        val imageId = turnImageRepository.findByTurnIdOrderByImageOrderAsc(turnId).firstOrNull()?.imageId
            ?: throw IllegalStateException("턴에 매핑된 이미지가 없습니다 (turnId=$turnId)")
        val image = imageResourceRepository.findById(imageId)
            .orElseThrow { IllegalStateException("이미지가 없습니다 (imageId=$imageId)") }

        val hint = if (shown == 0) {
            HintData(
                hintOrder = 1,
                cueType = "SEMANTIC",
                text = image.semanticCue ?: "이미지에 의미단서가 등록되지 않았습니다"
            )
        } else {
            HintData(
                hintOrder = 2,
                cueType = "ARTICULATORY",
                text = image.articulatoryCue ?: "이미지에 조음단서가 등록되지 않았습니다"
            )
        }
        turn.hintsShown = shown + 1
        return hint
    }

    // ============================================================
    // 4.5 이야기 턴 (STORYTELLING) — 8턴 하드캡 (유저 답변 수 기준, 승인 사안 A)
    // ============================================================
    @Transactional
    fun talk(sessionId: Long, userId: Long, file: MultipartFile?): TalkData {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }
            .also { if (it.status != "IN_PROGRESS") throw IllegalStateException("진행 중인 세션이 아닙니다") }

        val talkTurns = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType == "STORYTELLING" }
        // 승인 사안 A: 하드캡 체크를 유저 답변 수 기준으로 — 유저 8턴째 답변 이후엔
        // AI 마무리 응답(9번째)까지 생성 허용 (03 계약서 §9.3 "8턴 하드캡 = 유저 8턴
        // 답변 후 AI 마무리 응답(9번째)까지 포함").
        val userAnswered = talkTurns.count { it.answerText != null }
        if (userAnswered >= talkTurnLimit) {
            throw IllegalStateException("이야기 턴 한도 초과 (${talkTurnLimit}턴). /finish로 종료하세요")
        }

        val isFirst = talkTurns.isEmpty()
        val user = appUserRepository.findById(userId).orElseThrow()

        // 유저 음성 저장 (첫 턴 제외 — AI가 먼저 말을 건다)
        var objectKey: String? = null
        if (!isFirst) {
            val f = file ?: throw IllegalArgumentException("이야기 턴에는 음성 파일이 필요합니다")
            objectKey = objectStorageService.buildVoiceKey(user.uuid, sessionId, nextTalkVoiceSeq(sessionId), "USER")
            saveUserVoice(objectKey, f)
        }

        // context 누적: 기존 이야기 턴 (AI 발화 → 유저 발화 순)
        val context = talkTurns.flatMap { t ->
            listOfNotNull(
                t.promptText?.let { ChatMessage("AI", it) },
                t.answerText?.let { ChatMessage("USER", it) }
            )
        }

        val response = aiContainerClient.aichat(
            AiChatRequest(
                sessionId = sessionId,
                userId = userId,
                userInfos = ContainerUserInfo(
                    nickname = user.profile?.nickname,
                    hobbies = user.profile?.hobbies,
                    tags = userService.buildTagsString(userId).ifEmpty { null },
                    sex = user.profile?.sex,
                    age = user.profile?.birthDate?.let { calcAge(it) },
                    userMemory = user.profile?.userMemory
                ),
                turnResults = buildTurnResults(sessionId),
                context = context,
                userVoicePath = objectKey
            )
        )

        // TURN INSERT: prompt_text = AI 발화, answer_text = 유저 발화(STT)
        val turn = Turn(
            sessionId = sessionId,
            turnNumber = talkTurns.size + 9,  // 8평가턴 이후 9번부터
            contentType = "STORYTELLING",
            status = "PENDING",
            promptText = response.llmResponse,
            answerText = response.userText
        )
        turn.status = "SCORED"  // 채점 없는 턴 — 대화 기록 완료 상태
        turnRepository.save(turn)
        val turnIdVal = turn.id ?: throw IllegalStateException("턴 ID 발급 실패")

        // VOICE_RECORD USER 행 (발화지표 3종 NULL — STORYTELLING 규격)
        if (objectKey != null) {
            voiceRecordRepository.save(
                VoiceRecord(
                    userId = userId,
                    sessionId = sessionId,
                    turnId = turnIdVal,
                    speaker = "USER",
                    voiceFilePath = objectKey,
                    durationSeconds = null,
                    syllables = null,
                    speakingTime = null,
                    articulationTime = null
                )
            )
        }

        return TalkData(
            turnId = turnIdVal,
            turnNumber = turn.turnNumber,
            aiText = response.llmResponse,
            userText = response.userText
        )
    }

    private fun nextTalkVoiceSeq(sessionId: Long): Long {
        val talkTurns = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType == "STORYTELLING" }
        return (talkTurns.size + 1).toLong()
    }

    // ============================================================
    // [2] 세션 종료 — 간이 보고서 응답 + 리포트 2단계 트리거
    // ============================================================

    /**
     * finish (05a §3.5 갱신): 간이 보고서 데이터(AQ + 4지표 피드백 + 상태)를 동기 응답으로
     * 돌려주고, 상세 보고서(/report/total)는 백그라운드 생성 — 응답 대기 없이 즉시 반환.
     *
     * 중단/완료 판정 (03 계약서 §9.3 — 승인 확정):
     * - 이야기(STORYTELLING) 턴 유저 답변 수 = answer_text != null 행 수
     * - 1~3턴 = 학습 중단 → STATUS=COMPLETED_NO_TALK + /report/total 미호출 +
     *   talk/total 피드백 NULL 유지 (간이 보고서는 DB 저장 — 기록탭 미표시는 클라 규약)
     * - 4턴 이상 = 학습 완료 → /report/total 백그라운드 호출 (talkContext는 유저
     *   4턴째 답변까지만 — buildTalkContext가 "답변 완료 턴까지" 필터 = 기존 로직 자연 충족)
     * - 8턴 하드캡 = COMPLETED (유저 8턴째 답변 후 AI 마무리 응답까지 생성됨)
     *
     * FinishData는 구조 유지하되 talk/total은 null 전송(하위호환) — 05a §3.5 명시.
     */
    @Transactional
    fun finishSession(sessionId: Long, userId: Long): FinishData {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }
            .also {
                if (it.userId != userId) throw IllegalArgumentException("세션 소유 사용자가 아닙니다")
                if (it.status != "IN_PROGRESS") throw IllegalStateException("이미 종료된 세션입니다 (${it.status})")
            }

        // 중단/완료 판정: 이야기 턴 유저 답변 수 (answer_text != null)
        val talkTurns = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType == "STORYTELLING" }
        val userTalkAnswers = talkTurns.count { it.answerText != null }

        // 간이 보고서 데이터 — 8문제 채점 완료 시점에 이미 적재된 세션 값 (미완료 세션이면 null)
        val finishData = FinishData(
            sessionAQ = session.aq ?: 0,
            feedbacks = FeedbacksDto(
                listenFeedback = session.listenFeedback,
                namingFeedback = session.namingFeedback,
                shadowingFeedback = session.shadowingFeedback,
                selfTalkFeedback = session.selfTalkFeedback,
                talkFeedback = null,     // 2단계 계약: 상세는 /report/total → §8.3에서 수령
                totalFeedback = null
            )
        )

        if (userTalkAnswers in 1..3) {
            // 학습 중단 — total 미호출 (스텁 로그로 미호출 증명 = 이 로그만 남고 상세 생성 지연 로그가 없음)
            session.status = "COMPLETED_NO_TALK"
            logger.info(
                "[D-5] 학습 중단 판정: sessionId={}, 유저 talk 답변 {}턴 → COMPLETED_NO_TALK, /report/total 미호출, talk/total 피드백 NULL 유지",
                sessionId, userTalkAnswers
            )
        } else {
            // 학습 완료 (유저 4턴 이상 마치기 / 8턴 하드캡 후 마무리) — total 백그라운드 호출
            session.status = "COMPLETED"
            logger.info(
                "[D-5] 학습 완료 판정: sessionId={}, 유저 talk 답변 {}턴 → COMPLETED, /report/total 백그라운드 호출",
                sessionId, userTalkAnswers
            )
            // 트랜잭션 커밋 후 백그라운드 실행 — 스텁 10초 지연이 메인 응답을 막지 않는다
            val userIdVal = session.userId
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    sessionReportBackgroundWorker.generateTotalReportInBackground(sessionId, userIdVal)
                }
            })
        }

        return finishData
    }

    // ============================================================
    // [2.2] /report/problems 자동 트리거 — 8번째 문제 채점 완료 감지
    // ============================================================

    /**
     * 문제풀이 5종(LISTEN_TEXT/LISTEN_PICTURE/NAMING/SHADOWING/SELF_TALK) TURN이
     * 8행이고 전부 SCORED인 시점에 /report/problems를 백그라운드 호출.
     * - 제출 트랜잭션 afterCommit에서 실행 — 메인 제출 응답은 스텁 지연(2~3초)과 무관하게 즉시 반환
     * - 갱신 지점 ②: AQ+4지표 피드백 UPDATE + REP_SCORES 재계산 (ADR-009)
     * - STATUS는 IN_PROGRESS 유지 (종료 판정은 finish)
     */
    private fun maybeTriggerProblemsReport(sessionId: Long) {
        val scored = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType in PROBLEM_TYPES }
        if (scored.size != 8 || scored.any { it.status != "SCORED" }) return

        val session = sessionRepository.findById(sessionId).orElse(null) ?: return
        val userId = session.userId
        logger.info("[D-5] 8문제 채점 완료 감지: sessionId={} → /report/problems 백그라운드 호출", sessionId)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                sessionReportBackgroundWorker.generateProblemsReportInBackground(sessionId, userId)
            }
        })
    }

    // ============================================================
    // [4.1] GET /users/me/sessions/history — 기록 카드 (05a §8.2)
    // ============================================================
    @Transactional(readOnly = true)
    fun getHistory(userId: Long): SessionHistoryResponse {
        // 필터: STATUS != COMPLETED_NO_TALK AND AQ IS NOT NULL
        // (AQ null = 간이 보고서 미생성 세션 — 카드에 AQ 표시 불가라 제외.
        //  학습 중간에 나간 IN_PROGRESS 세션도 자연 배제됨. 05a §8.2 규약에 규약 추가 기재)
        val rows = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .filter { it.status != "COMPLETED_NO_TALK" && it.aq != null }
        return SessionHistoryResponse(
            sessions = rows.map { s ->
                SessionHistoryItem(
                    sessionId = s.id!!,
                    sessionName = s.sessionName,
                    createdAt = s.createdAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    aq = s.aq
                )
            }
        )
    }

    // ============================================================
    // [4.2] GET /sessions/{id}/report — 세부 보고서 (05a §8.3)
    // ============================================================

    /**
     * 세부 보고서 — radar(세션 TURN 집계) + metricCards(4지표 카드) + talkHistory.
     * - 학습 중단 세션(COMPLETED_NO_TALK) → E0404 (리스트에도 없으니 직접 호출도 차단)
     * - userId 소유 검증 필수 (permitAll 경로 — 타 유저 세션 조회 방어)
     * - 응답 수신 시 REPORT_VIEWED_AT null이면 기록 (구현 단순성 기준: null일 때만 기록)
     */
    @Transactional
    fun getSessionReport(sessionId: Long, userId: Long): SessionReportData {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }
        if (session.userId != userId) {
            throw IllegalArgumentException("세션 소유 사용자만 조회할 수 있습니다 (sessionId=$sessionId)")
        }
        if (session.status == "COMPLETED_NO_TALK") {
            throw NoSuchElementException("학습 중단 세션은 세부 보고서가 없습니다 (sessionId=$sessionId)")
        }

        val turns = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
        val problemTurns = turns.filter { it.contentType != "STORYTELLING" }

        // radar: content_type별 평균 — LISTEN_TEXT+LISTEN_PICTURE 통합=LISTEN (4축).
        // score NULL 턴 제외, 해당 타입에 SCORED 턴이 없으면 null.
        val radar = RadarDto(
            listen = avgScoreOf(turns, listOf("LISTEN_TEXT", "LISTEN_PICTURE")),
            naming = avgScoreOf(turns, listOf("NAMING")),
            shadowing = avgScoreOf(turns, listOf("SHADOWING")),
            selfTalk = avgScoreOf(turns, listOf("SELF_TALK"))
        )

        // metricCards: 4지표 카드 — feedback=LEARNING_SESSION.*_feedback(간이 보고서 적재분)
        val metricCards = listOf(
            metricCard("LISTEN", session.listenFeedback, turns, listOf("LISTEN_TEXT", "LISTEN_PICTURE")),
            metricCard("NAMING", session.namingFeedback, turns, listOf("NAMING")),
            metricCard("SHADOWING", session.shadowingFeedback, turns, listOf("SHADOWING")),
            metricCard("SELF_TALK", session.selfTalkFeedback, turns, listOf("SELF_TALK"))
        )

        // talkHistory: STORYTELLING 턴 — speaker=AI|USER, VOICE_RECORD 매핑
        val talkTurnRows = turns.filter { it.contentType == "STORYTELLING" }
        val talkHistory = talkTurnRows.flatMap { t ->
            val aiVoice = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "AI" }
            buildList {
                if (t.promptText != null) {
                    add(
                        TalkHistoryItem(
                            speaker = "AI",
                            text = t.promptText!!,
                            ttsUrl = aiVoice?.let { "/api/v1/voice/${it.id}" }
                        )
                    )
                }
                if (t.answerText != null) {
                    val userVoice = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "USER" }
                    add(
                        TalkHistoryItem(
                            speaker = "USER",
                            text = t.answerText!!,
                            voiceUrl = userVoice?.let { "/api/v1/voice/${it.id}" }
                        )
                    )
                }
            }
        }

        // REPORT_VIEWED_AT — null일 때만 기록 (구현 단순성 우선 — 05a §8.3 규약)
        var recordedViewedAt: LocalDateTime? = null
        if (session.reportViewedAt == null) {
            val now = LocalDateTime.now()
            session.reportViewedAt = now
            recordedViewedAt = now
            logger.info("[D-5] REPORT_VIEWED_AT 최초 기록: sessionId={}, at={}", sessionId, now)
        }

        return SessionReportData(
            sessionId = sessionId,
            aq = session.aq,
            totalFeedback = session.totalFeedback,
            radar = radar,
            metricCards = metricCards,
            talkFeedback = session.talkFeedback,
            talkHistory = talkHistory,
            reportViewedAt = recordedViewedAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ?: session.reportViewedAt?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    private fun metricCard(
        type: String,
        feedback: String?,
        turns: List<Turn>,
        contentTypes: List<String>
    ): MetricCardDto {
        val typeTurns = turns.filter { it.contentType in contentTypes }
        val score = avgScoreOf(turns, contentTypes)
        return MetricCardDto(
            type = type,
            score = score,
            feedback = feedback,
            turns = typeTurns.map { t ->
                MetricTurnDto(
                    turnId = t.id!!,
                    turnNumber = t.turnNumber,
                    promptText = t.promptText,
                    ttsUrl = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "AI" }
                        ?.let { "/api/v1/voice/${it.id}" },
                    imageUrl = t.turnImageId()?.let { "/api/v1/content/images/$it/file" },
                    answer = buildAnswer(t)
                )
            }
        )
    }

    /**
     * 턴별 답변 조립 (05a §8.3):
     * - LISTEN={mediaType:"text", value: 선택했던 선택지 텍스트, correct: selected==correct} —
     *   selected_value는 1-based order(정수 문자열) → choices_json 역직렬화로 텍스트 추출
     *   (deserializeChoices 선례 재사용).
     * - LISTEN_PICTURE: 선택지 context가 image_id → value는 선택지 context 전달,
     *   mediaType은 선택지 따름("image"). 클라가 이미지 로드 가능 (승인 사안 C).
     * - 음성형={mediaType:"voice", value: answer_text(STT), voiceUrl}
     */
    private fun buildAnswer(t: Turn): AnswerDto? {
        return when (t.contentType) {
            "LISTEN_TEXT", "LISTEN_PICTURE" -> {
                val selected = t.selectedValue ?: return AnswerDto(mediaType = "text", value = null, correct = null)
                val choices = deserializeChoices(t.choicesJson)
                val selectedChoice = choices?.firstOrNull { it.order.toString() == selected }
                val correct = t.correctValue != null && selected == t.correctValue
                AnswerDto(
                    mediaType = selectedChoice?.mediaType?.lowercase() ?: "text",
                    value = selectedChoice?.context,   // LISTEN_TEXT=선택했던 텍스트 / LISTEN_PICTURE=image_id
                    correct = correct
                )
            }
            else -> {
                val voice = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "USER" }
                AnswerDto(
                    mediaType = "voice",
                    value = t.answerText,
                    correct = null,
                    voiceUrl = voiceRecordRepository.findByTurnId(t.id!!).firstOrNull { it.speaker == "USER" }
                        ?.let { "/api/v1/voice/${it.id}" }
                )
            }
        }
    }

    private fun Turn.turnImageId(): Long? =
        turnImageRepository.findByTurnIdOrderByImageOrderAsc(this.id!!).firstOrNull()?.imageId

    /** content_type별 TURN.score 평균 — score NULL 턴 제외, 대상 없으면 null */
    private fun avgScoreOf(turns: List<Turn>, contentTypes: List<String>): BigDecimal? =
        turns.filter { it.contentType in contentTypes && it.score != null }
            .map { it.score!! }
            .takeIf { it.isNotEmpty() }
            ?.let { list ->
                list.reduce { acc, d -> d + acc }.divide(BigDecimal(list.size), 2, RoundingMode.HALF_UP)
            }

    // ============================================================
    // helpers
    // ============================================================

    private fun getTurn(sessionId: Long, turnId: Long): Turn =
        turnRepository.findById(turnId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 턴입니다: $turnId") }
            .also { if (it.sessionId != sessionId) throw IllegalArgumentException("세션 불일치 (turn=$turnId, session=$sessionId)") }

    private fun saveUserVoice(objectKey: String, file: MultipartFile) {
        try {
            objectStorageService.uploadObject(objectKey, file.bytes, "audio/mp4")
        } catch (e: Exception) {
            // 스텁/오프라인 모드: OCI 실패해도 논리 경로 유지하고 진행 (데모 범위)
            logger.warn("OCI 음성 업로드 실패 — 논리 경로만 유지: {}", e.message)
        }
    }

    /** 턴 결과 (컨테이너 전달용): 8평가턴 — type 소문자 camelCase 매핑 */
    private fun buildTurnResults(sessionId: Long): List<TurnResult> =
        turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType != "STORYTELLING" }
            .map { t ->
                TurnResult(
                    turnId = t.turnNumber,
                    type = toContainerType(t.contentType),
                    context = t.correctValue ?: t.promptText,
                    userAnswer = t.answerText ?: t.selectedValue,
                    score = t.score
                )
            }

    /**
     * talkContext (§7.2): STORYTELLING 턴 — "답변 완료 턴까지만" 필터.
     * 학습 완료 판정 시 유저 4턴째 답변까지만 포함 규약: AI 응답 생성 중이어도
     * 유저 답변이 완료된 턴까지만 담는다 — promptText(AI)가 있어도 answerText(유저)가
     * null인 미완료 턴은 USER 메시지가 없으므로 AI+USER 쌍으로 자연 필터됨.
     * (확인 후 유지 — 기존 로직이 규약 충족, D-5 승인 시 확인 완료)
     */
    private fun buildTalkContext(sessionId: Long): List<ChatMessage> =
        turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType == "STORYTELLING" }
            .flatMap { t ->
                listOfNotNull(
                    t.promptText?.let { ChatMessage("AI", it) },
                    t.answerText?.let { ChatMessage("USER", it) }
                )
            }

    /** 컨테이너 소문자 타입 → DB seed 코드 (v1.4: listenText→LISTEN_TEXT 등) */
    private fun toSeedType(containerType: String): String = when (containerType.lowercase()) {
        "selftalk", "self_talk" -> "SELF_TALK"
        "listentext" -> "LISTEN_TEXT"
        "listenpicture" -> "LISTEN_PICTURE"
        else -> containerType.uppercase()
    }

    private fun toContainerType(seedCode: String): String = when (seedCode) {
        "SELF_TALK" -> "selfTalk"
        "LISTEN_TEXT" -> "listenText"
        "LISTEN_PICTURE" -> "listenPicture"
        else -> seedCode.lowercase()
    }

    /** BIRTH_DATE 기반 나이 산정 (03a §1.1 — 구 AGE 컬럼 폐지 대체) */
    private fun calcAge(birthDate: java.time.LocalDate): Int =
        java.time.Period.between(birthDate, java.time.LocalDate.now()).years

    /**
     * B-4 (D-5 [5.1]): 스텁 TTS 타입별 매핑 확정.
     * LISTEN_TEXT/LISTEN_PICTURE→tts_listen / NAMING→tts_naming /
     * SHADOWING→tts_shadowing / STORYTELLING(및 기타)→tts_hello
     * (기존 4종 샘플 재사용 — 새 mp3 추가 없이 경로 분기만 확정, 커밋 최소화)
     */
    private fun stubTtsFile(contentType: String): String = when (contentType) {
        "LISTEN_TEXT", "LISTEN_PICTURE" -> "tts_listen.mp3"
        "NAMING" -> "tts_naming.mp3"
        "SHADOWING" -> "tts_shadowing.mp3"
        "STORYTELLING" -> "tts_hello.mp3"
        else -> "tts_hello.mp3"
    }

    private fun serializeChoices(options: List<com.sesac.speechapp.dto.aicontainer.ContainerOption>): String {
        val list = options.mapIndexed { i, o ->
            mapOf(
                "order" to (i + 1),
                "mediaType" to if (o.type == "image") "IMAGE" else "TEXT",
                "context" to o.context
            )
        }
        return objectMapper.writeValueAsString(list)
    }

    private fun deserializeChoices(json: String?): List<ChoiceDto>? {
        if (json == null) return null
        return try {
            val root = objectMapper.readTree(json)
            root.map { node ->
                ChoiceDto(
                    order = node.get("order").asInt(),
                    mediaType = node.get("mediaType").asText(),
                    context = node.get("context").asText()
                )
            }
        } catch (e: Exception) {
            logger.warn("choices_json 파싱 실패: {}", e.message)
            null
        }
    }

    // ============================================================
    // [3] 산정식 — userAQ 캐시 교체 + articulationRate·userRT 최근 20개 창 (03 §10)
    // ============================================================

    /**
     * articulationRate (v1.4/03 계약서 §10): 최근 문제풀이(NAMING/SHADOWING/SELF_TALK)
     * 유저 음성 중 SYLLABLES NOT NULL·ARTICULATION_TIME > 0인 것 중 **최근 20개 창**
     * (createdAt 내림차순 20행) 안에서 조음속도(ARTICULATION_TIME÷SYLLABLES) 최단
     * (가장 빠른) 10개의 (SYLLABLES 총합 ÷ ARTICULATION_TIME 총합), 소수 2자리.
     * 대상 0개면 null — 호출부에서 0 전송 (첫사용 규약).
     * ⚠️ D-5 [3.2] 수정: 구 초안은 전체 이력 정렬 take(10) — 최근 20개 창 적용으로 교체.
     */
    private fun calculateArticulationRate(userId: Long): BigDecimal? {
        val voicedTypeTurnIds = turnRepository.findByContentTypeIn(listOf("NAMING", "SHADOWING", "SELF_TALK"))
            .map { it.id }.toSet()
        val records = voiceRecordRepository.findByUserId(userId)
            .filter { it.speaker == "USER" && it.turnId in voicedTypeTurnIds }
            .filter { (it.syllables ?: 0) > 0 && it.articulationTime != null && it.articulationTime!!.signum() > 0 }
            .sortedByDescending { it.createdAt }      // 최근 20개 창 — createdAt 내림차순
            .take(20)
        if (records.isEmpty()) return null
        val top10 = records
            .sortedBy { it.articulationTime!!.divide(BigDecimal(it.syllables!!), 6, RoundingMode.HALF_UP) }
            .take(10)
        val syllableSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + BigDecimal(r.syllables!!) }
        val articulationSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + r.articulationTime!! }
        if (articulationSum.signum() == 0) return null
        return syllableSum.divide(articulationSum, 2, RoundingMode.HALF_UP)
    }

    /**
     * userRT (§10): 최근 NAMING 음성 중 SYLLABLES>0·SPEAKING_TIME NOT NULL 대상
     * **최근 20개 창** 안에서 발화시간/음절수 최단 10개의 (발화시간 총합 ÷ 음절수 총합).
     * 대상 0개면 null — 호출부에서 0 전송 (v1.4: 구 null 전송 폐지).
     * ⚠️ D-5 [3.2] 수정: userRT도 최근 20개 창 적용 (구 전체 이력 폐지).
     */
    private fun calculateUserRT(userId: Long): BigDecimal? {
        val namingTurnIds = turnRepository.findByContentType("NAMING").map { it.id }.toSet()
        val records = voiceRecordRepository.findByUserId(userId)
            .filter { it.speaker == "USER" && it.turnId in namingTurnIds }
            .filter { (it.syllables ?: 0) > 0 && it.speakingTime != null }
            .sortedByDescending { it.createdAt }      // 최근 20개 창 — createdAt 내림차순
            .take(20)
        if (records.isEmpty()) return null
        val top10 = records
            .sortedBy { it.speakingTime!!.divide(BigDecimal(it.syllables!!), 6, RoundingMode.HALF_UP) }
            .take(10)
        val speakingSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + r.speakingTime!! }
        val syllableSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + BigDecimal(r.syllables!!) }
        if (syllableSum.signum() == 0) return null
        return speakingSum.divide(syllableSum, 6, RoundingMode.HALF_UP)
    }

    companion object {
        /**
         * USER_MEMORY 하드캡 (04 v2.6 §4.2): 8KB = 8192 **문자** 기준.
         * ⚠️ CLOB LENGTH()는 문자 수 — UTF-8 바이트와 다름. 절단·실측 모두 문자 수로 통일.
         */
        const val USER_MEMORY_HARD_CAP = 8192

        /** 문제풀이 5종 — STORYTELLING 제외 (TURN 집계·8문제 완료 감지 공용) */
        val PROBLEM_TYPES = listOf("LISTEN_TEXT", "LISTEN_PICTURE", "NAMING", "SHADOWING", "SELF_TALK")

        /** 테마 유효성 (D-5 [1.2] — IMAGE_THEMA CHECK 6종 중 EASY 기본 3종) */
        val ALLOWED_THEMAS = setOf("TEST", "HOSPITAL", "CAFE")
    }
}