package com.sesac.speechapp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 외부 AI 컨테이너(문제 생성 / 채점) 엔드포인트 설정
 *
 * application.yml 예시:
 *   container:
 *     llm:
 *       base-url: "http://llm-container:8000"
 *       generate-path: "/api/v1/generate"
 *       timeout-ms: 30000
 *     scoring:
 *       base-url: "http://scoring-container:8000"
 *       score-path: "/api/v1/score"
 *       timeout-ms: 60000
 */
@Component
@ConfigurationProperties(prefix = "container")
class ContainerProperties {
    var llm: ContainerEndpoint = ContainerEndpoint()
    var scoring: ContainerEndpoint = ContainerEndpoint()

    data class ContainerEndpoint(
        var baseUrl: String = "",
        var generatePath: String = "",
        var scorePath: String = "",
        var timeoutMs: Long = 30000
    )
}
