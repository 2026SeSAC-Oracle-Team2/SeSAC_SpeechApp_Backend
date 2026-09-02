package com.sesac.speechapp.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.oracle.bmc.objectstorage.requests.GetObjectRequest
import java.io.ByteArrayInputStream

/**
 * 콘텐츠 이미지(IMAGE_RESOURCE) 전용 OCI 접근.
 *
 * ⚠️ 버킷 분리 규약 (2026-09-03 실측):
 *  - 콘텐츠 이미지 = bucket-team545-problemfiles, 키 = "images/{id}/{file}" (admin_page 관리)
 *  - 유저 음성/프로필 = bucket-team545-userfiles (ObjectStorageService 사용)
 *
 * ObjectStorageService와 동일한 인증 설정을 쓰되 버킷만 다르므로 별도 서비스로 분리.
 */
@Service
class ContentImageStorageService(
    @Value("\${oci.objectstorage.namespace}")
    private val namespace: String,

    @Value("\${oci.objectstorage.content-bucket:bucket-team545-problemfiles}")
    private val bucketName: String,

    @Value("\${oci.objectstorage.config-path}")
    private val configPath: String,

    @Value("\${oci.objectstorage.config-profile}")
    private val configProfile: String
) {
    private val logger = LoggerFactory.getLogger(ContentImageStorageService::class.java)

    private val client: com.oracle.bmc.objectstorage.ObjectStorage by lazy {
        val expandedPath = if (configPath.startsWith("~")) {
            System.getProperty("user.home") + configPath.substring(1)
        } else {
            configPath
        }
        val provider = com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider(expandedPath, configProfile)
        com.oracle.bmc.objectstorage.ObjectStorageClient(provider)
    }

    fun getObject(objectKey: String): com.oracle.bmc.objectstorage.responses.GetObjectResponse {
        val request = GetObjectRequest.builder()
            .namespaceName(namespace)
            .bucketName(bucketName)
            .objectName(objectKey)
            .build()
        return client.getObject(request)
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
        logger.info("OCI 콘텐츠 이미지 업로드 완료: bucket={}, key={} ({} bytes)", bucketName, objectKey, bytes.size)
    }
}