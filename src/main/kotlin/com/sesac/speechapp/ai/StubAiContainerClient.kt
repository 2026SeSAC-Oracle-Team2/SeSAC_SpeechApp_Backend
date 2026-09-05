package com.sesac.speechapp.ai

import com.sesac.speechapp.dto.aicontainer.AiChatRequest
import com.sesac.speechapp.dto.aicontainer.AiChatResponse
import com.sesac.speechapp.dto.aicontainer.ChatMessage
import com.sesac.speechapp.dto.aicontainer.ContainerOption
import com.sesac.speechapp.dto.aicontainer.ContainerPerType
import com.sesac.speechapp.dto.aicontainer.ContainerProblem
import com.sesac.speechapp.dto.aicontainer.CreateSessionRequest
import com.sesac.speechapp.dto.aicontainer.CreateSessionResponse
import com.sesac.speechapp.dto.aicontainer.NamingScoreRequest
import com.sesac.speechapp.dto.aicontainer.NamingScoreResponse
import com.sesac.speechapp.dto.aicontainer.ProblemsReportRequest
import com.sesac.speechapp.dto.aicontainer.ProblemsReportResponse
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreRequest
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreResponse
import com.sesac.speechapp.dto.aicontainer.SessionFeedbacks
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreRequest
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreResponse
import com.sesac.speechapp.dto.aicontainer.TotalReportRequest
import com.sesac.speechapp.dto.aicontainer.TotalReportResponse
import com.sesac.speechapp.dto.aicontainer.TurnResult
import com.sesac.speechapp.dto.aicontainer.UserVoiceEval
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import kotlin.random.Random

/**
 * 스텁 AI 컨테이너 — 데모/개발용 (ai.container.mode=stub).
 *
 * 실제 컨테이너의 응답 지연을 시뮬레이션하고 그럴듯한 한국어 데이터를 반환한다.
 * 버리는 코드가 아니라 RealAiContainerClient와 동일 인터페이스 구현 + 지연값도
 * 실제 컨테이너 응답 시간에 맞춰 튜닝 → 데모·부하감 상황 재현용.
 *
 * 지연 (지시문 v1.97):
 *  - 세션 문제 생성 2~3초 / 답안 채점 0.8~1.5초 / 이야기 턴 1~2초
 *  - 리포트: problems(간이) 2~3초 / total(상세) 10초 (03a §7.3 스텁 지연값)
 *
 * v1.8 (D-4, 2026-09-05): generateReport에 userMemory 반환 추가 (§9 차이표 갱신) —
 * 기존값 있으면 기존+더미 신규 문장 / null이면 더미 신규 작성 (갱신 시뮬레이션).
 * aichat에는 userMemory 넣지 않음 — 갱신 지점은 /report/total 유일 (03a §10).
 *
 * v1.9 (D-5, 2026-09-06):
 * - createSession → createSessionToday/createSessionTheme 분리 (03a §2 —
 *   스텁은 내부 로직 동일 공유, 엔드포인트 메서드 분기만. 시나리오 플로우는 컨텐츠 미확정).
 * - generateReport → generateProblems/generateTotal 분리 (03a §7 — 2단계).
 *   generateProblems: sessionAQ = 8문제 점수 평균 올림 (§7.1 계약 — 구 랜덤 폐지),
 *   4지표 피드백만 non-null / talk·total은 null.
 *   generateTotal: talk/total 피드백 non-null + userMemory 갱신 반환.
 */
@Component
@ConditionalOnProperty(name = ["ai.container.mode"], havingValue = "stub", matchIfMissing = true)
class StubAiContainerClient : AiContainerClient {

    private val logger = LoggerFactory.getLogger(StubAiContainerClient::class.java)
    private val rnd = Random(System.nanoTime())

