package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.ImageResource
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ImageResourceRepository : JpaRepository<ImageResource, Long> {
    fun findByImageIdIn(imageIds: Collection<Long>): List<ImageResource>
}
