package com.sesac.speechapp.ai

import com.sesac.speechapp.dto.aicontainer.AiChatRequest
import com.sesac.speechapp.dto.aicontainer.AiChatResponse
import com.sesac.speechapp.dto.aicontainer.CreateSessionRequest
import com.sesac.speechapp.dto.aicontainer.CreateSessionResponse
import com.sesac.speechapp.dto.aicontainer.NamingScoreRequest
import com.sesac.speechapp.dto.aicontainer.NamingScoreResponse
import com.sesac.speechapp.dto.aicontainer.ProblemsReportRequest
import com.sesac.speechapp.dto.aicontainer.ProblemsReportResponse
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreRequest
import com.sesac.speechapp.dto.aicontainer.SelfTalkScoreResponse
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreRequest
import com.sesac.speechapp.dto.aicontainer.ShadowingScoreResponse
import com.sesac.speechapp.dto.aicontainer.TotalReportRequest
import com.sesac.speechapp.dto.aicontainer.TotalReportResponse

/**
 * BE ↔ AI 컨테이너 계약 클라이언트 (03_AI_Container_Contract.md).
 *
 * - 구현체 2종: StubAiContainerClient (데모/로컬, 자연 지연+그럴듯한 응답)
 *              RealAiContainerClient (실제 FastAPI 호출 — 컨테이너 배포 후 활성화)
 * - 전환: application.yml `ai.container.mode: stub|real` (@ConditionalOnProperty)
 *
 * v1.9 (D-5, 2026-09-06):
 * - createSession → createSessionToday / createSessionTheme 2종 분리 (03a §2 —
 *   엔드포인트 분기: today=무작위 출제 / theme=기획 시나리오 플로우. 요청·응답 필드 공통)
 * - generateReport → generateProblems / generateTotal 2종 분리 (03a §7 —
 *   리포트 2단계: problems=간이(AQ+4지표) / total=상세(talk/total+userMemory).
 *   구 ReportRequest/ReportResponse 폐지)
 */
interface AiContainerClient {
    /** §2 세션 문제 일괄 생성 — today (오늘의 학습: 테마 무작위+무작위 출제) */
    fun createSessionToday(request: CreateSessionRequest): CreateSessionResponse

    /** §2 세션 문제 일괄 생성 — theme (테마별 학습: 기획 시나리오 플로우) */
    fun createSessionTheme(request: CreateSessionRequest): CreateSessionResponse

    /** §4 이름대기 채점 */
    fun scoreNaming(request: NamingScoreRequest): NamingScoreResponse

    /** §5 따라말하기 채점 */
    fun scoreShadowing(request: ShadowingScoreRequest): ShadowingScoreResponse

    /** §6 자발화 채점 */
    fun scoreSelfTalk(request: SelfTalkScoreRequest): SelfTalkScoreResponse

    /** §8 이야기 턴 (한 턴씩 생성) */
    fun aichat(request: AiChatRequest): AiChatResponse

    /** §7.1 간이 보고서 — 문제 8턴 종료 시점 (AQ + 4지표 피드백) */
    fun generateProblems(request: ProblemsReportRequest): ProblemsReportResponse

    /** §7.2 상세 보고서 — 세션 종료/학습 완료 판정 시점 (talk/total 피드백 + userMemory 갱신) */
    fun generateTotal(request: TotalReportRequest): TotalReportResponse
}