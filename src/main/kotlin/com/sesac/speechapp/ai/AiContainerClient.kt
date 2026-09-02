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

/**
 * BE ↔ AI 컨테이너 계약 클라이언트 (03_AI_Container_Contract.md).
 *
 * - 구현체 2종: StubAiContainerClient (데모/로컬, 자연 지연+그럴듯한 응답)
 *              RealAiContainerClient (실제 FastAPI 호출 — 컨테이너 배포 후 활성화)
 * - 전환: application.yml `ai.container.mode: stub|real` (@ConditionalOnProperty)
 */
interface AiContainerClient {
    /** §2 세션 문제 일괄 생성 (8문제, 무작위 순서) */
    fun createSession(request: CreateSessionRequest): CreateSessionResponse

    /** §4 이름대기 채점 */
    fun scoreNaming(request: NamingScoreRequest): NamingScoreResponse

    /** §5 따라말하기 채점 */
    fun scoreShadowing(request: ShadowingScoreRequest): ShadowingScoreResponse

    /** §6 자발화 채점 */
    fun scoreSelfTalk(request: SelfTalkScoreRequest): SelfTalkScoreResponse

    /** §8 이야기 턴 (한 턴씩 생성) */
    fun aichat(request: AiChatRequest): AiChatResponse

    /** §9 세션 보고서 */
    fun generateReport(request: ReportRequest): ReportResponse
}