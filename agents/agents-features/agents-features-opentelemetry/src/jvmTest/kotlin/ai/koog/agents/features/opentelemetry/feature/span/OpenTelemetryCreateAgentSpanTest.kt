package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.createAgentWithSingleLLMCallStrategy
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Operation.OperationNameType
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenTelemetryCreateAgentSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test create and invoke agent spans are collected`() = runTest {

        val createAgentAttribute = SpanAttributes.Operation.Name(OperationNameType.CREATE_AGENT)
        val invokeAgentAttribute = SpanAttributes.Operation.Name(OperationNameType.INVOKE_AGENT)

        val attributeKey = AttributeKey.stringKey(createAgentAttribute.key)

        val collectedTestData = createAgentWithSingleLLMCallStrategy(
            filter = { spanData ->
                spanData.attributes.get(attributeKey) == createAgentAttribute.value ||
                    spanData.attributes.get(attributeKey) == invokeAgentAttribute.value
            }
        )

        val agentId = collectedTestData.agentId
        val runId = collectedTestData.runId
        val model = collectedTestData.model
        val collectedSpans = collectedTestData.collectedSpans

        assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")

        val expectedSpans = listOf(
            mapOf(
                "agent.$agentId" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.operation.name" to "create_agent",
                        "gen_ai.system" to model.provider.id,
                        "gen_ai.agent.id" to agentId,
                        "gen_ai.request.model" to model.id
                    ),
                    "events" to emptyMap()
                )
            ),

            mapOf(
                "run.$runId" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.operation.name" to "invoke_agent",
                        "gen_ai.system" to model.provider.id,
                        "gen_ai.agent.id" to agentId,
                        "gen_ai.conversation.id" to runId
                    ),
                    "events" to emptyMap()
                )
            )
        )

        assertSpans(expectedSpans, collectedSpans)
    }
}
