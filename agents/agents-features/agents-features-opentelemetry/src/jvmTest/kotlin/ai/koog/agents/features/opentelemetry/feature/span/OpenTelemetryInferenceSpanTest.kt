package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.Parameter.SYSTEM_PROMPT
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.Parameter.TEMPERATURE
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.Parameter.USER_PROMPT_PARIS
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithSingleLLMCallStrategy
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Operation.OperationNameType
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.prompt.message.Message
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenTelemetryInferenceSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test inference spans are collected`() = runTest {
        val collectedTestData = runAgentWithSingleLLMCallStrategy()

        val runId = collectedTestData.lastRunId
        val model = collectedTestData.model
        val result = collectedTestData.result

        val actualSpans = collectedTestData.filterInferenceSpans()
        assertTrue(actualSpans.isNotEmpty(), "Spans should be created during agent execution")

        val expectedSpans = listOf(
            mapOf(
                "llm.${USER_PROMPT_PARIS}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.operation.name" to OperationNameType.CHAT.id,
                        "gen_ai.system" to model?.provider?.id,
                        "gen_ai.conversation.id" to runId,
                        "gen_ai.request.temperature" to TEMPERATURE,
                        "gen_ai.request.model" to model?.id,
                        "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.Stop.id)
                    ),
                    "events" to mapOf(
                        "gen_ai.user.message" to mapOf(
                            "gen_ai.system" to model?.provider?.id,
                            "role" to Message.Role.User.name.lowercase(),
                            "content" to USER_PROMPT_PARIS
                        )
                    ),

                    "events" to mapOf(
                        "gen_ai.system.message" to mapOf(
                            "gen_ai.system" to model?.provider?.id,
                            "role" to Message.Role.System.name.lowercase(),
                            "content" to SYSTEM_PROMPT,
                        ),
                        "gen_ai.user.message" to mapOf(
                            "gen_ai.system" to model?.provider?.id,
                            "role" to Message.Role.User.name.lowercase(),
                            "content" to USER_PROMPT_PARIS,
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

        assertSpans(expectedSpans, actualSpans)
    }
}
