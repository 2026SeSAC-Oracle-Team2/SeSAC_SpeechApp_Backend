package com.sesac.speechapp.service

import com.oracle.bmc.objectstorage.ObjectStorage
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest
import com.oracle.bmc.objectstorage.responses.GetObjectResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

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
    private val configProfile: String,

    @Value("\${oci.objectstorage.region:ap-chuncheon-1}")
    private val region: String
) {
    private val logger = LoggerFactory.getLogger(ObjectStorageService::class.java)

    /** ObjectStorageClient는 스레드세이프 — 첫 사용 시 1회만 생성한다. */
    private val client: ObjectStorage by lazy {
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
     * Pre-Authenticated Request (PAR) 생성
     *
     * 외부 컨테이너(문제 생성/채점)에 음성 파일을 전달할 때 사용.
     * PAR는 시간 제한이 있는 일회용/기간제 URL이므로 안전하게 공유 가능.
     *
     * @param objectKey OCI 오브젝트 키
     * @param expirationMinutes 유효 시간(분). 기본 10분
     * @return 외부에서 직접 접근 가능한 Pre-Authenticated URL
     *
     * application.yml에 oci.objectstorage.region 필수.
     */
    fun createPreauthenticatedUrl(objectKey: String, expirationMinutes: Long = 10): String {
        val expireInstant = Instant.now().plusSeconds(expirationMinutes * 60)
        val expireOffset = OffsetDateTime.ofInstant(expireInstant, ZoneId.systemDefault())

        val details = CreatePreauthenticatedRequestDetails.builder()
            .name("par-${System.currentTimeMillis()}-$objectKey")
            .objectName(objectKey)
            .accessType(CreatePreauthenticatedRequestDetails.AccessType.ObjectRead)
            .timeExpires(expireOffset)
            .build()

        val request = CreatePreauthenticatedRequestRequest.builder()
            .namespaceName(namespace)
            .bucketName(bucketName)
            .createPreauthenticatedRequestDetails(details)
            .build()

        val response = client.createPreauthenticatedRequest(request)
        val accessUri = response.preauthenticatedRequest.accessUri

        // accessUri는 상대 경로일 수 있으므로, OCI 기본 엔드포인트와 조합
        val parUrl = "https://objectstorage.${region}.oraclecloud.com${accessUri}"

        logger.info("OCI PAR 생성 완료: key={}, expiresIn={}min, urlPrefix={}",
            objectKey, expirationMinutes, parUrl.take(80) + "...")
        return parUrl
    }

    /**
     * Signed URL 방식 (PAR 대안)
     *
     * PAR 생성 API 호출 없이 클라이언트 측에서 URL에 서명을 추가.
     * 다만 oci-java-sdk에서 직접적인 Signed URL 생성은 복잡하므로,
     * 대부분의 경우 PAR 방식을 권장.
     *
     * NOTE: 이 프로젝트에서는 PAR 방식을 기본으로 사용.
     */
    fun createSignedUrl(objectKey: String, expirationMinutes: Long = 10): String {
        // PAR 방식과 동일하게 동작하되, 필요 시 AWS-style signed URL 로직 교체 가능
        return createPreauthenticatedUrl(objectKey, expirationMinutes)
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

    /** 음성 파일 키 생성: {userUuid}/voice/{sessionId}/{turnNumber}_{timestamp}.m4a */
    fun buildVoiceKey(userUuid: String, sessionId: Long, turnNumber: Int, ext: String = "m4a"): String =
        "$userUuid/voice/$sessionId/${turnNumber}_${System.currentTimeMillis()}.${ext.lowercase()}"

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
