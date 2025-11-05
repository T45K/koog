package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithSingleLLMCallStrategy
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Operation.OperationNameType
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.prompt.message.Message
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenTelemetryInferenceSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test inference spans are collected`() = runTest {
        val chatAttribute = SpanAttributes.Operation.Name(OperationNameType.CHAT)
        val attributeKey = AttributeKey.stringKey(chatAttribute.key)

        val collectedTestData = runAgentWithSingleLLMCallStrategy(
            filter = { spanData -> spanData.attributes.get(attributeKey) == chatAttribute.value }
        )

        val runId = collectedTestData.runId
        val model = collectedTestData.model
        val temperature = collectedTestData.temperature
        val userPrompt = collectedTestData.userPrompt
        val systemPrompt = collectedTestData.systemPrompt
        val result = collectedTestData.result
        val collectedSpans = collectedTestData.collectedSpans

        assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")

        val expectedSpans = listOf(
            mapOf(
                "llm.${userPrompt}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.operation.name" to OperationNameType.CHAT.id,
                        "gen_ai.system" to model?.provider?.id,
                        "gen_ai.conversation.id" to runId,
                        "gen_ai.request.temperature" to temperature,
                        "gen_ai.request.model" to model?.id,
                        "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.Stop.id)
                    ),
                    "events" to mapOf(
                        "gen_ai.user.message" to mapOf(
                            "gen_ai.system" to model?.provider?.id,
                            "role" to Message.Role.User.name.lowercase(),
                            "content" to userPrompt
                        )
                    ),

                    "events" to mapOf(
                        "gen_ai.system.message" to mapOf(
                            "gen_ai.system" to model?.provider?.id,
                            "role" to Message.Role.System.name.lowercase(),
                            "content" to systemPrompt,
                        ),
                        "gen_ai.user.message" to mapOf(
                            "gen_ai.system" to model?.provider?.id,
                            "role" to Message.Role.User.name.lowercase(),
                            "content" to userPrompt,
                        ),
                        "gen_ai.assistant.message" to mapOf(
                            "gen_ai.system" to model?.provider?.id,
                            "role" to Message.Role.Assistant.name.lowercase(),
                            "content" to result,
                        )
                    )
                )
            ),
        )

        assertSpans(expectedSpans, collectedSpans)
    }
}
