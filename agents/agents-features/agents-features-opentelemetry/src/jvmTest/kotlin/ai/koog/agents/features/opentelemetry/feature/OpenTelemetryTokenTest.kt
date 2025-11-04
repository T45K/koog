package ai.koog.agents.features.opentelemetry.feature

import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.createAgent
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Response.FinishReasonType
import ai.koog.agents.features.opentelemetry.mock.MockSpanExporter
import ai.koog.agents.features.opentelemetry.mock.TestGetWeatherTool
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer
import ai.koog.utils.io.use
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the OpenTelemetry feature.
 *
 * These tests verify that spans are created correctly during agent execution
 * and that the structure of spans matches the expected hierarchy.
 */
class OpenTelemetryTokenTest : OpenTelemetryTestBase() {

    @Test
    fun `test tokens attributes are captured for inference spans`() = runTest {
        MockSpanExporter().use { mockExporter ->

            val systemPrompt = "You are the application that predicts weather"
            val userPrompt = "What's the weather in Paris?"
            val mockResponse = "The weather in Paris is rainy and overcast, with temperatures around 57°F"

            val agentId = "test-agent-id"
            val promptId = "test-prompt-id"
            val model = OpenAIModels.Chat.GPT4o
            val temperature = 0.4
            val maxTokens = 123

            val strategy = strategy("test-strategy") {
                val nodeSendInput by nodeLLMRequest("test-llm-call")
                val nodeExecuteTool by nodeExecuteTool("test-tool-call")
                val nodeSendToolResult by nodeLLMSendToolResult("test-node-llm-send-tool-result")

                edge(nodeStart forwardTo nodeSendInput)
                edge(nodeSendInput forwardTo nodeExecuteTool onToolCall { true })
                edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
                edge(nodeExecuteTool forwardTo nodeSendToolResult)
                edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
                edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
            }

            val toolRegistry = ToolRegistry {
                tool(TestGetWeatherTool)
            }

            val toolCallId = "tool-call-id"
            val tokenizer = SimpleRegexBasedTokenizer()

            val mockExecutor = getMockExecutor(clock = testClock, tokenizer = tokenizer) {
                mockLLMToolCall(tool = TestGetWeatherTool, args = TestGetWeatherTool.Args("Paris"), toolCallId = toolCallId) onRequestEquals userPrompt
                mockLLMAnswer(mockResponse) onRequestContains TestGetWeatherTool.DEFAULT_PARIS_RESULT
            }

            createAgent(
                agentId = agentId,
                strategy = strategy,
                promptId = promptId,
                systemPrompt = systemPrompt,
                toolRegistry = toolRegistry,
                promptExecutor = mockExecutor,
                model = model,
                clock = testClock,
                temperature = temperature,
                maxTokens = maxTokens,
            ) {
                install(OpenTelemetry) {
                    addSpanExporter(mockExporter)
                    setVerbose(true)
                }
            }.use { agent ->
                agent.run(userPrompt)
            }

            val collectedSpans = mockExporter.collectedSpans
            assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")

            val actualInferenceSpans = collectedSpans.filter { span -> span.name.startsWith("llm.") }
            assertEquals(2, actualInferenceSpans.size)

            // Check Spans

            val expectedInferenceSpans = listOf(
                mapOf(
                    "llm.${TestGetWeatherTool.DEFAULT_PARIS_RESULT}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.request.model" to model.id,
                            "gen_ai.request.max_tokens" to maxTokens.toLong(),
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "gen_ai.operation.name" to "chat",
                            "gen_ai.request.temperature" to temperature,
                            "gen_ai.response.finish_reasons" to listOf(FinishReasonType.Stop.id),
                            "gen_ai.usage.output_tokens" to tokenizer.countTokens(text = mockResponse).toLong()
                        ),
                        "events" to mapOf(
                            "gen_ai.system.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.System.name.lowercase(),
                                "content" to systemPrompt,
                            ),
                            "gen_ai.user.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.User.name.lowercase(),
                                "content" to userPrompt,
                            ),
                            "gen_ai.choice" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.Tool.name.lowercase(),
                                "tool_calls" to """[{"function":{"name":"${TestGetWeatherTool.name}","arguments":"{\"location\":\"Paris\"}"},"id":"$toolCallId","type":"function"}]""",
                                "finish_reason" to FinishReasonType.ToolCalls.id,
                            ),
                            "gen_ai.tool.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.Tool.name.lowercase(),
                                "content" to TestGetWeatherTool.DEFAULT_PARIS_RESULT,
                                "id" to toolCallId,
                            ),
                            "gen_ai.assistant.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.Assistant.name.lowercase(),
                                "content" to mockResponse,
                            ),
                        )
                    )
                ),
                mapOf(
                    "llm.${userPrompt}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.request.model" to model.id,
                            "gen_ai.request.max_tokens" to maxTokens.toLong(),
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "gen_ai.operation.name" to "chat",
                            "gen_ai.request.temperature" to temperature,
                            "gen_ai.response.finish_reasons" to listOf(FinishReasonType.ToolCalls.id),
                            "gen_ai.usage.output_tokens" to tokenizer.countTokens(
                                text = TestGetWeatherTool.encodeArgsToString(TestGetWeatherTool.Args("Paris"))
                            ).toLong(),
                        ),
                        "events" to mapOf(
                            "gen_ai.system.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.System.name.lowercase(),
                                "content" to systemPrompt,
                            ),
                            "gen_ai.user.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.User.name.lowercase(),
                                "content" to userPrompt,
                            ),
                            "gen_ai.choice" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "index" to 0L,
                                "role" to Message.Role.Tool.name.lowercase(),
                                "tool_calls" to """[{"function":{"name":"${TestGetWeatherTool.name}","arguments":"{\"location\":\"Paris\"}"},"id":"$toolCallId","type":"function"}]""",
                                "finish_reason" to FinishReasonType.ToolCalls.id,
                            ),
                        )
                    )
                ),
            )

            assertSpans(expectedInferenceSpans, actualInferenceSpans)
        }
    }
}
