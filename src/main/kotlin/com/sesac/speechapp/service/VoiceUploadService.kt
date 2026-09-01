package com.sesac.speechapp.service

import com.sesac.speechapp.entity.Turn
import com.sesac.speechapp.entity.VoiceRecord
import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.VoiceRecordRepository
import com.sesac.speechapp.repository.SessionRepository
import com.sesac.speechapp.repository.AppUserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

@Service
class VoiceUploadService(
    private val objectStorageService: ObjectStorageService,
    private val sessionService: SessionService,
    private val turnRepository: TurnRepository,
    private val voiceRecordRepository: VoiceRecordRepository,
    private val sessionRepository: SessionRepository,
    private val appUserRepository: AppUserRepository
) {
    @Transactional
    fun uploadVoice(
        file: MultipartFile,
        userId: Long,
        sessionId: Long?,
        contentType: String
    ): VoiceUploadResult {
        // 1. 세션 조회 또는 생성
        val session = sessionId?.let { sessionRepository.findById(it).orElseThrow() }
            ?: sessionService.createSession(userId, null)

        // 2. 턴 생성
        val turnNumber = turnRepository.countBySessionId(session.id!!).toInt() + 1
        val turn = Turn(
            sessionId = session.id,
            turnNumber = turnNumber,
            contentType = contentType
        )
        turnRepository.save(turn)

        // 3. OCI 업로드
        val user = appUserRepository.findById(userId).orElseThrow()
        val objectKey = buildVoiceKey(user.uuid, session.id, turn.id!!, "USER")
        objectStorageService.uploadObject(objectKey, file.bytes, "audio/mp4")

        // 4. VOICE_RECORD INSERT
        val voiceRecord = VoiceRecord(
            userId = userId,
            sessionId = session.id,
            turnId = turn.id,
            speaker = "USER",
            voiceFilePath = objectKey,
            durationSeconds = null // TODO: 클라이언트에서 duration 전달 또는 서버에서 파싱
        )
        voiceRecordRepository.save(voiceRecord)

        return VoiceUploadResult(
            voiceRecordId = voiceRecord.id!!,
            turnId = turn.id,
            sessionId = session.id,
            filePath = objectKey
        )
    }

    private fun buildVoiceKey(userUuid: String, sessionId: Long, turnId: Long, speaker: String): String {
        val ext = if (speaker == "AI") "mp3" else "m4a"
        val speakerSuffix = if (speaker == "AI") "_ai" else "_user"
        return "containers/llm/${userUuid}/${sessionId}/${turnId}${speakerSuffix}.${ext}"
    }
}

data class VoiceUploadResult(
    val voiceRecordId: Long,
    val turnId: Long,
    val sessionId: Long,
    val filePath: String
)
