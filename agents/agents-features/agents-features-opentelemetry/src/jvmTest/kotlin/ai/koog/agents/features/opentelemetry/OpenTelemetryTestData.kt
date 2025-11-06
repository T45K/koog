package ai.koog.agents.features.opentelemetry

import ai.koog.prompt.llm.LLModel
import io.opentelemetry.sdk.trace.data.SpanData

internal data class NodeInfo(val nodeName: String, val nodeId: String)

internal data class OpenTelemetryTestData(
    var agentId: String? = null,
    var runIds: List<String> = emptyList(),
    var model: LLModel? = null,
    var temperature: Double? = null,
    var userPrompt: String? = null,
    var systemPrompt: String? = null,
    var toolCallId: String? = null,
    var toolCallArg: String? = null,
    var result: String? = null,
    var collectedSpans: List<SpanData> = emptyList(),
    var collectedNodeIds: List<NodeInfo> = emptyList(),
) {
    val lastRunId: String
        get() = runIds.last()

    fun singleNodeInfoByName(nodeName: String): NodeInfo = collectedNodeIds.single { it.nodeName == nodeName }

    fun singleNodeIdByName(nodeName: String): String = singleNodeInfoByName(nodeName).nodeId
}
