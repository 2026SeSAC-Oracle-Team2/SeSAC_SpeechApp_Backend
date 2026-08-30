package com.sesac.speechapp

import com.sesac.speechapp.service.ObjectStorageService
import com.oracle.bmc.model.BmcException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * OCI Object Storage 실서명 라운드트립 수동 테스트.
 *
 * 기본 비활성화(일반 빌드에서 skip) — 실제 버킷에 대한 PUT/GET/DELETE를 검증한다.
 * 실행: ./gradlew test --tests "*ObjectStorageManualTest*" -Docitest=true
 * 인증은 VM의 ~/.oci/config [DEFAULT] 프로파일을 사용한다 (시크릿 커밋 없음).
 */
class ObjectStorageManualTest {

    @Test
    fun `put get delete roundtrip against real bucket`() {
        assumeTrue(System.getProperty("ocitest") == "true", "ocitest 시스템 프로퍼티가 있을 때만 실행")

        val service = ObjectStorageService(
            namespace = System.getenv("OCI_STORAGE_NAMESPACE") ?: "cn5brhz58dgr",
            bucketName = System.getenv("OCI_STORAGE_BUCKET") ?: "bucket-team545-userfiles",
            configPath = System.getProperty("user.home") + "/.oci/config",
            configProfile = "DEFAULT"
        )

        val key = "test/oci-roundtrip-${System.currentTimeMillis()}.txt"

        // PUT
        service.uploadObject(key, "hello-oci".toByteArray(), "text/plain")

        // GET
        val response = service.getObject(key)
        val body = response.inputStream.use { it.readBytes() }.decodeToString()
        assertEquals("hello-oci", body)

        // DELETE
        service.deleteObject(key)
        assertThrows<BmcException>("삭제된 오브젝트 재조회는 404가 나와야 한다") {
            service.getObject(key)
        }
    }
}