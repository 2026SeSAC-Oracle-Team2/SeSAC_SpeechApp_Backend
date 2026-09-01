package com.sesac.speechapp.dto

data class VoiceUploadRequest(
    val userId: Long,
    val contentType: String,
    val sessionId: Long? = null
)

data class VoiceUploadResponse(
    val voiceRecordId: Long,
    val turnId: Long,
    val sessionId: Long,
    val filePath: String
)
