package com.sesac.speechapp.service

import com.sesac.speechapp.repository.TurnRepository
import com.sesac.speechapp.repository.VoiceRecordRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 발화지표 산정식 서비스 (D-8② 분할, 2026-09-06 — 동작 불변).
 *
 * SessionFlowService에서 이동: calculateArticulationRate·calculateUserRT
 * (03 계약서 §10 — 최근 20개 창 내 최단 10 선정, D-5 [3.2] 확정분).
 * 로직 전부 SessionFlowService 9521f2b 기준 그대로 — 산정식·반올림 자릿수 무변경.
 *
 * 호출처: SessionScoringService (submitNaming→userRT / submitShadowing→articulationRate).
 * 대상 0개면 null — 호출부에서 0 전송 (첫사용 규약 v1.4).
 */
@Service
class ScoreCalculationService(
    private val turnRepository: TurnRepository,
    private val voiceRecordRepository: VoiceRecordRepository
) {
    /**
     * articulationRate (v1.4/03 계약서 §10): 최근 문제풀이(NAMING/SHADOWING/SELF_TALK)
     * 유저 음성 중 SYLLABLES NOT NULL·ARTICULATION_TIME > 0인 것 중 **최근 20개 창**
     * (createdAt 내림차순 20행) 안에서 조음속도(ARTICULATION_TIME÷SYLLABLES) 최단
     * (가장 빠른) 10개의 (SYLLABLES 총합 ÷ ARTICULATION_TIME 총합), 소수 2자리.
     * 대상 0개면 null — 호출부에서 0 전송 (첫사용 규약).
     * ⚠️ D-5 [3.2] 수정: 구 초안은 전체 이력 정렬 take(10) — 최근 20개 창 적용으로 교체.
     */
    fun calculateArticulationRate(userId: Long): BigDecimal? {
        val voicedTypeTurnIds = turnRepository.findByContentTypeIn(listOf("NAMING", "SHADOWING", "SELF_TALK"))
            .map { it.id }.toSet()
        val records = voiceRecordRepository.findByUserId(userId)
            .filter { it.speaker == "USER" && it.turnId in voicedTypeTurnIds }
            .filter { (it.syllables ?: 0) > 0 && it.articulationTime != null && it.articulationTime!!.signum() > 0 }
            .sortedByDescending { it.createdAt }      // 최근 20개 창 — createdAt 내림차순
            .take(20)
        if (records.isEmpty()) return null
        val top10 = records
            .sortedBy { it.articulationTime!!.divide(BigDecimal(it.syllables!!), 6, RoundingMode.HALF_UP) }
            .take(10)
        val syllableSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + BigDecimal(r.syllables!!) }
        val articulationSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + r.articulationTime!! }
        if (articulationSum.signum() == 0) return null
        return syllableSum.divide(articulationSum, 2, RoundingMode.HALF_UP)
    }

    /**
     * userRT (§10): 최근 NAMING 음성 중 SYLLABLES>0·SPEAKING_TIME NOT NULL 대상
     * **최근 20개 창** 안에서 발화시간/음절수 최단 10개의 (발화시간 총합 ÷ 음절수 총합).
     * 대상 0개면 null — 호출부에서 0 전송 (v1.4: 구 null 전송 폐지).
     * ⚠️ D-5 [3.2] 수정: userRT도 최근 20개 창 적용 (구 전체 이력 폐지).
     */
    fun calculateUserRT(userId: Long): BigDecimal? {
        val namingTurnIds = turnRepository.findByContentType("NAMING").map { it.id }.toSet()
        val records = voiceRecordRepository.findByUserId(userId)
            .filter { it.speaker == "USER" && it.turnId in namingTurnIds }
            .filter { (it.syllables ?: 0) > 0 && it.speakingTime != null }
            .sortedByDescending { it.createdAt }      // 최근 20개 창 — createdAt 내림차순
            .take(20)
        if (records.isEmpty()) return null
        val top10 = records
            .sortedBy { it.speakingTime!!.divide(BigDecimal(it.syllables!!), 6, RoundingMode.HALF_UP) }
            .take(10)
        val speakingSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + r.speakingTime!! }
        val syllableSum = top10.fold(BigDecimal.ZERO) { acc, r -> acc + BigDecimal(r.syllables!!) }
        if (syllableSum.signum() == 0) return null
        return speakingSum.divide(syllableSum, 6, RoundingMode.HALF_UP)
    }
}