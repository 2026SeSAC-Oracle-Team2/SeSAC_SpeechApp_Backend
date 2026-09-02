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
import com.sesac.speechapp.dto.aicontainer.ReportRequest
import com.sesac.speechapp.dto.aicontainer.ReportResponse
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreRequest
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreResponse
import com.sesac.speechapp.dto.aicontainer.SessionFeedbacks
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreRequest
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreResponse
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
 *  - 세션 문제 생성 2~3초 / 답안 채점 0.8~1.5초 / 이야기 턴 1~2초 / 리포트 2~3초
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
    // §2 POST /sessions
    // ------------------------------------------------------------
    override fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        sleepRandom(2000, 3000, "세션 문제 생성")

        // 8문제: 4타입 × 2회, 무작위 순서
        val types = mutableListOf("listen", "listen", "naming", "naming", "shadowing", "shadowing", "selfTalk", "selfTalk")
        types.shuffle(rnd)

        // NAMING/SELF_TALK에 쓸 이미지 선택 (요청 이미지 풀에서)
        val imagePool = request.imageList.toMutableList()
        val namingImages = pickImages(imagePool, 2)
        val selfTalkImages = pickImages(imagePool, 2)

        var namingIdx = 0
        var selfTalkIdx = 0
        var listenIdx = 0

        val problems = types.mapIndexed { idx, type ->
            val turnId = idx + 1
            when (type) {
                "listen" -> {
                    val q = listenQuestions[listenIdx++ % listenQuestions.size]
                    ContainerProblem(
                        turnId = turnId,
                        type = type,
                        ttsPath = ttsPathFor(turnId),
                        passage = q.passage,
                        perType = ContainerPerType(correct = 0, options = q.options.map { ContainerOption("text", it) })
                    )
                }
                "naming" -> {
                    // 계약 §2: naming correct = 이미지 이름 (LLM이 imageList에서 선택)
                    val img = if (namingImages.isNotEmpty()) namingImages[namingIdx++ % namingImages.size] else null
                    ContainerProblem(
                        turnId = turnId,
                        type = type,
                        ttsPath = ttsPathFor(turnId),
                        passage = "사진 속 사물의 이름을 말해보세요",
                        perType = ContainerPerType(correct = img?.imageName ?: demoNamingWords.random(rnd))
                    )
                }
                "shadowing" -> ContainerProblem(
                    turnId = turnId,
                    type = type,
                    ttsPath = ttsPathFor(turnId),
                    passage = shadowingPassages[rnd.nextInt(shadowingPassages.size)],
                    perType = null
                )
                else -> { // selfTalk
                    val img = selfTalkImages.getOrNull(selfTalkIdx++) ?: selfTalkImages.firstOrNull()
                    ContainerProblem(
                        turnId = turnId,
                        type = type,
                        ttsPath = ttsPathFor(turnId),
                        passage = selfTalkSituations[rnd.nextInt(selfTalkSituations.size)],
                        perType = ContainerPerType(image = img?.imageId)
                    )
                }
            }
        }

        logger.info("[StubContainer] 세션 문제 생성 완료: {} 문제 (thema={})", problems.size, request.thema)
        return CreateSessionResponse(sessionId = request.sessionId, userId = request.userId, problemList = problems)
    }

    // ------------------------------------------------------------
    // §4 POST /answer/naming
    // ------------------------------------------------------------
    override fun scoreNaming(request: NamingScoreRequest): NamingScoreResponse {
        sleepRandom(800, 1500, "이름대기 채점")
        // 정답 단어 유사도 기반 시뮬레이션 + 힌트 감점 (감점 = 컨테이너 루브릭)
        val similarity = similarity(request.problemContext, request.userVoicePath)
        val hintPenalty = request.hintCount * 5
        val score = (similarity - hintPenalty).coerceIn(60, 95)
        logger.info("[StubContainer] naming 채점: context={}, hintCount={}, score={}", request.problemContext, request.hintCount, score)
        return NamingScoreResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            scoreNaming = BigDecimal(score),
            userVoiceEval = randomVoiceEval()
        )
    }

    // ------------------------------------------------------------
    // §5 POST /answer/shadowing
    // ------------------------------------------------------------
    override fun scoreShadowing(request: ShadowingScoreRequest): ShadowingScoreResponse {
        sleepRandom(800, 1500, "따라말하기 채점")
        val score = rnd.nextInt(26) + 70 // 70~95
        logger.info("[StubContainer] shadowing 채점: score={}", score)
        return ShadowingScoreResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            scoreShadowing = BigDecimal(score),
            userVoiceEval = randomVoiceEval()
        )
    }

    // ------------------------------------------------------------
    // §6 POST /answer/selfTalk
    // ------------------------------------------------------------
    override fun scoreSelfTalk(request: SelfTalkScoreRequest): SelfTalkScoreResponse {
        sleepRandom(800, 1500, "자발화 채점")
        // 태그 기반 커버리지 시뮬레이션: 55~90
        val score = rnd.nextInt(36) + 55
        logger.info("[StubContainer] selfTalk 채점: image={}, score={}", request.problemImage, score)
        return SelfTalkScoreResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            scoreSelfTalk = BigDecimal(score),
            userVoiceEval = randomVoiceEval()
        )
    }

    // ------------------------------------------------------------
    // §8 POST /aichat
    // ------------------------------------------------------------
    override fun aichat(request: AiChatRequest): AiChatResponse {
        sleepRandom(1000, 2000, "이야기 턴 생성")
        val isFirst = request.context.isEmpty()
        val userText = request.context.lastOrNull { it.speaker == "USER" }?.text
        val llmResponse = if (isFirst) {
            aiChatOpeners.random(rnd)
        } else {
            aiChatReplies.random(rnd)
        }
        logger.info("[StubContainer] aichat: first={}, contextSize={}", isFirst, request.context.size)
        return AiChatResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            llmResponse = llmResponse,
            userText = userText
        )
    }

    // ------------------------------------------------------------
    // §9 POST /report
    // ------------------------------------------------------------
    override fun generateReport(request: ReportRequest): ReportResponse {
        sleepRandom(2000, 3000, "리포트 생성")
        val aq = rnd.nextInt(31) + 55 // 55~85
        val scored = request.turns.mapNotNull { it.score?.toInt() }
        val avg = if (scored.isNotEmpty()) scored.average().toInt() else aq
        val feedbacks = SessionFeedbacks(
            listenFeedback = "알아듣기 점수가 양호합니다. 짧은 질문은 잘 알아듣지만, 문장이 길어지면 놓치는 부분이 있어요. (관련 평균 ${avg}점)",
            namingFeedback = "이름대기에서 익숙한 사물은 정확하게 말했어요. 머릿속 단어를 꺼내는 시간을 줄이는 연습을 추천합니다.",
            shadowingFeedback = "따라말하기 발음이 또렷합니다. 문장이 길어질 때 끝까지 또렷하게 말하는 연습을 해보세요.",
            selfTalkFeedback = "스스로 말하기에서 주변 상황을 잘 묘사했어요. 다양한 단어를 사용해 문장을 길게 만들어보세요.",
            talkFeedback = "자유 대화에 적극적으로 참여하셨습니다. 긴 문장으로 이야기하시는 모습이 좋았어요.",
            totalFeedback = "전반적으로 ${avg}점 수준의 안정적인 발화를 보였습니다. 꾸준한 연습으로 긴 문장과 어휘 다양성을 키워가세요. (세션 AQ: ${aq})"
        )
        logger.info("[StubContainer] 리포트 생성: AQ={}", aq)
        return ReportResponse(
            sessionId = request.sessionId,
            userId = request.userId,
            sessionAQ = aq,
            sessionFeedbacks = feedbacks
        )
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------
    private fun ttsPathFor(localTurnId: Int): String = "stub/tts_{turn}_ai.mp3".replace("{turn}", localTurnId.toString())

    private fun pickImages(pool: MutableList<com.sesac.speechapp.dto.aicontainer.ContainerImageItem>, n: Int): List<com.sesac.speechapp.dto.aicontainer.ContainerImageItem> {
        val picked = mutableListOf<com.sesac.speechapp.dto.aicontainer.ContainerImageItem>()
        repeat(2) {
            if (pool.isNotEmpty()) picked += pool.removeAt(rnd.nextInt(pool.size))
        }
        return picked
    }

    /** 정답 단어 vs (가짜)STT 유사도 시뮬레이션 — 60~95 */
    private fun similarity(context: String, voicePath: String): Int {
        val base = rnd.nextInt(36) + 60
        // 정답 단어가 발화 텍스트(가짜 파일명 기반)에 포함되는 경우 보정 — 실제 STT는 없음
        return base
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

    private fun sleepRandom(minMs: Int, maxMs: Int, label: String) {
        val ms = rnd.nextInt(maxMs - minMs + 1) + minMs
        logger.info("[StubContainer] {} 지연 시뮬레이션: {}ms", label, ms)
        try {
            Thread.sleep(ms.toLong())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private data class PassageChoice(val passage: String, val options: List<String>)