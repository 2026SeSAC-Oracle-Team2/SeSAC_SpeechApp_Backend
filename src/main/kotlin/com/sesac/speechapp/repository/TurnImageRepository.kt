package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.TurnImage
import com.sesac.speechapp.entity.TurnImageId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TurnImageRepository : JpaRepository<TurnImage, TurnImageId> {
    fun findByTurnIdOrderByImageOrderAsc(turnId: Long): List<TurnImage>
}
