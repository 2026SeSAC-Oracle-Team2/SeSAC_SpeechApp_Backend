package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.ApiResponse
import com.sesac.speechapp.entity.VoiceRecord
import com.sesac.speechapp.repository.VoiceRecordRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.time.Duration

/**
 * 음성 스트리밍 (05 문서 §4.7).
 *
 * voice_file_path 값 3분기 (E2E-복구-2):
 * - "classpath:" 접두어 → 기존 classpath 샘플 서빙 (하위호환 — 구 세션 데이터)
 * - "stub/" 접두어     → 기존 resolveSample(%4) 스텁 서빙 (stub 모드 하위호환)
 * - 그 외(공유폴더 상대경로, real 모드) → ai.container.shared-audio-root 결합해
 *   실물 mp3 스트리밍 (컨테이너가 생성한 AI TTS — 03a §8)
 * - 조회 실패/파일 부재 → 404 E0404 (기존 ApiResponse 형식 유지)
 */
@RestController
class VoiceStreamController(
    private val voiceRecordRepository: VoiceRecordRepository,
    @Value("\${ai.container.shared-audio-root:/home/opc/containers/llm}")
    private val sharedAudioRoot: String
) {

    private val logger = LoggerFactory.getLogger(VoiceStreamController::class.java)

    @GetMapping("/api/v1/voice/{voiceRecordId}")
    fun streamVoice(@PathVariable voiceRecordId: Long): ResponseEntity<*> {
        val record: VoiceRecord = voiceRecordRepository.findById(voiceRecordId).orElse(null)
            ?: return notFound("음성 기록을 찾을 수 없습니다: $voiceRecordId")

        val bytes: ByteArray = when {
            record.voiceFilePath.startsWith("classpath:") -> {
                val resource = ClassPathResource(record.voiceFilePath.removePrefix("classpath:"))
                if (!resource.exists()) return notFound("음성 파일을 찾을 수 없습니다: ${record.voiceFilePath}")
                resource.inputStream.readBytes()
            }
            record.voiceFilePath.startsWith("stub/") -> {
                val resource = ClassPathResource("tts_samples/${resolveSample(voiceRecordId)}")
                if (!resource.exists()) return notFound("음성 파일을 찾을 수 없습니다: stub sample")
                resource.inputStream.readBytes()
            }
            else -> {
                // 공유폴더 상대경로 (real 모드 — 컨테이너 생성 실물 mp3)
                val file = File(sharedAudioRoot, record.voiceFilePath)
                logger.info("TTS 스트리밍: {}", file.path)
                if (!file.exists()) return notFound("음성 파일을 찾을 수 없습니다: ${record.voiceFilePath}")
                file.readBytes()
            }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)))
            .body(bytes)
    }

    private fun notFound(message: String): ResponseEntity<*> =
        ResponseEntity.status(404).body(ApiResponse.error<Any>("E0404", message))

    /** 스텁 매핑: 턴 타입별 샘플 ("stub/" 경로 전용 — 구 스텁 규약 유지). */
    private fun resolveSample(voiceRecordId: Long): String = when (voiceRecordId % 4) {
        0L -> "tts_listen.mp3"
        1L -> "tts_naming.mp3"
        2L -> "tts_shadowing.mp3"
        else -> "tts_hello.mp3"
    }
}