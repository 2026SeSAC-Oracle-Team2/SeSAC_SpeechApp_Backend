package com.sesac.speechapp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sesac.speechapp.ai.AiContainerClient
import com.sesac.speechapp.dto.aicontainer.AiChatRequest
import com.sesac.speechapp.dto.aicontainer.ChatMessage
import com.sesac.speechapp.dto.aicontainer.ContainerImageItem
import com.sesac.speechapp.dto.aicontainer.ContainerUserInfo
import com.sesac.speechapp.dto.aicontainer.CreateSessionRequest
import com.sesac.speechapp.dto.aicontainer.NamingScoreRequest
import com.sesac.speechapp.dto.aicontainer.ReportRequest
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreRequest
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreRequest
import com.sesac.speechapp.dto.aicontainer.TurnResult
import com.sesac.speechapp.dto.session.ChoiceDto
import com.sesac.speechapp.dto.session.FeedbacksDto
import com.sesac.speechapp.dto.session.FinishData
import com.sesac.speechapp.dto.session.HintData
import com.sesac.speechapp.dto.session.ListenSubmitData
import com.sesac.speechapp.dto.session.SessionCreateData
import com.sesac.speechapp.dto.session.TalkData
import com.sesac.speechapp.dto.session.TurnDto
import com.sesac.speechapp.dto.session.UserVoiceEvalDto
import com.sesac.speechapp.dto.session.VoiceSubmitData
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
import com.sesac.speechapp.repository.VoiceRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * "오늘의 학습" 데모 세션 플로우 서비스 (05 문서 §4 + 03 계약서).
 *
 * - 테마 랜덤 선택은 `demo.themes` 프로퍼티 (데모: TEST만 — 이미지 등록된 테마만 운영).
 * - 이야기 턴 하드캡: `demo.talk-turn-limit` (데모 3턴).
 * - 유저 음성: OCI 업로드 시도 → 실패 시(스텁/오프라인) 논리 경로만 유지하고 계속.
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
    private val objectStorageService: ObjectStorageService,
    @Value("\${demo.talk-turn-limit:3}") private val talkTurnLimit: Int,
    @Value("\${demo.themes:TEST}") private val demoThemes: String
) {
    private val logger = LoggerFactory.getLogger(SessionFlowService::class.java)
    private val objectMapper = ObjectMapper()

    // ============================================================
    // 4.1 POST /api/v1/sessions — 세션 생성 (8문제 일괄)
    // ============================================================
    @Transactional
    fun createSession(userId: Long): SessionCreateData {
        val user = appUserRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 사용자입니다: $userId") }

        // 1) 테마 랜덤 선택
        val theme = demoThemes.split(",").map { it.trim() }.filter { it.isNotEmpty() }.random()

        // 2) LEARNING_SESSION INSERT
        val session = Session(userId = userId, theme = theme, status = "IN_PROGRESS")
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

        // 4) userInfos + userAQ
        val profile = user.profile
        val userInfos = ContainerUserInfo(
            nickname = profile?.nickname,
            likes = profile?.likes,
            sex = profile?.sex,
            age = profile?.age
        )
        val userAQ = calculateUserAQ(userId)

        // 5) 컨테이너 POST /sessions (스텁 2~3초)
        val containerResponse = aiContainerClient.createSession(
            CreateSessionRequest(
                sessionId = sessionId,
                thema = theme,
                imageList = imageList,
                userId = userId,
                userInfos = userInfos,
                userAQ = userAQ
            )
        )

        // NAMING 정답 단어(=이미지 이름) → 이미지 id 리매핑용
        val namingCorrectWords = mutableMapOf<Int, String>()

        // 6) problemList → TURN 8행 INSERT (로컬 turnId → turn_number, ADR-006)
        val turnDtos = containerResponse.problemList.mapIndexed { index, problem ->
            val turnNumber = index + 1
            val turn = Turn(
                sessionId = sessionId,
                turnNumber = turnNumber,
                contentType = toSeedType(problem.type),  // listen→LISTEN, selfTalk→SELF_TALK
                status = "PENDING",
                promptText = problem.passage
            )
            when (problem.type.lowercase()) {
                "listen" -> {
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
                    // 스텁 모드: classpath tts_samples 매핑 (실모드: 공유폴더→리네임→OCI 키)
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
                choices = if (turn.contentType == "LISTEN") deserializeChoices(turn.choicesJson) else null,
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

        logger.info("세션 생성 완료: sessionId={}, theme={}, turns={}", sessionId, theme, turnDtos.size)
        return SessionCreateData(sessionId = sessionId, theme = theme, turns = turnDtos)
    }

    // ============================================================
    // 4.3 LISTEN — 백엔드 자체 채점 (ADR-003, 즉시 응답)
    // ============================================================
    @Transactional
    fun submitListen(sessionId: Long, turnId: Long, selected: Int): ListenSubmitData {
        val turn = getTurn(sessionId, turnId)
        require(turn.contentType == "LISTEN") { "LISTEN 턴이 아닙니다" }

        val correctRef = turn.correctValue
            ?: throw IllegalStateException("LISTEN 정답 미생성 턴입니다 (turnId=$turnId)")
        val score = if (selected.toString() == correctRef) 100 else 0

        turn.selectedValue = selected.toString()
        turn.score = BigDecimal(score)
        turn.status = "SCORED"

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

        val response: com.sesac.speechapp.dto.aicontainer.NamingScoreResponse = aiContainerClient.scoreNaming(
            NamingScoreRequest(
                sessionId = sessionId,
                userId = userId,
                problemContext = turn.correctValue ?: "",
                userVoicePath = objectKey,
                hintCount = turn.hintsShown ?: 0,
                userRT = calculateUserRT(userId)
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

        val response: com.sesac.speechapp.dto.aicontainer.ShadowingScoreResponse = aiContainerClient.scoreShadowing(
            ShadowingScoreRequest(
                sessionId = sessionId,
                userId = userId,
                problemContext = turn.correctValue ?: turn.promptText ?: "",
                userVoicePath = objectKey
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

        val response: com.sesac.speechapp.dto.aicontainer.SelfTalkScoreResponse = aiContainerClient.scoreSelfTalk(
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

    /** 공통 적재: TURN.score/answer_text + VOICE_RECORD USER 행 */
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
    // 4.5 이야기 턴 (STORYTELLING) — 데모 3턴 하드캡
    // ============================================================
    @Transactional
    fun talk(sessionId: Long, userId: Long, file: MultipartFile?): TalkData {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }
            .also { if (it.status != "IN_PROGRESS") throw IllegalStateException("진행 중인 세션이 아닙니다") }

        val talkTurns = turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType == "STORYTELLING" }
        if (talkTurns.size >= talkTurnLimit) {
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
                    likes = user.profile?.likes,
                    sex = user.profile?.sex,
                    age = user.profile?.age
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
    // 4.6 세션 종료 + 리포트 — 동기 응답
    // ============================================================
    @Transactional
    fun finishSession(sessionId: Long, userId: Long): FinishData {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 세션입니다: $sessionId") }

        // 컨테이너 /report (스텁 2~3초)
        val response = aiContainerClient.generateReport(
            ReportRequest(
                sessionId = sessionId,
                userId = userId,
                turns = buildTurnResults(sessionId),
                talkContext = buildTalkContext(sessionId)
            )
        )

        session.aq = response.sessionAQ
        session.listenFeedback = response.sessionFeedbacks.listenFeedback
        session.namingFeedback = response.sessionFeedbacks.namingFeedback
        session.shadowingFeedback = response.sessionFeedbacks.shadowingFeedback
        session.selfTalkFeedback = response.sessionFeedbacks.selfTalkFeedback
        session.talkFeedback = response.sessionFeedbacks.talkFeedback
        session.totalFeedback = response.sessionFeedbacks.totalFeedback
        session.status = "COMPLETED"

        return FinishData(
            sessionAQ = response.sessionAQ,
            feedbacks = FeedbacksDto(
                listenFeedback = session.listenFeedback,
                namingFeedback = session.namingFeedback,
                shadowingFeedback = session.shadowingFeedback,
                selfTalkFeedback = session.selfTalkFeedback,
                talkFeedback = session.talkFeedback,
                totalFeedback = session.totalFeedback
            )
        )
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

    private fun buildTalkContext(sessionId: Long): List<ChatMessage> =
        turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType == "STORYTELLING" }
            .flatMap { t ->
                listOfNotNull(
                    t.promptText?.let { ChatMessage("AI", it) },
                    t.answerText?.let { ChatMessage("USER", it) }
                )
            }

    /** 컨테이너 소문자 타입 → DB seed 코드 (listen→LISTEN, selfTalk→SELF_TALK) */
    private fun toSeedType(containerType: String): String = when (containerType.lowercase()) {
        "selftalk", "self_talk" -> "SELF_TALK"
        else -> containerType.uppercase()
    }

    private fun toContainerType(seedCode: String): String = when (seedCode) {
        "SELF_TALK" -> "selfTalk"
        else -> seedCode.lowercase()
    }

    /** userAQ (§10): 최근 20세션 AQ 상위 10 평균 — 0개면 null */
    private fun calculateUserAQ(userId: Long): Int? {
        val aqs = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId).mapNotNull { it.aq }
        if (aqs.isEmpty()) return null
        return aqs.sortedDescending().take(10).average().let { Math.ceil(it).toInt() }
    }

    /**
     * userRT (§10): 최근 NAMING 음성 20개 중 발화시간/음절수 최단 10개의 (발화시간 총합 ÷ 음절수 총합).
     * 대상 0개면 null.
     */
    private fun calculateUserRT(userId: Long): BigDecimal? {
        val namingTurnIds = turnRepository.findByContentType("NAMING").map { it.id }.toSet()
        val records = voiceRecordRepository.findByUserId(userId)
            .filter { it.speaker == "USER" && it.turnId in namingTurnIds }
            .filter { (it.syllables ?: 0) > 0 && it.speakingTime != null }
        if (records.isEmpty()) return null
        val top10 = records
            .sortedBy { it.speakingTime!!.divide(BigDecimal(it.syllables!!), 6, RoundingMode.HALF_UP) }
            .take(10)
        val speakingSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + r.speakingTime!! }
        val syllableSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + BigDecimal(r.syllables!!) }
        if (syllableSum.signum() == 0) return null
        return speakingSum.divide(syllableSum, 6, RoundingMode.HALF_UP)
    }

    private fun stubTtsFile(contentType: String): String = when (contentType) {
        "LISTEN" -> "tts_listen.mp3"
        "NAMING" -> "tts_naming.mp3"
        "SHADOWING" -> "tts_shadowing.mp3"
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
}