package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

@Entity
@Table(name = "turn_image", schema = "speechapp_user")
@IdClass(TurnImageId::class)
class TurnImage(

    @Id
    @Column(name = "turn_id", nullable = false)
    val turnId: Long = 0L,

    @Id
    @Column(name = "image_id", nullable = false)
    val imageId: Long = 0L,

    @Column(name = "image_order", nullable = false)
    val imageOrder: Int? = null
) : Serializable {
    constructor() : this(0L, 0L, null)
}

class TurnImageId(
    val turnId: Long = 0L,
    val imageId: Long = 0L
) : Serializable