    // ------------------------------------------------------------
    // NAMING 정답 단어 소사전 — TEST 테마 이미지(cafe_N류 코드명) 대체용.
    // 데모: 이미지 이름이 한국어 단어가 아니어도 NAMING이 성립하도록 랜덤 매핑.
    // (운영에서는 IMAGE_NAME이 실제 단어 — 매핑 없이 그대로 사용)
    // ------------------------------------------------------------
    private val demoNamingWords = listOf(
        "커피", "케이크", "메뉴판", "컵", "젓가락", "물컵", "계산서", "의자",
        "창문", "우산", "가방", "시계", "안경", "볼펜", "노트", "핸드폰"
    )
    private val demoSemanticCues = listOf(
        "커피를 마실 때 쓰는 것", "카페에서 마시는 따뜻한 음료", "식사 후에 마시는 것",
        "손에 쥐고 마시는 용기", "매장에서 주문할 때 보는 것"
    )
    private val demoArticulatoryCues = listOf(
        "입을 동그랗게 벌리고 발음합니다", "첫소리는 'ㅋ', 'ㅁ' 같은 파열음입니다",
        "입술을 모아서 소리를 냅니다", "혀끝을 위잇몸에 대고 발음합니다"
    )
    private val listenQuestions = listOf(
        PassageChoice("오늘은 무슨 요일인가요? 화요일의 다음 날은 며칠인지 말해보세요", listOf("8월 12일", "8월 20일")),
        PassageChoice("병원에 몇 시에 가기로 했는지 말해보세요", listOf("3시 30분", "4시 30분")),
        PassageChoice("손님이 주문한 음료를 말해보세요", listOf("아이스 아메리카노", "따뜻한 카페라떼")),
        PassageChoice("여기서 금요일이 며칠인지 말해보세요", listOf("8월 12일", "8월 21일")),
        PassageChoice("처방전을 받을 시간을 말해보세요", listOf("2시 15분", "2시 50분"))
    )
    private val shadowingPassages = listOf(
        "나는 오늘 아침에 따뜻한 커피를 마셨습니다",
        "병원 예약은 전화로 할 수 있습니다",
        "카페에서 친구와 이야기를 나눴습니다",
        "약은 식후 30분에 복용합니다",
        "오늘 날씨가 참 좋네요"
    )
    private val selfTalkSituations = listOf(
        "카페에서 친구를 기다리는 상황입니다. 이곳에서 무엇을 하는지 말해보세요",
        "병원 대기실에서 기다리는 상황입니다. 보통 어떻게 지내는지 말해보세요",
        "오늘 먹고 싶은 음식이 있는 상황입니다. 무엇을 먹고 싶은지 말해보세요"
    )
    private val aiChatOpeners = listOf(
        "오늘 학습 잘하셨어요! 평소에 카페를 자주 가시나요?",
        "문제를 잘 풀어주셨네요. 요즘 어떤 일이 제일 즐거우세요?",
        "수고하셨습니다. 마지막으로 오늘 기분이 어떠신지 이야기해볼까요?"
    )
    private val aiChatReplies = listOf(
        "그렇군요! 저도 그 이야기 좋아요. 조금 더 자세히 말씀해주실래요?",
        "아, 정말요? 그런 일이 있으셨군요. 그때 기분이 어땠어요?",
        "좋은 이야기네요. 다음에는 어떻게 하고 싶으세요?",
        "오, 그 부분이 궁금했는데 잘 들었어요. 오늘 하루 중 가장 기억에 남는 순간은 언제였나요?",
        "말씀 감사해요. 내일은 어떤 계획이 있으세요?"
    )

    // ------------------------------------------------------------
    // §2 POST /sessions/today · /sessions/theme — 엔드포인트 분기, 내부 로직 동일
    // ------------------------------------------------------------
    override fun createSessionToday(request: CreateSessionRequest): CreateSessionResponse {
        logger.info("[StubContainer] /sessions/today 호출: sessionId={}, thema={}, userAQ={}", request.sessionId, request.thema, request.userAQ)
        return createSessionInternal(request, "today")
    }

    override fun createSessionTheme(request: CreateSessionRequest): CreateSessionResponse {
        logger.info("[StubContainer] /sessions/theme 호출: sessionId={}, thema={}, userAQ={}", request.sessionId, request.thema, request.userAQ)
        // 시나리오 플로우 데이터는 컨텐츠 팀 미확정 — 지금은 "테마 고정 + 무작위 출제" 스텁
        // (today와 내부 동일). 출제 로직 차이는 실컨테이너 몫 (03a §2).
        return createSessionInternal(request, "theme")
    }

