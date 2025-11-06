package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.utils.SerializationUtils
import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.Parameter.MOCK_LLM_RESPONSE_PARIS
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.Parameter.USER_PROMPT_PARIS
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.runAgentWithStrategy
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.testClock
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenTelemetrySubgraphTest : OpenTelemetryTestBase() {

    @Test
    fun `test node execution spans are collected`() = runTest {

        val subgraphNodeName = "test-subgraph-node"
        val subgraphLLMCallNodeName = "test-subgraph-llm-call"

        val strategy = strategy<String, String>("test-strategy") {
            val nodeSubgraph by subgraph<String, String>(subgraphNodeName) {
                val nodeSubgraphLLMCall by nodeLLMRequest(subgraphLLMCallNodeName)

                edge(nodeStart forwardTo nodeSubgraphLLMCall)
                edge(nodeSubgraphLLMCall forwardTo nodeFinish onAssistantMessage { true })
            }

            nodeStart then nodeSubgraph then nodeFinish
        }

        val executor = getMockExecutor(clock = testClock) {
            mockLLMAnswer(MOCK_LLM_RESPONSE_PARIS) onRequestEquals USER_PROMPT_PARIS
        }

        val collectedTestData = runAgentWithStrategy(strategy = strategy, userPrompt = USER_PROMPT_PARIS, executor = executor)

        val runId = collectedTestData.lastRunId
        val result = collectedTestData.result

        val actualSpans = collectedTestData.filterNodeExecutionSpans()
        assertTrue(actualSpans.isNotEmpty(), "Spans should be created during agent execution")

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
                        "koog.node.input" to "\"$MOCK_LLM_RESPONSE_PARIS\"",
                    ),
                    "events" to emptyMap()
                )
            ),
            mapOf(
                "node.$subgraphNodeName.${collectedTestData.singleNodeIdByName(subgraphNodeName)}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to subgraphNodeName,
                        "koog.node.output" to "\"$MOCK_LLM_RESPONSE_PARIS\"",
                        "koog.node.input" to "\"$USER_PROMPT_PARIS\"",
                    ),
                    "events" to emptyMap()
                )
            ),
            mapOf(
                "node.__finish__$subgraphNodeName.${collectedTestData.singleNodeIdByName("__finish__$subgraphNodeName")}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to "__finish__$subgraphNodeName",
                        "koog.node.output" to "\"$MOCK_LLM_RESPONSE_PARIS\"",
                        "koog.node.input" to "\"$MOCK_LLM_RESPONSE_PARIS\"",
                    ),
                    "events" to emptyMap()
                )
            ),
            mapOf(
                "node.$subgraphLLMCallNodeName.${collectedTestData.singleNodeIdByName(subgraphLLMCallNodeName)}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to subgraphLLMCallNodeName,
                        "koog.node.output" to serializedAssistantResponse,
                        "koog.node.input" to "\"$USER_PROMPT_PARIS\"",
                    ),
                    "events" to emptyMap()
                )
            ),
            mapOf(
                "node.__start__$subgraphNodeName.${collectedTestData.singleNodeIdByName("__start__$subgraphNodeName")}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to "__start__$subgraphNodeName",
                        "koog.node.input" to "\"$USER_PROMPT_PARIS\"",
                        "koog.node.output" to "\"$USER_PROMPT_PARIS\"",
                    ),
                    "events" to emptyMap()
                )
            ),
            mapOf(
                "node.__start__.${collectedTestData.singleNodeIdByName("__start__")}" to mapOf(
                    "attributes" to mapOf(
                        "gen_ai.conversation.id" to runId,
                        "koog.node.name" to "__start__",
                        "koog.node.input" to "\"$USER_PROMPT_PARIS\"",
                        "koog.node.output" to "\"$USER_PROMPT_PARIS\"",
                    ),
                    "events" to emptyMap()
                )
            )
        )

        assertSpans(expectedSpans, actualSpans)
    }

}
