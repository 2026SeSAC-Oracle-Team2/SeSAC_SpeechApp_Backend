package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * USER_REPRESENTATIVE_SCORES — 대표점수 캐시 (04 v2.6 §4.2.2, D-1 LIVE 신설).
 * 대표점수 5종(AQ+4지표) 통합 저장. 대시보드 방사형 그래프 + /sessions userAQ 전달용.
 *
 * ⚠️ user_id는 PK이자 FK→user_profile.user_id (1:1) — IDENTITY/SEQUENCE 금지
 *    (@GeneratedValue 없음, MapsId로 user_profile과 공유).
 * 갱신 지점 2곳 고정: ① 가입 설문 접수(USER_AQ 초기 세팅 30/70/90)
 * ② /report/problems 수신(AQ+4지표 ADR-009 식 재계산 UPDATE) — 서비스 로직은 D-5 대상.
 * NUMBER(5,2) 지표는 BigDecimal 매핑 (Double→BINARY_DOUBLE 함정 회피, TURN.score 선례).
 */
@Entity
@Table(name = "user_representative_scores", schema = "speechapp_user")
class UserRepresentativeScore(

    @Id
    @Column(name = "user_id")
    val userId: Long = 0L,

    // 유저 대표 AQ — 가입 설문 환산값(30/70/90) 초기 세팅 → /report/problems 수신 시점마다 재계산 UPDATE.
    // null = 설문 미응답 (설문 재노출 판별 기준). NUMBER(3) CHECK 0~100.
    @Column(name = "user_aq", precision = 3, scale = 0)
    var userAq: Int? = null,

    // 대표 지표점수 — LISTEN_TEXT/LISTEN_PICTURE 통합 (지표상 동일 LISTEN). NUMBER(5,2) nullable
    @Column(name = "user_score_listen", precision = 5, scale = 2)
    var userScoreListen: BigDecimal? = null,

    @Column(name = "user_score_naming", precision = 5, scale = 2)
    var userScoreNaming: BigDecimal? = null,

    @Column(name = "user_score_shadowing", precision = 5, scale = 2)
    var userScoreShadowing: BigDecimal? = null,

    @Column(name = "user_score_self_talk", precision = 5, scale = 2)
    var userScoreSelfTalk: BigDecimal? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null
) {
    constructor() : this(userId = 0L)
}