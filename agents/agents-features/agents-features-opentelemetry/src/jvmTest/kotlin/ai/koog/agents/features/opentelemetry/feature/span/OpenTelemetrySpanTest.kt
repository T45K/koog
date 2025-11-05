package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.core.agent.context.element.getNodeInfoElement
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.utils.SerializationUtils
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.agents.features.opentelemetry.mock.MockSpanExporter
import ai.koog.agents.features.opentelemetry.mock.TestGetWeatherTool
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.io.use
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Tests for the OpenTelemetry feature.
 *
 * These tests verify that spans are created correctly during agent execution
 * and that the structure of spans matches the expected hierarchy.
 */
class OpenTelemetrySpanTest : OpenTelemetryTestBase() {

    companion object {
        private val logger = KotlinLogging.logger { }
    }


    object Strategies {
        val strategy1_singleLLMCall = strategy("test-strategy") {
            val nodeSendInput by nodeLLMRequest("test-llm-call")

            edge(nodeStart forwardTo nodeSendInput)
            edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
        }


        val strategy2_singleLLMCall = strategy("test-strategy") {
            val nodeSendInput by nodeLLMRequest("test-llm-call")

            edge(nodeStart forwardTo nodeSendInput)
            edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
        }

        val strategy3_tollCall = strategy("test-strategy") {
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

        val strategy4_parallel = strategy("test-strategy") {
            val nodeFirstJoke by node<String, String> { topic ->
                "First joke about $topic: Why do programmers prefer dark mode? Because light attracts bugs!"
            }

            val nodeSecondJoke by node<String, String> { topic ->
                "Second joke about $topic: Why do Java developers wear glasses? Because they don't C#!"
            }

            val nodeThirdJoke by node<String, String> { topic ->
                "Third joke about $topic: A SQL query walks into a bar, walks up to two tables and asks, 'Can I join you?'"
            }

            // Define a node to run joke generation in parallel
            val nodeGenerateJokes by parallel(
                nodeFirstJoke,
                nodeSecondJoke,
                nodeThirdJoke
            ) {
                selectByIndex {
                    // Always select the first joke for testing purposes
                    0
                }
            }

            edge(nodeStart forwardTo nodeGenerateJokes)
            edge(nodeGenerateJokes forwardTo nodeFinish)
        }

        val nodeWithErrorName = "node-with-error"
        val testErrorMessage = "Test error"
        val strategy5_error = strategy("test-strategy") {
            val nodeWithError by node<String, String>(nodeWithErrorName) {
                throw IllegalStateException(testErrorMessage)
            }

            edge(nodeStart forwardTo nodeWithError)
            edge(nodeWithError forwardTo nodeFinish)
        }
    }



//    @Test
//    fun `test spans are created for agent with one llm call`() = runTest {
//        MockSpanExporter().use { mockExporter ->
//
//            val systemPrompt = "You are the application that predicts weather"
//            val userPrompt = "What's the weather in Paris?"
//
//            val agentId = "test-agent-id"
//            val promptId = "test-prompt-id"
//            val model = OpenAIModels.Chat.GPT4o
//            val temperature = 0.4
//
//            val strategy = strategy("test-strategy") {
//                val nodeSendInput by nodeLLMRequest("test-llm-call")
//
//                edge(nodeStart forwardTo nodeSendInput)
//                edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
//            }
//
//            val mockResponse = "The weather in Paris is rainy and overcast, with temperatures around 57°F"
//
//            val mockExecutor = getMockExecutor(clock = testClock) {
//                mockLLMAnswer(mockResponse) onRequestEquals userPrompt
//            }
//
//            OpenTelemetryTestAPI.createAgent(
//                agentId = agentId,
//                strategy = strategy,
//                promptId = promptId,
//                systemPrompt = systemPrompt,
//                promptExecutor = mockExecutor,
//                model = model,
//                clock = testClock,
//                temperature = temperature
//            ) {
//                install(OpenTelemetry.Feature) {
//                    addSpanExporter(mockExporter)
//                    setVerbose(true)
//                }
//            }.use { agent ->
//                agent.run(userPrompt)
//            }
//
//            val collectedSpans = mockExporter.collectedSpans
//            assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")
//
//            // Check each span
//
//            val expectedSpans = listOf(
//                mapOf(
//                    "agent.$agentId" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "create_agent",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.request.model" to model.id
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "run.${mockExporter.lastRunId}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "invoke_agent",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.conversation.id" to mockExporter.lastRunId
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "node.__finish__.\"${mockResponse}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "__finish__",
//                            "koog.node.output" to "\"$mockResponse\"",
//                            "koog.node.input" to "\"$mockResponse\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "node.test-llm-call.\"${userPrompt}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "test-llm-call",
//                            "koog.node.input" to "\"$userPrompt\"",
//                            "koog.node.output" to @OptIn(InternalAgentsApi::class)
//                            SerializationUtils.encodeDataToStringOrDefault(
//                                data = Message.Assistant(
//                                    content = mockResponse,
//                                    metaInfo = ResponseMetaInfo(
//                                        timestamp = testClock.now()
//                                    )
//                                ),
//                                dataType = typeOf<Message>()
//                            ),
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "llm.${userPrompt}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "chat",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "gen_ai.request.temperature" to temperature,
//                            "gen_ai.request.model" to model.id,
//                            "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.Stop.id)
//                        ),
//                        "events" to mapOf(
//                            "gen_ai.user.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.User.name.lowercase(),
//                                "content" to userPrompt
//                            )
//                        ),
//
//                        "events" to mapOf(
//                            "gen_ai.system.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.System.name.lowercase(),
//                                "content" to systemPrompt,
//                            ),
//                            "gen_ai.user.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.User.name.lowercase(),
//                                "content" to userPrompt,
//                            ),
//                            "gen_ai.assistant.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.Assistant.name.lowercase(),
//                                "content" to mockResponse,
//                            )
//                        )
//                    )
//                ),
//
//                mapOf(
//                    "node.__start__.\"$userPrompt\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "__start__",
//                            "koog.node.input" to "\"$userPrompt\"",
//                            "koog.node.output" to "\"$userPrompt\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                )
//            )
//
//            assertSpans(expectedSpans, collectedSpans)
//        }
//    }
//
//    @Test
//    fun `test spans for same agent run multiple times`() = runTest {
//        MockSpanExporter().use { mockExporter ->
//
//            val systemPrompt = "You are the application that predicts weather"
//
//            val userPrompt0 = "What's the weather in Paris?"
//            val mockResponse0 = "The weather in Paris is rainy and overcast, with temperatures around 57°F"
//
//            val userPrompt1 = "What's the weather in London?"
//            val mockResponse1 = "The weather in London is sunny, with temperatures around 65°F"
//
//            val agentId = "test-agent-id"
//            val promptId = "test-prompt-id"
//            val model = OpenAIModels.Chat.GPT4o
//            val temperature = 0.4
//
//            val strategy = strategy("test-strategy") {
//                val nodeSendInput by nodeLLMRequest("test-llm-call")
//
//                edge(nodeStart forwardTo nodeSendInput)
//                edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
//            }
//
//            val mockExecutor = getMockExecutor(clock = testClock) {
//                mockLLMAnswer(mockResponse0) onRequestEquals userPrompt0
//                mockLLMAnswer(mockResponse1) onRequestEquals userPrompt1
//            }
//
//            val nodeNameToIdMap1 = mutableMapOf<String, String>()
//            val nodeNameToIdMap = mutableMapOf<String, String>()
//
//            val agentService = OpenTelemetryTestAPI.createAgentService(
//                strategy = strategy,
//                promptId = promptId,
//                systemPrompt = systemPrompt,
//                promptExecutor = mockExecutor,
//                model = model,
//                clock = testClock,
//                temperature = temperature
//            ) {
//                install(OpenTelemetry.Feature) {
//                    addSpanExporter(mockExporter)
//                    setVerbose(true)
//                }
//
//                install(EventHandler.Feature) {
//                    onNodeExecutionStarting { eventContext ->
//                        getNodeInfoElement()?.id?.let { nodeId -> nodeNameToIdMap[eventContext.node.name] = nodeId }
//                    }
//                }
//            }
//
//            agentService.createAgentAndRun(userPrompt0, id = agentId)
//
//            agentService.createAgentAndRun(userPrompt1, id = agentId)
//
//            val collectedSpans = mockExporter.collectedSpans
//            assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")
//
//            agentService.closeAll()
//
//            // Check each span
//
//            val expectedSpans = listOf(
//                mapOf(
//                    "agent.$agentId" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "create_agent",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.request.model" to model.id
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                // First run
//                mapOf(
//                    "run.${mockExporter.runIds[1]}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "invoke_agent",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.conversation.id" to mockExporter.runIds[1]
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "node.__finish__.${nodeNameToIdMap["__finish__"]}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.runIds[1],
//                            "koog.node.name" to "__finish__",
//                            "koog.node.input" to "\"$mockResponse1\"",
//                            "koog.node.output" to "\"$mockResponse1\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "node.test-llm-call.\"${userPrompt1}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.runIds[1],
//                            "koog.node.name" to "test-llm-call",
//                            "koog.node.input" to "\"$userPrompt1\"",
//                            "koog.node.output" to @OptIn(InternalAgentsApi::class)
//                            SerializationUtils.encodeDataToStringOrDefault(
//                                data = Message.Assistant(
//                                    content = mockResponse1,
//                                    metaInfo = ResponseMetaInfo(
//                                        timestamp = testClock.now()
//                                    )
//                                ),
//                                dataType = typeOf<Message>()
//                            ),
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "llm.${userPrompt1}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "chat",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.conversation.id" to mockExporter.runIds[1],
//                            "gen_ai.request.temperature" to temperature,
//                            "gen_ai.request.model" to model.id,
//                            "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.Stop.id),
//                        ),
//                        "events" to mapOf(
//                            "gen_ai.system.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.System.name.lowercase(),
//                                "content" to systemPrompt,
//                            ),
//                            "gen_ai.user.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.User.name.lowercase(),
//                                "content" to userPrompt1,
//                            ),
//                            "gen_ai.assistant.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.Assistant.name.lowercase(),
//                                "content" to mockResponse1,
//                            )
//                        )
//                    )
//                ),
//
//                mapOf(
//                    "node.__start__.\"${userPrompt1}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.runIds[1],
//                            "koog.node.name" to "__start__",
//                            "koog.node.input" to "\"$userPrompt1\"",
//                            "koog.node.output" to "\"$userPrompt1\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                // Second run
//                mapOf(
//                    "run.${mockExporter.runIds[0]}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "invoke_agent",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.conversation.id" to mockExporter.runIds[0]
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "node.__finish__.\"${mockResponse0}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.runIds[0],
//                            "koog.node.name" to "__finish__",
//                            "koog.node.input" to "\"$mockResponse0\"",
//                            "koog.node.output" to "\"$mockResponse0\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "node.test-llm-call.\"${userPrompt0}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.runIds[0],
//                            "koog.node.name" to "test-llm-call",
//                            "koog.node.input" to "\"$userPrompt0\"",
//                            "koog.node.output" to @OptIn(InternalAgentsApi::class)
//                            SerializationUtils.encodeDataToStringOrDefault(
//                                data = Message.Assistant(
//                                    content = mockResponse0,
//                                    metaInfo = ResponseMetaInfo(
//                                        timestamp = testClock.now()
//                                    )
//                                ),
//                                dataType = typeOf<Message>()
//                            ),
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "llm.${userPrompt0}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "chat",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.conversation.id" to mockExporter.runIds[0],
//                            "gen_ai.request.temperature" to temperature,
//                            "gen_ai.request.model" to model.id,
//                            "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.Stop.id),
//                        ),
//                        "events" to mapOf(
//                            "gen_ai.system.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.System.name.lowercase(),
//                                "content" to systemPrompt,
//                            ),
//                            "gen_ai.user.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.User.name.lowercase(),
//                                "content" to userPrompt0,
//                            ),
//                            "gen_ai.assistant.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.Assistant.name.lowercase(),
//                                "content" to mockResponse0,
//                            )
//                        )
//                    )
//                ),
//
//                mapOf(
//                    "node.__start__.\"${userPrompt0}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.runIds[0],
//                            "koog.node.name" to "__start__",
//                            "koog.node.input" to "\"$userPrompt0\"",
//                            "koog.node.output" to "\"$userPrompt0\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                )
//            )
//
//            assertSpans(expectedSpans, collectedSpans)
//        }
//    }
//
//    @Test
//    fun `test spans are created for agent with tool call`() = runTest {
//        MockSpanExporter().use { mockExporter ->
//
//            val systemPrompt = "You are the application that predicts weather"
//            val userPrompt = "What's the weather in Paris?"
//            val mockResponse = "The weather in Paris is rainy and overcast, with temperatures around 57°F"
//
//            val agentId = "test-agent-id"
//            val promptId = "test-prompt-id"
//            val model = OpenAIModels.Chat.GPT4o
//            val temperature = 0.4
//
//            val strategy = strategy("test-strategy") {
//                val nodeSendInput by nodeLLMRequest("test-llm-call")
//                val nodeExecuteTool by nodeExecuteTool("test-tool-call")
//                val nodeSendToolResult by nodeLLMSendToolResult("test-node-llm-send-tool-result")
//
//                edge(nodeStart forwardTo nodeSendInput)
//                edge(nodeSendInput forwardTo nodeExecuteTool onToolCall { true })
//                edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
//                edge(nodeExecuteTool forwardTo nodeSendToolResult)
//                edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
//                edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
//            }
//
//            val toolRegistry = ToolRegistry.Companion {
//                tool(TestGetWeatherTool)
//            }
//
//            val toolCallId = "tool-call-id"
//
//            val mockExecutor = getMockExecutor(clock = testClock) {
//                mockLLMToolCall(
//                    tool = TestGetWeatherTool,
//                    args = TestGetWeatherTool.Args("Paris"),
//                    toolCallId = toolCallId
//                ) onRequestEquals userPrompt
//                mockLLMAnswer(mockResponse) onRequestContains TestGetWeatherTool.DEFAULT_PARIS_RESULT
//            }
//
//            OpenTelemetryTestAPI.createAgent(
//                agentId = agentId,
//                strategy = strategy,
//                promptId = promptId,
//                systemPrompt = systemPrompt,
//                toolRegistry = toolRegistry,
//                promptExecutor = mockExecutor,
//                model = model,
//                clock = testClock,
//                temperature = temperature
//            ) {
//                install(OpenTelemetry.Feature) {
//                    addSpanExporter(mockExporter)
//                    setVerbose(true)
//                }
//            }.use { agent ->
//                agent.run(userPrompt)
//            }
//
//            val collectedSpans = mockExporter.collectedSpans
//            assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")
//
//            // Check Spans
//
//            fun getWeatherInParisCallSerialized(dataType: KType = typeOf<Message.Tool.Call>()) =
//                @OptIn(InternalAgentsApi::class) SerializationUtils.encodeDataToStringOrDefault(
//                    data = Message.Tool.Call(
//                        id = toolCallId,
//                        tool = TestGetWeatherTool.name,
//                        content = "{\"location\":\"Paris\"}",
//                        metaInfo = ResponseMetaInfo(
//                            timestamp = testClock.now()
//                        )
//                    ),
//                    dataType = dataType
//                )
//
//            fun responseOutputSerialized() =
//                @OptIn(InternalAgentsApi::class) SerializationUtils.encodeDataToStringOrDefault(
//                    data = Message.Assistant(
//                        content = mockResponse,
//                        metaInfo = ResponseMetaInfo(
//                            timestamp = testClock.now()
//                        )
//                    ),
//                    dataType = typeOf<Message>()
//                )
//
//            val expectedSpans = listOf(
//                mapOf(
//                    "agent.$agentId" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.request.model" to model.id,
//                            "gen_ai.operation.name" to "create_agent",
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//                mapOf(
//                    "run.${mockExporter.lastRunId}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "gen_ai.operation.name" to "invoke_agent",
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//                mapOf(
//                    "node.__finish__.\"${mockResponse}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "__finish__",
//                            "koog.node.input" to "\"$mockResponse\"",
//                            "koog.node.output" to "\"$mockResponse\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//                mapOf(
//                    "node.test-node-llm-send-tool-result.${TestGetWeatherTool.DEFAULT_PARIS_RESULT}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "test-node-llm-send-tool-result",
//                            "koog.node.input" to TestGetWeatherTool.DEFAULT_PARIS_RESULT,
//                            "koog.node.output" to responseOutputSerialized(),
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//                mapOf(
//                    "llm.${TestGetWeatherTool.DEFAULT_PARIS_RESULT}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.request.model" to model.id,
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "gen_ai.operation.name" to "chat",
//                            "gen_ai.request.temperature" to temperature,
//                            "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.Stop.id),
//                        ),
//                        "events" to mapOf(
//                            "gen_ai.system.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.System.name.lowercase(),
//                                "content" to systemPrompt,
//                            ),
//                            "gen_ai.user.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.User.name.lowercase(),
//                                "content" to userPrompt,
//                            ),
//                            "gen_ai.choice" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.Tool.name.lowercase(),
//                                "tool_calls" to """[{"function":{"name":"${TestGetWeatherTool.name}","arguments":"{\"location\":\"Paris\"}"},"id":"$toolCallId","type":"function"}]""",
//                                "finish_reason" to SpanAttributes.Response.FinishReasonType.ToolCalls.id,
//                            ),
//                            "gen_ai.tool.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.Tool.name.lowercase(),
//                                "content" to TestGetWeatherTool.DEFAULT_PARIS_RESULT,
//                                "id" to toolCallId,
//                            ),
//                            "gen_ai.assistant.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.Assistant.name.lowercase(),
//                                "content" to mockResponse,
//                            ),
//                        )
//                    )
//                ),
//                mapOf(
//                    "node.test-tool-call.${getWeatherInParisCallSerialized()}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "test-tool-call",
//                            "koog.node.input" to getWeatherInParisCallSerialized(),
//                            "koog.node.output" to TestGetWeatherTool.DEFAULT_PARIS_RESULT
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//                mapOf(
//                    "tool.Get whether" to mapOf(
//                        "attributes" to mapOf(
//                            "output.value" to TestGetWeatherTool.DEFAULT_PARIS_RESULT,
//                            "input.value" to "{\"location\":\"Paris\"}",
//                            "gen_ai.tool.description" to "The test tool to get a whether based on provided location.",
//                            "gen_ai.tool.name" to "Get whether",
//                            "gen_ai.tool.call.id" to toolCallId,
//                        ),
//                        "events" to mapOf()
//                    )
//                ),
//                mapOf(
//                    "node.test-llm-call.\"${userPrompt}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "test-llm-call",
//                            "koog.node.input" to "\"$userPrompt\"",
//                            "koog.node.output" to getWeatherInParisCallSerialized(typeOf<Message>()),
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//                mapOf(
//                    "llm.${userPrompt}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.request.model" to model.id,
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "gen_ai.operation.name" to "chat",
//                            "gen_ai.request.temperature" to temperature,
//                            "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.ToolCalls.id),
//                        ),
//                        "events" to mapOf(
//                            "gen_ai.system.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.System.name.lowercase(),
//                                "content" to systemPrompt,
//                            ),
//                            "gen_ai.user.message" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "role" to Message.Role.User.name.lowercase(),
//                                "content" to userPrompt,
//                            ),
//                            "gen_ai.choice" to mapOf(
//                                "gen_ai.system" to model.provider.id,
//                                "index" to 0L,
//                                "role" to Message.Role.Tool.name.lowercase(),
//                                "tool_calls" to """[{"function":{"name":"${TestGetWeatherTool.name}","arguments":"{\"location\":\"Paris\"}"},"id":"$toolCallId","type":"function"}]""",
//                                "finish_reason" to SpanAttributes.Response.FinishReasonType.ToolCalls.id,
//                            ),
//                        )
//                    )
//                ),
//                mapOf(
//                    "node.__start__.\"${userPrompt}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "__start__",
//                            "koog.node.input" to "\"$userPrompt\"",
//                            "koog.node.output" to "\"${userPrompt}\""
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//            )
//
//            assertSpans(expectedSpans, collectedSpans)
//        }
//    }
//
//    @Test
//    fun `test spans are created for agent with parallel nodes execution`() = runTest {
//        MockSpanExporter().use { mockExporter ->
//
//            val userPrompt = "What's the best joke about programming?"
//            val agentId = "test-agent-id"
//            val promptId = "test-prompt-id"
//            val model = OpenAIModels.Chat.GPT4o
//            val temperature = 0.4
//
//            val strategy = strategy("test-strategy") {
//                val nodeFirstJoke by node<String, String> { topic ->
//                    "First joke about $topic: Why do programmers prefer dark mode? Because light attracts bugs!"
//                }
//
//                val nodeSecondJoke by node<String, String> { topic ->
//                    "Second joke about $topic: Why do Java developers wear glasses? Because they don't C#!"
//                }
//
//                val nodeThirdJoke by node<String, String> { topic ->
//                    "Third joke about $topic: A SQL query walks into a bar, walks up to two tables and asks, 'Can I join you?'"
//                }
//
//                // Define a node to run joke generation in parallel
//                val nodeGenerateJokes by parallel(
//                    nodeFirstJoke,
//                    nodeSecondJoke,
//                    nodeThirdJoke
//                ) {
//                    selectByIndex {
//                        // Always select the first joke for testing purposes
//                        0
//                    }
//                }
//
//                edge(nodeStart forwardTo nodeGenerateJokes)
//                edge(nodeGenerateJokes forwardTo nodeFinish)
//            }
//
//            val mockResponse = "Why do programmers prefer dark mode? Because light attracts bugs!"
//
//            val mockExecutor = getMockExecutor(clock = testClock) {
//                mockLLMAnswer(mockResponse) onRequestEquals userPrompt
//            }
//
//            val agent = OpenTelemetryTestAPI.createAgent(
//                agentId = agentId,
//                strategy = strategy,
//                promptId = promptId,
//                promptExecutor = mockExecutor,
//                model = model,
//                clock = testClock,
//                temperature = temperature
//            ) {
//                install(OpenTelemetry.Feature) {
//                    addSpanExporter(mockExporter)
//                    setVerbose(true)
//                }
//            }
//
//            agent.run(userPrompt)
//
//            val collectedSpans = mockExporter.collectedSpans
//            assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")
//
//            agent.close()
//            // Check each span
//            // We expect spans for:
//            // 1. Agent creation
//            // 2. Agent run
//            // 3. Start node
//            // 4. Each parallel node (3 nodes)
//            // 5. Merge node
//            // 6. Finish node
//
//            // Verify that we have spans for all parallel nodes
//            val nodeSpanNames = collectedSpans.map { it.name }
//                .filter { it.startsWith("node.") }
//                .sorted()
//
//            logger.debug { "Node span names: $nodeSpanNames" }
//
//            // Print all node spans with their attributes for debugging
//            collectedSpans.filter { it.name.startsWith("node.") }.forEach { span ->
//                val attributes = span.attributes.asMap().asSequence().associate { it.key.key to it.value }
//                logger.debug { "Node span: ${span.name}, attributes: $attributes" }
//            }
//
//            // Check if we have the expected number of node spans (5 nodes)
//            assertEquals(6, nodeSpanNames.size, "Expected 6 node spans but found ${nodeSpanNames.size}")
//
//            // Check for each node span
//            assertTrue(nodeSpanNames.any { it.contains("nodeFirstJoke") }, "First joke node span should be created")
//            assertTrue(nodeSpanNames.any { it.contains("nodeSecondJoke") }, "Second joke node span should be created")
//            assertTrue(nodeSpanNames.any { it.contains("nodeThirdJoke") }, "Third joke node span should be created")
//            assertTrue(
//                nodeSpanNames.any { it.contains("nodeGenerateJokes") },
//                "Generate jokes node span should be created"
//            )
//
//            // Verify parallel node spans have the correct conversation ID
//            val parallelNodeSpans = collectedSpans.filter {
//                it.name.startsWith("node.") &&
//                        (
//                                it.name.contains("nodeFirstJoke") ||
//                                        it.name.contains("nodeSecondJoke") ||
//                                        it.name.contains("nodeThirdJoke")
//                                )
//            }
//
//            assertEquals(3, parallelNodeSpans.size, "Should have 3 parallel node spans")
//
//            parallelNodeSpans.forEach { span ->
//                val spanAttributes = span.attributes.asMap().asSequence().associate {
//                    it.key.key to it.value
//                }
//
//                assertEquals(
//                    mockExporter.lastRunId,
//                    spanAttributes["gen_ai.conversation.id"],
//                    "Parallel node span ${span.name} should have conversation ID '${mockExporter.lastRunId}'"
//                )
//            }
//        }
//    }
//
//    @Test
//    fun `test spans are created for agent with node execution error`() = runTest {
//        MockSpanExporter().use { mockExporter ->
//
//            val userPrompt = "What's the weather in Paris?"
//            val agentId = "test-agent-id"
//            val promptId = "test-prompt-id"
//            val model = OpenAIModels.Chat.GPT4o
//            val temperature = 0.4
//
//            val nodeWithErrorName = "node-with-error"
//            val testErrorMessage = "Test error"
//
//            val strategy = strategy("test-strategy") {
//                val nodeWithError by node<String, String>(nodeWithErrorName) {
//                    throw IllegalStateException(testErrorMessage)
//                }
//
//                edge(nodeStart forwardTo nodeWithError)
//                edge(nodeWithError forwardTo nodeFinish)
//            }
//
//            OpenTelemetryTestAPI.createAgent(
//                agentId = agentId,
//                strategy = strategy,
//                promptId = promptId,
//                model = model,
//                clock = testClock,
//                temperature = temperature
//            ) {
//                install(OpenTelemetry.Feature) {
//                    setVerbose(true)
//                    addSpanExporter(mockExporter)
//                }
//            }.use { agent ->
//                val throwable = assertFails {
//                    agent.run(userPrompt)
//                }
//
//                assertEquals(testErrorMessage, throwable.message)
//                assertTrue(mockExporter.collectedSpans.isNotEmpty(), "Spans should be created during agent execution")
//            }
//
//            // Check each span
//
//            val expectedSpans = listOf(
//                mapOf(
//                    "agent.$agentId" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "create_agent",
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.request.model" to model.id
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "run.${mockExporter.lastRunId}" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.operation.name" to "invoke_agent",
//                            "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.Error.id),
//                            "gen_ai.system" to model.provider.id,
//                            "gen_ai.agent.id" to agentId,
//                            "gen_ai.conversation.id" to mockExporter.lastRunId
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "node.node-with-error.\"${userPrompt}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "node-with-error",
//                            "koog.node.input" to "\"$userPrompt\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                ),
//
//                mapOf(
//                    "node.__start__.\"${userPrompt}\"" to mapOf(
//                        "attributes" to mapOf(
//                            "gen_ai.conversation.id" to mockExporter.lastRunId,
//                            "koog.node.name" to "__start__",
//                            "koog.node.input" to "\"$userPrompt\"",
//                            "koog.node.output" to "\"$userPrompt\"",
//                        ),
//                        "events" to emptyMap()
//                    )
//                )
//            )
//
//            assertSpans(expectedSpans, mockExporter.collectedSpans)
//        }
//    }

    //region Create Agent Span
    //endregion Create Agent Span

    //region Inference Span
    //endregion Inference Span

    //region Node Execute Span
    //endregion Node Execute Span

    //region Execute Tool Span
    //endregion Execute Tool Span


    private suspend fun runAgentWithTools() {

    }
}
