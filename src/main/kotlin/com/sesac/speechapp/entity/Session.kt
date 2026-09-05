package com.sesac.speechapp.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Lob
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "learning_session", schema = "speechapp_user")
class Session(

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sessionSeq")
    @SequenceGenerator(name = "sessionSeq", sequenceName = "session_seq", schema = "speechapp_user", allocationSize = 1)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "theme", length = 30)
    val theme: String? = null,

    // D-2 (04 v2.6 §4.4): 세션 표시명 — 학습 기록 카드용.
    // today=`오늘의 학습 - {테마명}` / theme=기획 시나리오명. D-1에서 기존 3행 백필 완료.
    @Column(name = "session_name", length = 100)
    val sessionName: String? = null,

    // D-2 (04 v2.6 §4.4): 세션 종류 — today(오늘의 학습: 무작위 출제) / theme(테마별: 시나리오 플로우).
    // 컨테이너 엔드포인트 분기(/sessions/today vs theme)와 매핑. CHECK IN ('today','theme').
    @Column(name = "type", length = 20)
    val type: String? = null,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "IN_PROGRESS",

    // P2-36 (ADR-008): 리포트 — 세션 AQ (100점 만점 정수, 리포트 생성 시점에 적재, 전까지 NULL)
    @Column(name = "aq")
    var aq: Int? = null,

    @Lob
    @Column(name = "listen_feedback")
    var listenFeedback: String? = null,

    @Lob
    @Column(name = "naming_feedback")
    var namingFeedback: String? = null,

    @Lob
    @Column(name = "shadowing_feedback")
    var shadowingFeedback: String? = null,

    @Lob
    @Column(name = "self_talk_feedback")
    var selfTalkFeedback: String? = null,

    @Lob
    @Column(name = "talk_feedback")
    var talkFeedback: String? = null,

    @Lob
    @Column(name = "total_feedback")
    var totalFeedback: String? = null,

    // D-2 (04 v2.6 §4.4): 클라가 상세 보고서를 조회한 시점. null=미조회 —
    // 앱 내 알림함/네비 버블 판별용 (조회 시각 저장으로 조회 여부+시점 동시 커버).
    @Column(name = "report_viewed_at")
    var reportViewedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    constructor() : this(userId = 0L)
}
