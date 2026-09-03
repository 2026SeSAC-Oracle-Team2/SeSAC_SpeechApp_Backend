package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.TurnImage
import com.sesac.speechapp.entity.TurnImageId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TurnImageRepository : JpaRepository<TurnImage, TurnImageId> {
    fun findByTurnIdOrderByImageOrderAsc(turnId: Long): List<TurnImage>

    /** 회원탈퇴용 벌크 삭제 (FK 역순 하드딜리트 — B-1) */
    @Modifying
    @Query("DELETE FROM TurnImage ti WHERE ti.turnId IN :turnIds")
    fun deleteByTurnIds(@Param("turnIds") turnIds: Collection<Long>)
}
