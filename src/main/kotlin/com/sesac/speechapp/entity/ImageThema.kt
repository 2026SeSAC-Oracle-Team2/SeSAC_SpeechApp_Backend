package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** SPEECHAPP_CONTENT.IMAGE_THEMA — 이미지↔테마 매핑 (THEMA_KEY: TEST/HOSPITAL/CAFE) */
@Entity
@Table(name = "image_thema", schema = "speechapp_content")
class ImageThema(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "image_id", nullable = false)
    val imageId: Long,

    @Column(name = "thema_key", nullable = false, length = 30)
    val themaKey: String
) {
    constructor() : this(imageId = 0L, themaKey = "")
}
