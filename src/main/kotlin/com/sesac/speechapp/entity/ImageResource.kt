package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

/**
 * SPEECHAPP_CONTENT.IMAGE_RESOURCE — 데모에서 읽기 전용으로 사용.
 * - P2-35 (ADR-004): IMAGE_HINT_PATH 폐지 → SEMANTIC_CUE / ARTICULATORY_CUE DB 컬럼.
 * - 태그 JSON은 셀/클라에 노출하지 않고 컨테이너 채점 요청에만 전달.
 */
@Entity
@Table(name = "image_resource", schema = "speechapp_content")
class ImageResource(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val imageId: Long? = null,

    @Column(name = "image_name", nullable = false, length = 200)
    val imageName: String,

    @Column(name = "image_file_path", nullable = false, length = 500)
    val imageFilePath: String,

    @Column(name = "image_tag_path", length = 500)
    val imageTagPath: String? = null,

    // NAMING 힌트 1순위 (의미단서)
    @Column(name = "semantic_cue", length = 500)
    val semanticCue: String? = null,

    // NAMING 힌트 2순위 (조음단서)
    @Column(name = "articulatory_cue", length = 500)
    val articulatoryCue: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null
) {
    constructor() : this(imageName = "", imageFilePath = "")
}
