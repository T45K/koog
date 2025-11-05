package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.utils.SerializationUtils
import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithSingleToolCallStrategy
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.testClock
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Operation.OperationNameType
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.agents.features.opentelemetry.mock.TestGetWeatherTool
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenTelemetryExecuteToolSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test execute tool spans are collected`() = runTest {
        val executeToolAttribute = SpanAttributes.Operation.Name(OperationNameType.EXECUTE_TOOL)
        val attributeKey = AttributeKey.stringKey(executeToolAttribute.key)

        val collectedTestData = runAgentWithSingleToolCallStrategy(
            filter = { spanData -> spanData.attributes.get(attributeKey) != executeToolAttribute.value }
        )

        val toolCallId = collectedTestData.toolCallId
        val toolCallArg = collectedTestData.toolCallArg
        val collectedSpans = collectedTestData.collectedSpans

        assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")

        val expectedSpans = listOf(
            mapOf(
                "tool.${TestGetWeatherTool.name}" to mapOf(
                    "attributes" to mapOf(
                        "output.value" to TestGetWeatherTool.DEFAULT_PARIS_RESULT,
                        "input.value" to "{\"location\":\"$toolCallArg\"}",
                        "gen_ai.tool.description" to TestGetWeatherTool.description,
                        "gen_ai.tool.name" to TestGetWeatherTool.name,
                        "gen_ai.tool.call.id" to toolCallId,
                    ),
                    "events" to mapOf()
                )
            ),
        )

        assertSpans(expectedSpans, collectedSpans)
    }
}
