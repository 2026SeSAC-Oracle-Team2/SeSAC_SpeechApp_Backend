package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.ImageThema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ImageThemaRepository : JpaRepository<ImageThema, Long> {
    fun findByThemaKey(themaKey: String): List<ImageThema>
}
