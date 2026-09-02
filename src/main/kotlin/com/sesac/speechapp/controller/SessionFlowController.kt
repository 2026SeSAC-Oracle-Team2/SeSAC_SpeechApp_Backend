package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.ApiResponse
import com.sesac.speechapp.dto.session.FinishData
import com.sesac.speechapp.dto.session.HintData
import com.sesac.speechapp.dto.session.ListenSubmitData
import com.sesac.speechapp.dto.session.ListenSubmitRequest
import com.sesac.speechapp.dto.session.SessionCreateData
import com.sesac.speechapp.dto.session.TalkData
import com.sesac.speechapp.dto.session.VoiceSubmitData
import com.sesac.speechapp.service.SessionFlowService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * "오늘의 학습" 데모 세션 플로우 API (05 문서 §4).
 *
 * dev용 임시 규칙: SecurityConfig에서 sessions/voice 경로 permitAll.
 */
@RestController
@RequestMapping("/api/v1/sessions")
class SessionFlowController(
    private val sessionFlowService: SessionFlowService
) {

    /** 4.1 세션 생성 — "오늘의 학습" 시작 (8문제 일괄 생성, 로딩 대기) */
    @PostMapping("/v2")
    fun createSession(
        @RequestParam("userId") userId: Long
    ): ResponseEntity<ApiResponse<SessionCreateData>> {
        val data = sessionFlowService.createSession(userId)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 4.3 LISTEN 답안 제출 — 백엔드 자체 채점 (즉시) */
    @PostMapping("/{sessionId}/turns/{turnId}/listen")
    fun submitListen(
        @PathVariable sessionId: Long,
        @PathVariable turnId: Long,
        @RequestBody request: ListenSubmitRequest
    ): ResponseEntity<ApiResponse<ListenSubmitData>> {
        val data = sessionFlowService.submitListen(sessionId, turnId, request.selected)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 4.3 NAMING 답안 제출 (음성 multipart) */
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

    /** 4.3 SHADOWING 답안 제출 (음성 multipart) */
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

    /** 4.3 SELF_TALK 답안 제출 (음성 multipart) */
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

    /** 4.4 NAMING 힌트 요청 — 의미단서 → 조음단서 순 */
    @PostMapping("/{sessionId}/turns/{turnId}/hint")
    fun requestHint(
        @PathVariable sessionId: Long,
        @PathVariable turnId: Long
    ): ResponseEntity<ApiResponse<HintData>> {
        val data = sessionFlowService.getHint(sessionId, turnId)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 4.5 이야기 턴 — 첫 호출은 음성 없음(AI 개시), 이후 음성 multipart */
    @PostMapping("/{sessionId}/turns/talk")
    fun talk(
        @PathVariable sessionId: Long,
        @RequestParam("userId") userId: Long,
        @RequestParam(value = "file", required = false) file: MultipartFile?
    ): ResponseEntity<ApiResponse<TalkData>> {
        val data = sessionFlowService.talk(sessionId, userId, file)
        return ResponseEntity.ok(ApiResponse.success(data))
    }

    /** 4.6 세션 종료 + 리포트 — 동기 응답 (로딩 대기) */
    @PostMapping("/{sessionId}/finish")
    fun finish(
        @PathVariable sessionId: Long,
        @RequestParam("userId") userId: Long
    ): ResponseEntity<ApiResponse<FinishData>> {
        val data = sessionFlowService.finishSession(sessionId, userId)
        return ResponseEntity.ok(ApiResponse.success(data))
    }
}