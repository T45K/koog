package ai.koog.agents.features.opentelemetry

import ai.koog.prompt.llm.LLModel
import io.opentelemetry.sdk.trace.data.SpanData

internal data class NodeInfo(val nodeName: String, val nodeId: String)

internal data class OpenTelemetryTestData(
    var agentId: String? = null,
    var runId: String? = null,
    var model: LLModel? = null,
    var temperature: Double? = null,
    var userPrompt: String? = null,
    var systemPrompt: String? = null,
    var result: String? = null,
    val collectedSpans: List<SpanData> = emptyList(),
    val collectedNodeIds: List<NodeInfo> = emptyList(),
) {
    fun singleNodeInfoByName(nodeName: String): NodeInfo = collectedNodeIds.single { it.nodeName == nodeName }

    fun singleNodeIdByName(nodeName: String): String = singleNodeInfoByName(nodeName).nodeId

    fun merge(other: OpenTelemetryTestData): OpenTelemetryTestData = OpenTelemetryTestData(
        this.agentId ?: other.agentId,
        this.runId ?: other.runId,
        this.model ?: other.model,
        this.temperature ?: other.temperature,
        this.userPrompt ?: other.userPrompt,
        this.systemPrompt ?: other.systemPrompt,
        this.result ?: other.result,
        this.collectedSpans + other.collectedSpans,
        this.collectedNodeIds + other.collectedNodeIds,
    )
}
