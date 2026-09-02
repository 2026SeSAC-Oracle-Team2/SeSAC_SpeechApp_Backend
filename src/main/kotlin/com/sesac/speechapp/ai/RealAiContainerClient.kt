package com.sesac.speechapp.ai

import com.sesac.speechapp.dto.aicontainer.AiChatRequest
import com.sesac.speechapp.dto.aicontainer.AiChatResponse
import com.sesac.speechapp.dto.aicontainer.CreateSessionRequest
import com.sesac.speechapp.dto.aicontainer.CreateSessionResponse
import com.sesac.speechapp.dto.aicontainer.NamingScoreRequest
import com.sesac.speechapp.dto.aicontainer.NamingScoreResponse
import com.sesac.speechapp.dto.aicontainer.ReportRequest
import com.sesac.speechapp.dto.aicontainer.ReportResponse
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreRequest
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreResponse
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreRequest
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * 실제 AI 컨테이너 호출 클라이언트 (ai.container.mode=real).
 *
 * ⚠️ TODO: AI 컨테이너(FastAPI) 배포 후 활성화. 계약서 03의 엔드포인트/바디 그대로.
 *  - 타임아웃/에러 규약은 미정(고도화 과제) — 데모 단계에서는 컨테이너 가용성 가정.
 *  - 음성 파일은 HTTP로 보내지 않는다: 공유폴더 경로(docker compose volume)만 전달.
 */
@Component
@ConditionalOnProperty(name = ["ai.container.mode"], havingValue = "real")
class RealAiContainerClient(
    builder: RestClient.Builder
) : AiContainerClient {

    // TODO: base-url을 application.yml ai.container.base-url 로 주입
    private val restClient: RestClient = builder
        .baseUrl("http://localhost:8000")
        .build()

    override fun createSession(request: CreateSessionRequest): CreateSessionResponse =
        restClient.post()
            .uri("/sessions")
            .body(request)
            .retrieve()
            .body(CreateSessionResponse::class.java)
            ?: throw IllegalStateException("AI 컨테이너 /sessions 응답이 비어 있습니다")

    override fun scoreNaming(request: NamingScoreRequest): NamingScoreResponse =
        restClient.post()
            .uri("/answer/naming")
            .body(request)
            .retrieve()
            .body(NamingScoreResponse::class.java)
            ?: throw IllegalStateException("AI 컨테이너 /answer/naming 응답이 비어 있습니다")

    override fun scoreShadowing(request: ShadowingScoreRequest): ShadowingScoreResponse =
        restClient.post()
            .uri("/answer/shadowing")
            .body(request)
            .retrieve()
            .body(ShadowingScoreResponse::class.java)
            ?: throw IllegalStateException("AI 컨테이너 /answer/shadowing 응답이 비어 있습니다")

    override fun scoreSelfTalk(request: SelfTalkScoreRequest): SelfTalkScoreResponse =
        restClient.post()
            .uri("/answer/selfTalk")
            .body(request)
            .retrieve()
            .body(SelfTalkScoreResponse::class.java)
            ?: throw IllegalStateException("AI 컨테이너 /answer/selfTalk 응답이 비어 있습니다")

    override fun aichat(request: AiChatRequest): AiChatResponse =
        restClient.post()
            .uri("/aichat")
            .body(request)
            .retrieve()
            .body(AiChatResponse::class.java)
            ?: throw IllegalStateException("AI 컨테이너 /aichat 응답이 비어 있습니다")

    override fun generateReport(request: ReportRequest): ReportResponse =
        restClient.post()
            .uri("/report")
            .body(request)
            .retrieve()
            .body(ReportResponse::class.java)
            ?: throw IllegalStateException("AI 컨테이너 /report 응답이 비어 있습니다")
}