package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.ApiResponse
import com.sesac.speechapp.dto.VoiceUploadResponse
import com.sesac.speechapp.service.VoiceUploadService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/voice")
class VoiceController(
    private val voiceUploadService: VoiceUploadService
) {
    @PostMapping("/upload")
    fun uploadVoice(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("userId") userId: Long,
        @RequestParam("contentType") contentType: String,
        @RequestParam("sessionId", required = false) sessionId: Long?
    ): ResponseEntity<ApiResponse<VoiceUploadResponse>> {
        val result = voiceUploadService.uploadVoice(file, userId, sessionId, contentType)
        return ResponseEntity.ok(ApiResponse.success(
            VoiceUploadResponse(
                voiceRecordId = result.voiceRecordId,
                turnId = result.turnId,
                sessionId = result.sessionId,
                filePath = result.filePath
            )
        ))
    }
}