    private fun createSessionInternal(request: CreateSessionRequest, endpoint: String): CreateSessionResponse {
        sleepRandom(2000, 3000, "세션 문제 생성 ($endpoint)")

        val problemList = mutableListOf<ContainerProblem>()

        // 8문제 타입 세트 (03a §2): LISTEN_TEXT 1 + LISTEN_PICTURE 1 + NAMING 2 +
        // SHADOWING 2 + SELF_TALK 2 — 무작위 순서
        val types = mutableListOf(
            "listenText", "listenPicture",
            "naming", "naming",
            "shadowing", "shadowing",
            "selfTalk", "selfTalk"
        )
        types.shuffle(rnd)

        // 풀 복사본 (제거 방식 — 중복 선택 방지)
        val namingPool = request.imageListNaming.toMutableList()
        val selfTalkPool = request.imageListSelfTalk.toMutableList()
        val listenPool = request.imageListListening.toMutableList()

        for (type in types) {
            when (type) {
                "listenText" -> {
                    val q = listenQuestions.random(rnd)
                    val correctIdx = rnd.nextInt(q.options.size)
                    problemList += ContainerProblem(
                        type = type, ttsPath = ttsPathFor(problemList.size + 1),
                        passage = q.passage,
                        perType = ContainerPerType(
                            correct = correctIdx,
                            options = q.options.map { ContainerOption("text", it) }
                        )
                    )
                }
                "listenPicture" -> {
                    val pool = if (listenPool.isNotEmpty()) listenPool else request.imageListNaming
                    val picked = pickImages(pool.toMutableList(), 2)
                    val correctIdx = rnd.nextInt(picked.size)
                    problemList += ContainerProblem(
                        type = type, ttsPath = ttsPathFor(problemList.size + 1),
                        passage = "${picked[correctIdx].imageName}을(를) 고르세요",
                        perType = ContainerPerType(
                            correct = correctIdx,
                            options = picked.map { ContainerOption("image", it.imageId.toString()) }
                        )
                    )
                }
                "naming" -> {
                    val img = pickImages(namingPool, 1).firstOrNull()
                        ?: request.imageListNaming.randomOrNull(rnd)
                        ?: continue
                    problemList += ContainerProblem(
                        type = "naming", ttsPath = ttsPathFor(problemList.size + 1),
                        passage = "이것은 무엇일까요? 사진 속 물건의 이름을 말해보세요.",
                        perType = ContainerPerType(correct = img.imageName)
                    )
                }
                "shadowing" -> {
                    problemList += ContainerProblem(
                        type = "shadowing", ttsPath = ttsPathFor(problemList.size + 1),
                        passage = shadowingPassages.random(rnd), perType = null
                    )
                }
                "selfTalk" -> {
                    val img = pickImages(selfTalkPool, 1).firstOrNull()
                        ?: request.imageListSelfTalk.randomOrNull(rnd)
                        ?: continue
                    problemList += ContainerProblem(
                        type = "selfTalk", ttsPath = ttsPathFor(problemList.size + 1),
                        passage = "다음 상황을 보고 묘사해보세요",
                        perType = ContainerPerType(image = img.imageId)
                    )
                }
            }
        }

        return CreateSessionResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            problemList = problemList.mapIndexed { i, p ->
                p.copy(turnId = i + 1, ttsPath = ttsPathFor(i + 1))
            }
        )
    }

    // ------------------------------------------------------------
    // §4 NAMING 채점
    // ------------------------------------------------------------
    override fun scoreNaming(request: NamingScoreRequest): NamingScoreResponse {
        sleepRandom(800, 1500, "NAMING 채점")
        val score = similarity(request.problemContext, request.userVoicePath)
        return NamingScoreResponse(
            sessionId = request.sessionId, userId = request.userId,
            scoreNaming = BigDecimal(score), userVoiceEval = randomVoiceEval()
        )
    }

    // ------------------------------------------------------------
    // §5 SHADOWING 채점
    // ------------------------------------------------------------
    override fun scoreShadowing(request: ShadowingScoreRequest): ShadowingScoreResponse {
        sleepRandom(800, 1500, "SHADOWING 채점")
        val score = similarity(request.problemContext, request.userVoicePath)
        return ShadowingScoreResponse(
            sessionId = request.sessionId, userId = request.userId,
            scoreShadowing = BigDecimal(score), userVoiceEval = randomVoiceEval()
        )
    }

    // ------------------------------------------------------------
    // §6 SELF_TALK 채점
    // ------------------------------------------------------------
    override fun scoreSelfTalk(request: SelfTalkScoreRequest): SelfTalkScoreResponse {
        sleepRandom(800, 1500, "SELF_TALK 채점")
        val score = rnd.nextInt(41) + 55 // 55~95
        return SelfTalkScoreResponse(
            sessionId = request.sessionId, userId = request.userId,
            scoreSelfTalk = BigDecimal(score), userVoiceEval = randomVoiceEval()
        )
    }

    // ------------------------------------------------------------
    // §8 이야기 턴
    // ------------------------------------------------------------
    override fun aichat(request: AiChatRequest): AiChatResponse {
        sleepRandom(1000, 2000, "이야기 턴 생성")
        val isFirst = request.context.isEmpty()
        // B-2: 이번 턴 유저 발화는 호출 시점에 context에 아직 없다 (TURN INSERT는 응답 수신 후).
        // 실컨테이너는 userVoicePath 음성의 실제 STT 결과를 userText로 반환하므로 구조 변경 불필요 —
        // 스텁 전용 보정: 음성이 있는 턴(userVoicePath != null)은 더미 STT 텍스트 반환,
        // 첫 호출(음성 없는 턴)은 03a §6 규약대로 null 유지.
        val userText = if (request.userVoicePath != null) {
            dummySttText()
        } else {
            null
        }
        val llmResponse = if (isFirst) {
            aiChatOpeners.random(rnd)
        } else {
            aiChatReplies.random(rnd)
        }
        logger.info("[StubContainer] aichat: first={}, contextSize={}, userText={}", isFirst, request.context.size, userText)
        // D-4 주의: aichat 응답에는 userMemory를 넣지 않는다 (§10 — 갱신 지점은 /report/total 유일)
        return AiChatResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            llmResponse = llmResponse,
            userText = userText
        )
    }

    // ------------------------------------------------------------
    // §7.1 POST /report/problems — 간이 보고서 (D-5 [2.1])
    // ------------------------------------------------------------
    override fun generateProblems(request: ProblemsReportRequest): ProblemsReportResponse {
        sleepRandom(2000, 3000, "간이 보고서 생성")
        // §7.1 계약: sessionAQ = 8개 문제 점수만으로 산출 — 소수점 올림은 컨테이너 책임.
        // (구 v1.8 랜덤 55~85 폐지 — D-5 승인 사안 D: 검증 ⑤a TURN 평균 정합 손계산 대조 가능하게)
        val scored = request.turns.mapNotNull { it.score?.toInt() }
        val aq = if (scored.isNotEmpty()) Math.ceil(scored.average()).toInt() else 0
        val listenAvg = avgOf(request.turns, listOf("listenText", "listenPicture"))
        val namingAvg = avgOf(request.turns, listOf("naming"))
        val shadowingAvg = avgOf(request.turns, listOf("shadowing"))
        val selfTalkAvg = avgOf(request.turns, listOf("selfTalk"))
        val feedbacks = SessionFeedbacks(
            listenFeedback = "알아듣기 문제를 대부분 정확히 골랐어요. (평균 ${listenAvg ?: "-"}점)",
            namingFeedback = "이름대기에서 익숙한 사물은 정확하게 말했어요. (평균 ${namingAvg ?: "-"}점)",
            shadowingFeedback = "문장을 또박또박 따라했어요. (평균 ${shadowingAvg ?: "-"}점)",
            selfTalkFeedback = "상황 묘사에 핵심 단어가 일부 빠졌어요. (평균 ${selfTalkAvg ?: "-"}점)",
            talkFeedback = null,     // §7.1 규약: 아직 생성 전 — null
            totalFeedback = null     // §7.1 규약: 아직 생성 전 — null
        )
        logger.info(
            "[StubContainer] 간이 보고서: AQ={} (turns={}), userMemory 전달 안 함 (§7.1)",
            aq, request.turns.size
        )
        return ProblemsReportResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            sessionAQ = aq,
            sessionFeedbacks = feedbacks
        )
    }

    // ------------------------------------------------------------
    // §7.2 POST /report/total — 상세 보고서 + userMemory 갱신 반환 (D-4/D-5)
    // ------------------------------------------------------------
    override fun generateTotal(request: TotalReportRequest): TotalReportResponse {
        sleepRandom(9000, 10000, "상세 보고서 생성")
        // §7.2 규약: talk/total만 non-null — 4지표는 problems에서 이미 적재됐으므로 null
        val feedbacks = SessionFeedbacks(
            listenFeedback = null,
            namingFeedback = null,
            shadowingFeedback = null,
            selfTalkFeedback = null,
            talkFeedback = "자유 대화에 적극적으로 참여하셨습니다. 긴 문장으로 이야기하시는 모습이 좋았어요.",
            totalFeedback = "전반적으로 안정적인 발화를 보였습니다. 꾸준한 연습으로 긴 문장과 어휘 다양성을 키워가세요."
        )

        // D-4 [4]: userMemory 갱신 시뮬레이션 (03a §9 — 실컨테이너 흐름 사전 검증용).
        // - 기존값 있으면 "기존값 + 더미 신규 문장" 반환 (갱신 시뮬레이션)
        // - null이면 더미 신규 작성 (첫 세션) — 11a 선언형 짧은 문장 스타일
        // - 갱신 지점은 /report/total 유일 (§10) — aichat 응답에는 userMemory를 넣지 않는다
        val existing = request.userMemory
        val newSentence = "- 오늘 학습 대화에서 카페와 커피 이야기를 좋아한다 (더미: ${request.sessionId})"
        val updatedMemory = if (existing != null) {
            "$existing\n$newSentence"
        } else {
            "- 첫 대화에서 문제 풀이를 차분히 수행했다\n- 카페와 일상 이야기에 흥얼거림이 보인다\n$newSentence"
        }
        logger.info(
            "[StubContainer] 상세 보고서: talkContext={}건, userMemory 기존={}자 → 반환={}자",
            request.talkContext.size, existing?.length ?: 0, updatedMemory.length
        )
        return TotalReportResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            userMemory = updatedMemory,
            sessionFeedbacks = feedbacks
        )
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------
    /** 타입별 평균 점수 (피드백 문구용) */
    private fun avgOf(turns: List<TurnResult>, types: List<String>): Int? =
        turns.filter { it.type in types && it.score != null }
            .map { it.score!!.toInt() }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()

    private fun ttsPathFor(localTurnId: Int): String = "stub/tts_{turn}_ai.mp3".replace("{turn}", localTurnId.toString())

    private fun pickImages(pool: MutableList<com.sesac.speechapp.dto.aicontainer.ContainerImageItem>, n: Int): List<com.sesac.speechapp.dto.aicontainer.ContainerImageItem> {
        val picked = mutableListOf<com.sesac.speechapp.dto.aicontainer.ContainerImageItem>()
        repeat(n) {
            if (pool.isNotEmpty()) picked += pool.removeAt(rnd.nextInt(pool.size))
        }
        return picked
    }

    /** 정답 단어 vs (가짜)STT 유사도 시뮬레이션 — 60~95 */
    private fun similarity(context: String, voicePath: String): Int {
        // 정답 단어가 발화 텍스트(가짜 파일명 기반)에 포함되는 경우 보정 — 실제 STT는 없음
        return rnd.nextInt(36) + 60
    }

    private fun randomVoiceEval(): UserVoiceEval = UserVoiceEval(
        durationSecond = rnd.nextInt(4) + 3,                    // 3~6초
        syllables = rnd.nextInt(15) + 10,                       // 10~24음절
        speakingTime = BigDecimal(rnd.nextInt(30) + 15).divide(BigDecimal(10)), // 1.5~4.4초
        articulationTime = BigDecimal(rnd.nextInt(20) + 10).divide(BigDecimal(10)), // 1.0~2.9초
        text = stubUtterance()
    )

    private fun stubUtterance(): String = listOf(
        "그거.. 음.. 커피요!",
        "저기 어.. 병원에 가봤어요",
        "카페에서.. 친구를 만났어요",
        "아.. 오늘은 날씨가 좋아서 산책을 했어요",
        "음.. 메뉴를 보고 주문했어요"
    ).random(rnd)

    /** B-2: 이야기 턴 더미 STT 텍스트 — 실컨테이너는 Whisper STT 결과를 반환 (스텁 전용 보정) */
    private fun dummySttText(): String = listOf(
        "오늘은 카페에 갔어요",
        "아침에 커피 한 잔 마셨어요",
        "친구랑 산책하면서 이야기했어요",
        "요즘 날씨가 좋아서 기분이 좋아요",
        "점심 메뉴를 고르는 중이에요"
    ).random(rnd)

    private fun sleepRandom(minMs: Int, maxMs: Int, label: String) {
        val ms = rnd.nextInt(maxMs - minMs + 1) + minMs
        logger.info("[StubContainer] {} 지연 시뮬레이션: {}ms", label, ms)
        try {
            Thread.sleep(ms.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private data class PassageChoice(val passage: String, val options: List<String>)
}