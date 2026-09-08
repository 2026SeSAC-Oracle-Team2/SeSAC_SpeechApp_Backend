package com.sesac.speechapp.service

import com.sesac.speechapp.ai.AiContainerClient
import com.sesac.speechapp.dto.aicontainer.AiChatRequest
import com.sesac.speechapp.dto.aicontainer.ChatMessage
import com.sesac.speechapp.dto.aicontainer.ContainerUserInfo
import com.sesac.speechapp.dto.aicontainer.TurnResult
import com.sesac.speechapp.dto.session.FinishData
import com.sesac.speechapp.dto.session.FeedbacksDto
import com.sesac.speechapp.dto.session.HintData
import com.sesac.speechapp.dto.session.ListenSubmitData
import com.sesac.speechapp.dto.session.TalkData
import com.sesac.speechapp.dto.session.UserVoiceEvalDto
import com.sesac.speechapp.dto.session.VoiceSubmitData
import com.sesac.speechapp.entity.Turn
import com.sesac.speechapp.entity.VoiceRecord
import com.sesac.speechapp.repository.AppUserRepository
import com.sesac.speechapp.repository.ImageResourceRepository
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.TurnImageRepository
import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.VoiceRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile

/**
 * 세션 채점·진행 서비스 (D-8② 분할, 2026-09-06 — 동작 불변).
 *
 * SessionFlowService에서 이동: 문제 제출 4종(submitListen/Naming/Shadowing/SelfTalk)·
 * 힌트·이야기 턴·finish(중단/완료 판정·afterCommit 트리거)·problems 트리거 감지.
 * 로직 전부 SessionFlowService 9521f2b 기준 그대로 — 계약(05a §3.3~3.5)·키 무변경.
 *
 * ⚠️ 트랜잭션 경계 보존: finish 흐름의 afterCommit 백그라운드 트리거와
 * REQUIRES_NEW 적용자(SessionReportApplier) 구조는 건드리지 않는다.
 * afterCommit 콜백은 SessionReportBackgroundWorker(@Async 빈)를 호출 —
 * 프록시 경계(빈 주입 호출) 구조 유지.
 */
