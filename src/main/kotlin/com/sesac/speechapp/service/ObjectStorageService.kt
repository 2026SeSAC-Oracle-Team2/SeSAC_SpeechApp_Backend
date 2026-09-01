package com.sesac.speechapp.service

import com.oracle.bmc.objectstorage.responses.GetObjectResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream

/**
 * OCI Object Storage 연동 서비스 (API Key 인증: ~/.oci/config [DEFAULT]).
 *
 * - DB에는 오브젝트 "키"만 저장하고 버킷명/네임스페이스는 이 서비스/yml이 관리한다.
 * - 버킷은 비공개이며 모든 클라이언트 읽기는 백엔드 엔드포인트를 통해 스트리밍된다.
 * - 키 규약: {userUUID}/profile.{ext} (사용자 단위 격리 네임스페이스)
 */
@Service
class ObjectStorageService(
    @Value("\${oci.objectstorage.namespace}")
    private val namespace: String,

    @Value("\${oci.objectstorage.bucket}")
    private val bucketName: String,

    @Value("\${oci.objectstorage.config-path}")
    private val configPath: String,

    @Value("\${oci.objectstorage.config-profile}")
    private val configProfile: String
) {
    private val logger = LoggerFactory.getLogger(ObjectStorageService::class.java)

    /** ObjectStorageClient는 스레드세이프 — 첫 사용 시 1회만 생성한다. */
    private val client: com.oracle.bmc.objectstorage.ObjectStorage by lazy {
        // ConfigFileAuthenticationDetailsProvider는 ~ 를 확장하지 않으므로 직접 확장
        val expandedPath = if (configPath.startsWith("~")) {
            System.getProperty("user.home") + configPath.substring(1)
        } else {
            configPath
        }
        val provider = com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider(expandedPath, configProfile)
        com.oracle.bmc.objectstorage.ObjectStorageClient(provider)
    }

    fun uploadObject(objectKey: String, bytes: ByteArray, contentType: String) {
        val request = com.oracle.bmc.objectstorage.requests.PutObjectRequest.builder()
            .namespaceName(namespace)
            .bucketName(bucketName)
            .objectName(objectKey)
            .contentType(contentType)
            .contentLength(bytes.size.toLong())
            .putObjectBody(ByteArrayInputStream(bytes))
            .build()
        client.putObject(request)
        logger.info("OCI Object 업로드 완료: bucket={}, key={} ({} bytes)", bucketName, objectKey, bytes.size)
    }

    fun getObject(objectKey: String): GetObjectResponse {
        val request = com.oracle.bmc.objectstorage.requests.GetObjectRequest.builder()
            .namespaceName(namespace)
            .bucketName(bucketName)
            .objectName(objectKey)
            .build()
        return client.getObject(request)
    }

    fun deleteObject(objectKey: String) {
        val request = com.oracle.bmc.objectstorage.requests.DeleteObjectRequest.builder()
            .namespaceName(namespace)
            .bucketName(bucketName)
            .objectName(objectKey)
            .build()
        client.deleteObject(request)
        logger.info("OCI Object 삭제: bucket={}, key={}", bucketName, objectKey)
    }

    /**
     * 프로필 이미지 표준 키 생성: {userUUID}/profile.{ext}
     * 업로드 시 기존 확장자와 무관하게 덮어쓰기되므로 사용자당 1개 오브젝트만 유지된다.
     */
    fun buildVoiceKey(userUuid: String, sessionId: Long, turnId: Long, speaker: String): String {
        val ext = if (speaker == "AI") "mp3" else "m4a"
        val speakerSuffix = if (speaker == "AI") "_ai" else "_user"
        return "${userUuid}/voice/${sessionId}/${turnId}${speakerSuffix}.${ext}"
    }

    fun buildProfileKey(userUuid: String, extension: String): String =
        "$userUuid/profile.${extension.lowercase()}"

    companion object {
        /** 지원 확장자 → Content-Type 매핑 (jpg/png/webp) */
        val SUPPORTED_IMAGE_TYPES: Map<String, String> = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp"
        )

        /** 파일 확장자 추출 (소문자, 매핑에 없으면 null) */
        fun extractImageExtension(filename: String?): String? {
            val ext = filename?.substringAfterLast('.', "")?.lowercase() ?: return null
            return if (ext in SUPPORTED_IMAGE_TYPES) ext else null
        }
    }
}