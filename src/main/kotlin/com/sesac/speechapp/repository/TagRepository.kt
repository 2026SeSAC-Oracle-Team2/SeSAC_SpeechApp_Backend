package com.sesac.speechapp.repository

import com.sesac.speechapp.entity.Tag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * TAGS — 관심사 태그 마스터 (04 v2.6 §4.2.1, D-3 [2]).
 * 15종 시드는 D-1 마이그레이션에서 이미 LIVE 적재 완료.
 * GET /api/v1/users/me/tags → { tags: [ {tagId, tag}, ... ] } — order by tagId.
 */
@Repository
interface TagRepository : JpaRepository<Tag, Long> {
    fun findAllByOrderByTagIdAsc(): List<Tag>
}