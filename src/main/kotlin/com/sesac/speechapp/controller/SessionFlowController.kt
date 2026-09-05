package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.ApiResponse
import com.sesac.speechapp.dto.session.FinishData
import com.sesac.speechapp.dto.session.HintData
import com.sesac.speechapp.dto.session.ListenSubmitData
import com.sesac.speechapp.dto.session.ListenSubmitRequest
import com.sesac.speechapp.dto.session.SessionCreateData
import com.sesac.speechapp.dto.session.SessionReportData
import com.sesac.speechapp.dto.session.TalkData
import com.sesac.speechapp.dto.session.VoiceSubmitData
import com.sesac.speechapp.service.SessionFlowService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 세션 플로우 API (05a §3 — v1.6).
 *
 * dev용 임시 규칙: SecurityConfig에서 sessions/voice/content 경로 permitAll
 * (05a §0 주석 — 운영 전 JWT 전환은 05a §6.3 잔여).
 *
 * v1.6 (D-5, 2026-09-06):
 * - POST /today·/theme 신설 (2종 분기 — 03a §2). /v2는 하위호환 유지(클라 데모용·today 동작)
 * - GET /{sessionId}/report 신설 (세부 보고서 — 05a §8.3, userId 쿼리파라미터 소유 검증)
 * - finish 응답 = 간이 보고서 (talk/total null — 상세는 GET /report에서 수령)
 */
@RestController
@RequestMapping("/api/v1/sessions")
class SessionFlowController(
    private val sessionFlowService: SessionFlowService
) {

    /** 3.1 세션 생성 — 오늘의 학습 (테마 랜덤 + 무작위 출제) */
    @PostMapping("/today")
    fun createSessionToday(
        @RequestParam("userId") userId: Long
    ): ResponseEntity<ApiResponse<SessionCreateData>> {
        val data = sessionFlowService.createSessionToday(userId)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 3.1 세션 생성 — 테마별 학습 (thema 고정: TEST/HOSPITAL/CAFE, 이외 E0400) */
    @PostMapping("/theme")
    fun createSessionTheme(
        @RequestParam("userId") userId: Long,
        @RequestParam("thema") thema: String
    ): ResponseEntity<ApiResponse<SessionCreateData>> {
        val data = sessionFlowService.createSessionTheme(userId, thema)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 하위호환 — /v2는 today와 동일 동작 (클라 데모가 v2 사용 중 — 05a §3.1) */
    @PostMapping("/v2")
    fun createSessionLegacy(
        @RequestParam("userId") userId: Long
    ): ResponseEntity<ApiResponse<SessionCreateData>> {
        val data = sessionFlowService.createSessionToday(userId)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 3.2 LISTEN 답안 제출 — 백엔드 자체 채점 (즉시) */
    @PostMapping("/{sessionId}/turns/{turnId}/listen")
    fun submitListen(
        @PathVariable sessionId: Long,
        @PathVariable turnId: Long,
        @RequestBody request: ListenSubmitRequest
    ): ResponseEntity<ApiResponse<ListenSubmitData>> {
        val data = sessionFlowService.submitListen(sessionId, turnId, request.selected)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 3.2 NAMING 답안 제출 (음성 multipart) */
    @PostMapping("/{sessionId}/turns/{turnId}/naming", consumes = ["multipart/form-data"])
    fun submitNaming(
        @PathVariable sessionId: Long,
        @PathVariable turnId: Long,
        @RequestParam("userId") userId: Long,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ApiResponse<VoiceSubmitData>> {
        val data = sessionFlowService.submitNaming(sessionId, turnId, userId, file)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 3.2 SHADOWING 답안 제출 (음성 multipart) */
    @PostMapping("/{sessionId}/turns/{turnId}/shadowing", consumes = ["multipart/form-data"])
    fun submitShadowing(
        @PathVariable sessionId: Long,
        @PathVariable turnId: Long,
        @RequestParam("userId") userId: Long,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ApiResponse<VoiceSubmitData>> {
        val data = sessionFlowService.submitShadowing(sessionId, turnId, userId, file)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 3.2 SELF_TALK 답안 제출 (음성 multipart) */
    @PostMapping("/{sessionId}/turns/{turnId}/selftalk", consumes = ["multipart/form-data"])
    fun submitSelfTalk(
        @PathVariable sessionId: Long,
        @PathVariable turnId: Long,
        @RequestParam("userId") userId: Long,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ApiResponse<VoiceSubmitData>> {
        val data = sessionFlowService.submitSelfTalk(sessionId, turnId, userId, file)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 3.3 NAMING 힌트 요청 — 의미단서 → 조음단서 순 */
    @PostMapping("/{sessionId}/turns/{turnId}/hint")
    fun requestHint(
        @PathVariable sessionId: Long,
        @PathVariable turnId: Long
    ): ResponseEntity<ApiResponse<HintData>> {
        val data = sessionFlowService.getHint(sessionId, turnId)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 3.4 이야기 턴 — 첫 호출은 음성 없음(AI 개시), 이후 음성 multipart */
    @PostMapping("/{sessionId}/turns/talk")
    fun talk(
        @PathVariable sessionId: Long,
        @RequestParam("userId") userId: Long,
        @RequestParam(value = "file", required = false) file: MultipartFile?
    ): ResponseEntity<ApiResponse<TalkData>> {
        val data = sessionFlowService.talk(sessionId, userId, file)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 3.5 세션 종료 — 간이 보고서 응답 (상세 보고서는 백그라운드 생성, 05a §3.5 갱신) */
    @PostMapping("/{sessionId}/finish")
    fun finish(
        @PathVariable sessionId: Long,
        @RequestParam("userId") userId: Long
    ): ResponseEntity<ApiResponse<FinishData>> {
        val data = sessionFlowService.finishSession(sessionId, userId)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 8.3 세부 보고서 조회 — userId 쿼리파라미터 소유 검증 (permitAll 경로 방어, 05a §8.3) */
    @GetMapping("/{sessionId}/report")
    fun getSessionReport(
        @PathVariable sessionId: Long,
        @RequestParam("userId") userId: Long
    ): ResponseEntity<ApiResponse<SessionReportData>> {
        val data = sessionFlowService.getSessionReport(sessionId, userId)
        return ResponseEntity.ok(ApiResponse.success(data))
    }
}