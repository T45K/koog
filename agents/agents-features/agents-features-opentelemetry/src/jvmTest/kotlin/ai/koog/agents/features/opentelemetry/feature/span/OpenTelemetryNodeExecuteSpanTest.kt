package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.utils.SerializationUtils
import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithSingleLLMCallStrategy
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithStrategy
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.testClock
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestData
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class OpenTelemetryNodeExecuteSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test node execute spans are collected`() = runTest {
        val attributeKey = AttributeKey.stringKey("koog.node.name")

        val collectedTestData = runAgentWithSingleLLMCallStrategy(
            filter = { spanData -> spanData.attributes.get(attributeKey) != null }
        )

        val runId = collectedTestData.runId
        val userPrompt = collectedTestData.userPrompt
        val result = collectedTestData.result
        val collectedSpans = collectedTestData.collectedSpans

        assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")

        @OptIn(InternalAgentsApi::class)
        val serializedAssistantResponse = SerializationUtils.encodeDataToStringOrDefault(
            data = Message.Assistant(
                content = result.toString(),
                metaInfo = ResponseMetaInfo(
                    timestamp = testClock.now()
                )
            ),
            dataType = typeOf<Message>()
        )

        val expectedSpans = listOf(
            mapOf(
                "node.__finish__.${collectedTestData.singleNodeIdByName("__finish__")}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to "__finish__",
                        "koog.node.output" to "\"$result\"",
                        "koog.node.input" to "\"$result\"",
                    ),
                    "events" to emptyMap()
                )
            ),
            mapOf(
                "node.test-llm-call.${collectedTestData.singleNodeIdByName("test-llm-call")}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to "test-llm-call",
                        "koog.node.input" to "\"$userPrompt\"",
                        "koog.node.output" to serializedAssistantResponse
                    ),
                    "events" to emptyMap()
                )
            ),
            mapOf(
                "node.__start__.${collectedTestData.singleNodeIdByName("__start__")}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to "__start__",
                        "koog.node.input" to "\"$userPrompt\"",
                        "koog.node.output" to "\"$userPrompt\"",
                    ),
                    "events" to emptyMap()
                )
            )
        )

        assertSpans(expectedSpans, collectedSpans)
    }

    @Test
    fun `test node execution spans for node with error`() = runTest {
        val nodeWithErrorName = "node-with-error"
        val testErrorMessage = "Test error"

        val strategy = strategy("test-strategy") {
            val nodeWithError by node<String, String>(nodeWithErrorName) {
                throw IllegalStateException(testErrorMessage)
            }

            nodeStart then nodeWithError then nodeFinish
        }

        val collectedTestData = OpenTelemetryTestData()
        val throwable = assertFails {
            runAgentWithStrategy(strategy = strategy, collectedTestData = collectedTestData)
        }

        val runId = collectedTestData.runId
        val userPrompt = collectedTestData.userPrompt
        val collectedSpans = collectedTestData.collectedSpans

        assertEquals(testErrorMessage, throwable.message)

        val expectedSpans = listOf(
            mapOf(
                "node.$nodeWithErrorName.${collectedTestData.singleNodeIdByName(nodeWithErrorName)}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to "\"$nodeWithErrorName\"",
                        "koog.node.input" to "\"$userPrompt\"",
                    ),
                    "events" to emptyMap()
                )
            ),

            mapOf(
                "node.__start__.${collectedTestData.singleNodeIdByName("__start__")}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to "__start__",
                        "koog.node.input" to "\"$userPrompt\"",
                        "koog.node.output" to "\"$userPrompt\"",
                    ),
                    "events" to emptyMap()
                )
            )
        )

        assertSpans(expectedSpans, collectedSpans)
    }
}
