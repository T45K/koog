package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithSingleToolCallStrategy
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Operation.OperationNameType
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.agents.features.opentelemetry.mock.TestGetWeatherTool
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTelemetryExecuteToolSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test execute tool spans are collected`() = runTest {
        val collectedTestData = runAgentWithSingleToolCallStrategy()

        val toolCallId = collectedTestData.toolCallId
        val toolCallArg = collectedTestData.toolCallArg
        val collectedSpans = collectedTestData.collectedSpans

        assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")
        assertNotNull(toolCallArg, "Tool call arg should not be null")

        val executeToolAttribute = SpanAttributes.Operation.Name(OperationNameType.EXECUTE_TOOL)
        val attributeKey = AttributeKey.stringKey(executeToolAttribute.key)

        val actualSpans = collectedSpans.filter { spanData ->
            spanData.attributes.get(attributeKey) == executeToolAttribute.value
        }

        val serializedArgs = TestGetWeatherTool.encodeArgsToString(TestGetWeatherTool.Args(toolCallArg))

        val expectedSpans = listOf(
            mapOf(
                "tool.${TestGetWeatherTool.name}.args.$serializedArgs" to mapOf(
                    "attributes" to mapOf(
                        "output.value" to TestGetWeatherTool.DEFAULT_PARIS_RESULT,
                        "input.value" to serializedArgs,
                        "gen_ai.tool.name" to TestGetWeatherTool.name,
                        "gen_ai.tool.call.id" to toolCallId,
                        "gen_ai.operation.name" to OperationNameType.EXECUTE_TOOL.id,
                        "gen_ai.tool.description" to TestGetWeatherTool.description,
                    ),
                    "events" to mapOf()
                )
            ),
        )

        assertSpans(expectedSpans, actualSpans)
    }
}
