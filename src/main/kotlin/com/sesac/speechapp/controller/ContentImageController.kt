package com.sesac.speechapp.controller

import com.sesac.speechapp.dto.ApiResponse
import com.sesac.speechapp.entity.ImageResource
import com.sesac.speechapp.repository.ImageResourceRepository
import com.sesac.speechapp.service.ContentImageStorageService
import com.sesac.speechapp.service.ObjectStorageService
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * 콘텐츠 이미지 프록시 스트리밍 (05 문서 §4.8).
 *
 * 버킷은 비공개이므로 백엔드가 IMAGE_RESOURCE.image_file_path(OCI 키)로
 * getObject 후 바이트 스트리밍한다. 세션 생성 응답의 imageUrl
 * (/api/v1/content/images/{id}/file)이 이 컨트롤러를 가리킨다.
 *
 * ⚠️ 키 규약: DB의 image_file_path는 상대경로("90/90.jpg")이고 실제 버킷 키는
 * admin_page 규약과 동일하게 "images/" 프리픽스가 붙는다("images/90/90.jpg").
 * 레거시 호환: 이미 프리픽스로 시작하면 중복 부착하지 않음.
 *
 * dev용 임시 규칙: SecurityConfig에서 이 경로 permitAll.
 */
@RestController
@RequestMapping("/api/v1/content")
class ContentImageController(
    private val imageResourceRepository: ImageResourceRepository,
    private val contentImageStorage: ContentImageStorageService
) {
    private val logger = LoggerFactory.getLogger(ContentImageController::class.java)

    @GetMapping("/images/{imageId}/file")
    fun streamImageFile(@PathVariable imageId: Long): ResponseEntity<*> {
        val image: ImageResource = imageResourceRepository.findById(imageId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error<Any>("E0404", "이미지를 찾을 수 없습니다. (imageId=$imageId)")
            )

        val objectKey = buildContentImageKey(image.imageFilePath)
        return try {
            val response = contentImageStorage.getObject(objectKey)
            val bytes = response.inputStream.use { it.readBytes() }
            val contentType = ObjectStorageService.SUPPORTED_IMAGE_TYPES.entries
                .firstOrNull { objectKey.endsWith(".${it.key}") }?.value
                ?: MediaType.APPLICATION_OCTET_STREAM_VALUE

            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(300)))
                .body(ByteArrayResource(bytes))
        } catch (e: Exception) {
            logger.warn("이미지 스트리밍 실패: imageId={}, key={}, cause={}", imageId, objectKey, e.message)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<Any>("E0500", "이미지 파일을 가져올 수 없습니다.")
            )
        }
    }

    /** DB 상대경로 → 버킷 객체 키 ("90/90.jpg" → "images/90/90.jpg", admin_page build_key 동일 규약) */
    private fun buildContentImageKey(relPath: String): String {
        val path = relPath.trimStart('/')
        return if (path.startsWith(CONTENT_IMAGE_KEY_PREFIX) || path.startsWith("tmp/")) {
            path
        } else {
            CONTENT_IMAGE_KEY_PREFIX + path
        }
    }

    companion object {
        private const val CONTENT_IMAGE_KEY_PREFIX = "images/"
    }
}