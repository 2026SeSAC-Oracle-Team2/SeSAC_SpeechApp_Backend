package com.sesac.speechapp.service

import com.sesac.speechapp.dto.aicontainer.ContainerOption
import com.sesac.speechapp.dto.session.ChoiceDto
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.Period

/**
 * 세션 플로우 공용 순수 헬퍼 (D-8② 분할, 2026-09-06 — 동작 불변).
 *
 * SessionFlowService의 private 헬퍼를 관심사별 서비스 분할 시 공용으로 이동한 것.
 * 로직 전부 동일 — 타입 매핑·choices 직렬화·스텁 TTS 매핑·나이 산정.
 * (무상태 object — DB 접근 없음, 서비스들은 이 오브젝트를 정적 호출)
 */
object SessionTurnSupport {
    private val logger = LoggerFactory.getLogger(SessionTurnSupport::class.java)
    private val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    /** 컨테이너 소문자 타입 → DB seed 코드 (v1.4: listenText→LISTEN_TEXT 등 6종) */
    fun toSeedType(containerType: String): String = when (containerType.lowercase()) {
        "selftalk", "self_talk" -> "SELF_TALK"
        "listentext" -> "LISTEN_TEXT"
        "listenpicture" -> "LISTEN_PICTURE"
        else -> containerType.uppercase()
    }

    /** seed 코드 → 컨테이너 소문자 타입 (camelCase — 03a 계약) */
    fun toContainerType(seedCode: String): String = when (seedCode) {
        "SELF_TALK" -> "selfTalk"
        "LISTEN_TEXT" -> "listenText"
        "LISTEN_PICTURE" -> "listenPicture"
        else -> seedCode.lowercase()
    }

    /** BIRTH_DATE 기반 나이 산정 (03a §1.1 — 구 AGE 컬럼 폐지 대체) */
    fun calcAge(birthDate: LocalDate): Int =
        Period.between(birthDate, LocalDate.now()).years

    /**
     * B-4 (D-5 [5.1]): 스텁 TTS 타입별 매핑 확정.
     * LISTEN_TEXT/LISTEN_PICTURE→tts_listen / NAMING→tts_naming /
     * SHADOWING→tts_shadowing / STORYTELLING(및 기타)→tts_hello
     * (기존 4종 샘플 재사용 — 새 mp3 추가 없이 경로 분기만 확정, 커밋 최소화)
     */
    fun stubTtsFile(contentType: String): String = when (contentType) {
        "LISTEN_TEXT", "LISTEN_PICTURE" -> "tts_listen.mp3"
        "NAMING" -> "tts_naming.mp3"
        "SHADOWING" -> "tts_shadowing.mp3"
        "STORYTELLING" -> "tts_hello.mp3"
        else -> "tts_hello.mp3"
    }

    /** LISTEN 선택지 → choices_json 직렬화 (order 1-based / mediaType IMAGE|TEXT) */
    fun serializeChoices(options: List<ContainerOption>): String {
        val list = options.mapIndexed { i, o ->
            mapOf(
                "order" to (i + 1),
                "mediaType" to if (o.type == "image") "IMAGE" else "TEXT",
                "context" to o.context
            )
        }
        return objectMapper.writeValueAsString(list)
    }

    /** choices_json 역직렬화 — 파싱 실패 시 null (경고 로그만, 기존 동일) */
    fun deserializeChoices(json: String?): List<ChoiceDto>? {
        if (json == null) return null
        return try {
            val root = objectMapper.readTree(json)
            root.map { node ->
                ChoiceDto(
                    order = node.get("order").asInt(),
                    mediaType = node.get("mediaType").asText(),
                    context = node.get("context").asText()
                )
            }
        } catch (e: Exception) {
            logger.warn("choices_json 파싱 실패: {}", e.message)
            null
        }
    }
}