@Service
class SessionScoringService(
    private val aiContainerClient: AiContainerClient,
    private val sessionRepository: SessionRepository,
    private val turnRepository: TurnRepository,
    private val turnImageRepository: TurnImageRepository,
    private val voiceRecordRepository: VoiceRecordRepository,
    private val imageResourceRepository: ImageResourceRepository,
    private val appUserRepository: AppUserRepository,
    private val scoreCalculationService: ScoreCalculationService,
    private val userService: UserService,
    private val sessionReportBackgroundWorker: SessionReportBackgroundWorker,
    private val objectStorageService: ObjectStorageService,
    @Value("\${demo.talk-turn-limit:8}") private val talkTurnLimit: Int,
    @Value("\${ai.container.shared-audio-root:/home/opc/containers/llm}") private val sharedAudioRoot: String
) {
    private val logger = LoggerFactory.getLogger(SessionScoringService::class.java)

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
        turn.score = java.math.BigDecimal(score)
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
            com.sesac.speechapp.dto.aicontainer.NamingScoreRequest(
                sessionId = sessionId,
                userId = userId,
                problemContext = turn.correctValue ?: "",
                userVoicePath = objectKey,
                hintCount = turn.hintsShown ?: 0,
                // v1.4: 0개(첫사용)면 0 전송 — 구 null 폐지. 최근 20개 창 적용 (D-5 [3.2])
                userRT = scoreCalculationService.calculateUserRT(userId) ?: java.math.BigDecimal.ZERO
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
            com.sesac.speechapp.dto.aicontainer.ShadowingScoreRequest(
                sessionId = sessionId,
                userId = userId,
                problemContext = turn.correctValue ?: turn.promptText ?: "",
                userVoicePath = objectKey,
                // v1.4: 조음속도 — 0개(첫사용)면 0 전송. 최근 20개 창 적용 (D-5 [3.2])
                articulationRate = scoreCalculationService.calculateArticulationRate(userId) ?: java.math.BigDecimal.ZERO
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
            com.sesac.speechapp.dto.aicontainer.SelfTalkScoreRequest(
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
        score: java.math.BigDecimal,
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
    fun getHint(sessionId: Long, turnId: Long): com.sesac.speechapp.dto.session.HintData {
        val turn = getTurn(sessionId, turnId)
        require(turn.contentType == "NAMING") { "NAMING 턴이 아닙니다" }

        val shown = turn.hintsShown ?: 0
        if (shown >= 2) throw IllegalStateException("힌트를 모두 사용했습니다")

        val imageId = turnImageRepository.findByTurnIdOrderByImageOrderAsc(turnId).firstOrNull()?.imageId
            ?: throw IllegalStateException("턴에 매핑된 이미지가 없습니다 (turnId=$turnId)")
        val image = imageResourceRepository.findById(imageId)
            .orElseThrow { IllegalStateException("이미지가 없습니다 (imageId=$imageId)") }

        val hint = if (shown == 0) {
            com.sesac.speechapp.dto.session.HintData(
                hintOrder = 1,
                cueType = "SEMANTIC",
                text = image.semanticCue ?: "이미지에 의미단서가 등록되지 않았습니다"
            )
        } else {
            com.sesac.speechapp.dto.session.HintData(
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
                    age = user.profile?.birthDate?.let { SessionTurnSupport.calcAge(it) },
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
            .filter { it.contentType in SessionFlowService.PROBLEM_TYPES }
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
    // helpers
    // ============================================================

    private fun getTurn(sessionId: Long, turnId: Long): Turn =
        turnRepository.findById(turnId)
            .orElseThrow { IllegalArgumentException("존재하지 않는 턴입니다: $turnId") }
            .also { if (it.sessionId != sessionId) throw IllegalArgumentException("세션 불일치 (turn=$turnId, session=$sessionId)") }

    private fun saveUserVoice(objectKey: String, file: MultipartFile) {
        val bytes = file.bytes
        try {
            objectStorageService.uploadObject(objectKey, bytes, "audio/mp4")
        } catch (e: Exception) {
            // 스텁/오프라인 모드: OCI 실패해도 논리 경로 유지하고 진행 (데모 범위)
            logger.warn("OCI 음성 업로드 실패 — 논리 경로만 유지: {}", e.message)
        }
        // E2E-복구-2: 컨테이너가 shared_root + userVoicePath로 읽으므로(03a §0)
        // 공유폴더에 동일 바이트 사본을 기록한다. 실패 시 즉시 실패 — 조용히
        // 통과하면 컨테이너 404로 다시 변장한다.
        val sharedFile = java.io.File(sharedAudioRoot, objectKey)
        try {
            sharedFile.parentFile.mkdirs()
            java.nio.file.Files.write(sharedFile.toPath(), bytes)
        } catch (e: Exception) {
            throw IllegalStateException("공유폴더 음성 기록 실패 (${sharedFile.path}): ${e.message}", e)
        }
        logger.info("유저 음성 공유폴더 사본 기록: {}", sharedFile.path)
    }

    /** 턴 결과 (컨테이너 전달용): 8평가턴 — type 소문자 camelCase 매핑 */
    private fun buildTurnResults(sessionId: Long): List<TurnResult> =
        turnRepository.findBySessionIdOrderByTurnNumberAsc(sessionId)
            .filter { it.contentType != "STORYTELLING" }
            .map { t ->
                TurnResult(
                    turnId = t.turnNumber,
                    type = SessionTurnSupport.toContainerType(t.contentType),
                    context = t.correctValue ?: t.promptText,
                    userAnswer = t.answerText ?: t.selectedValue,
                    score = t.score
                )
            }
}