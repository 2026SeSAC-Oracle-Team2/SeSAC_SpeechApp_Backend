package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * 음성 스트리밍 (05 문서 §4.7).
 *
 * - 스텁 모드: VOICE_RECORD.voice_file_path가 "classpath:tts_samples/..." → 샘플 mp3 서빙.
 * - 실모드: OCI 원본 프록시 스트리밍 (기존 getObject 방식 — TODO는 AI 컨테이너 연동 시).
 */
@RestController
class VoiceStreamController {

    private val logger = LoggerFactory.getLogger(VoiceStreamController::class.java)

    @GetMapping("/api/v1/voice/{voiceRecordId}")
    fun streamVoice(@PathVariable voiceRecordId: Long): ResponseEntity<*> {
        // 데모 스텁: voiceRecordId → 샘플 mp3 스트리밍.
        // 실모드 전환 시: VoiceRecordRepository에서 파일 경로 조회 → OCI 스트리밍.
        val sample = resolveSample(voiceRecordId)
        val resource = ClassPathResource("tts_samples/$sample")
        if (!resource.exists()) {
            return ResponseEntity.status(404).body(
                ApiResponse.error<Any>("E0404", "음성 파일을 찾을 수 없습니다.")
            )
        }
        val bytes = resource.inputStream.readBytes()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)))
            .body(bytes)
    }

    /** 스텁 매핑: 턴 타입별 샘플. 실서비스에서는 DB voice_file_path 기반. */
    private fun resolveSample(voiceRecordId: Long): String = when (voiceRecordId % 4) {
        0L -> "tts_listen.mp3"
        1L -> "tts_naming.mp3"
        2L -> "tts_shadowing.mp3"
        else -> "tts_hello.mp3"
    }
}