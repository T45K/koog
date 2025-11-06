package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithSingleLLMCallStrategy
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Operation.OperationNameType
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenTelemetryAgentSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test create and invoke agent spans are collected`() = runTest {

        val collectedTestData = runAgentWithSingleLLMCallStrategy()

        val agentId = collectedTestData.agentId
        val runId = collectedTestData.lastRunId
        val model = collectedTestData.model

        val actualSpans = collectedTestData.filterCreateAgentSpans() + collectedTestData.filterAgentInvokeSpans()
        assertTrue(actualSpans.isNotEmpty(), "Spans should be created during agent execution")

        val expectedSpans = listOf(
            mapOf(
                "agent.$agentId" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.operation.name" to OperationNameType.CREATE_AGENT.id,
                        "gen_ai.system" to model?.provider?.id,
                        "gen_ai.agent.id" to agentId,
                        "gen_ai.request.model" to model?.id
                    ),
                    "events" to emptyMap()
                )
            ),

            mapOf(
                "run.$runId" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.operation.name" to OperationNameType.INVOKE_AGENT.id,
                        "gen_ai.system" to model?.provider?.id,
                        "gen_ai.agent.id" to agentId,
                        "gen_ai.conversation.id" to runId
                    ),
                    "events" to emptyMap()
                )
            )
        )

        assertSpans(expectedSpans, actualSpans)
    }
}
