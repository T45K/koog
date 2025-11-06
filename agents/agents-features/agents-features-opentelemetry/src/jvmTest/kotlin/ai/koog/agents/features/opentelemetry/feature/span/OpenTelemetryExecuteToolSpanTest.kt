package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithSingleToolCallStrategy
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Operation.OperationNameType
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.agents.features.opentelemetry.mock.TestGetWeatherTool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTelemetryExecuteToolSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test execute tool spans are collected`() = runTest {
        val collectedTestData = runAgentWithSingleToolCallStrategy()

        val toolCallId = collectedTestData.toolCallId

        val actualSpans = collectedTestData.filterExecuteToolSpans()
        assertTrue(actualSpans.isNotEmpty(), "Spans should be created during agent execution")

        val toolCallArg = collectedTestData.toolCallArg
        assertNotNull(toolCallArg, "Tool call arg should not be null")

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
